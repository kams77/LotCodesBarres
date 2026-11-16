package cd.codesbarres.lot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** Liaison brute vers une imprimante (Bluetooth, réseau ou USB). */
interface PrinterLink : Closeable {
    fun write(data: ByteArray)
    /** Pause entre deux blocs, pour ne pas saturer la mémoire de l'imprimante. */
    val pauseMs: Long
}

/** Bluetooth classique (profil série SPP), imprimante déjà appairée dans Android. */
class BluetoothLink @SuppressLint("MissingPermission") constructor(device: BluetoothDevice) : PrinterLink {
    private val socket: BluetoothSocket
    private val out: OutputStream
    override val pauseMs = 40L

    init {
        val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        socket = connectWithFallbacks(device, spp)
        out = socket.outputStream
    }

    @SuppressLint("MissingPermission")
    private fun connectWithFallbacks(d: BluetoothDevice, spp: UUID): BluetoothSocket {
        val attempts = listOf<() -> BluetoothSocket>(
            { d.createRfcommSocketToServiceRecord(spp) },
            { d.createInsecureRfcommSocketToServiceRecord(spp) },
            // Certaines imprimantes bon marché n'annoncent pas le service SPP : canal 1 direct.
            { d.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(d, 1) as BluetoothSocket }
        )
        var last: Exception? = null
        for (make in attempts) {
            var s: BluetoothSocket? = null
            try {
                s = make()
                s.connect()
                return s
            } catch (e: Exception) {
                last = e
                try { s?.close() } catch (_: IOException) {}
            }
        }
        throw IOException("connexion Bluetooth impossible (${last?.message ?: "imprimante éteinte ou hors de portée ?"})")
    }

    override fun write(data: ByteArray) { out.write(data); out.flush() }
    override fun close() {
        try { out.flush() } catch (_: IOException) {}
        Thread.sleep(300)   // laisse l'imprimante vider son tampon avant de couper
        socket.close()
    }
}

/** Imprimante réseau (Wi-Fi ou câble), port brut 9100 en général. */
class NetworkLink(host: String, port: Int) : PrinterLink {
    private val socket = Socket()
    private val out: OutputStream
    override val pauseMs = 10L

    init {
        try {
            socket.connect(InetSocketAddress(host, port), 5000)
        } catch (e: Exception) {
            throw IOException("impossible de joindre $host:$port (même réseau Wi-Fi ? adresse correcte ?)")
        }
        socket.soTimeout = 10000
        out = socket.getOutputStream()
    }

    override fun write(data: ByteArray) { out.write(data); out.flush() }
    override fun close() {
        try { out.flush() } catch (_: IOException) {}
        Thread.sleep(200)
        socket.close()
    }
}

/** Imprimante branchée en USB (câble OTG). L'autorisation doit déjà être accordée. */
class UsbLink(manager: UsbManager, device: UsbDevice) : PrinterLink {
    private val conn: UsbDeviceConnection
    private val intf: UsbInterface
    private val ep: UsbEndpoint
    override val pauseMs = 10L

    init {
        val found = findBulkOut(device) ?: throw IOException("aucune sortie d'impression trouvée sur ce périphérique USB")
        intf = found.first
        ep = found.second
        conn = manager.openDevice(device) ?: throw IOException("ouverture du périphérique USB refusée")
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            throw IOException("l'imprimante USB est occupée")
        }
    }

    override fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val len = minOf(4096, data.size - off)
            val n = conn.bulkTransfer(ep, data, off, len, 5000)
            if (n < 0) throw IOException("envoi USB interrompu")
            off += n
        }
    }

    override fun close() {
        conn.releaseInterface(intf)
        conn.close()
    }

    companion object {
        /** Interface de classe imprimante en priorité, sinon toute interface avec une sortie bulk. */
        fun findBulkOut(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
            var fallback: Pair<UsbInterface, UsbEndpoint>? = null
            for (i in 0 until device.interfaceCount) {
                val itf = device.getInterface(i)
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
                        if (itf.interfaceClass == UsbConstants.USB_CLASS_PRINTER) return itf to ep
                        if (fallback == null) fallback = itf to ep
                    }
                }
            }
            return fallback
        }

        fun isPrinterCandidate(device: UsbDevice) = findBulkOut(device) != null
    }
}

/** Commandes ESC/POS et mise en forme des étiquettes pour papier thermique. */
object PosPrinter {

    /** Largeur imprimable en points (203 dpi). */
    fun dotsFor(paperMm: Int) = if (paperMm >= 80) 576 else 384

    private val INIT = byteArrayOf(0x1B, 0x40)
    private fun feed(lines: Int) = byteArrayOf(0x1B, 0x64, lines.coerceIn(0, 255).toByte())
    private val CUT = byteArrayOf(0x1D, 0x56, 0x42, 0x00)

    /** Code-barres dimensionné pour la largeur du papier (modules entiers pour rester lisible). */
    fun barcodeBitmap(f: Fmt, value: String, showText: Boolean, dots: Int): Bitmap {
        val probe = BarcodeEngine.render(f, value, showText, 1)
        val usable = dots * (if (f == Fmt.QR) 0.6 else 0.94)
        val scale = (usable / probe.width).toInt().coerceIn(1, 4)
        probe.recycle()
        val bmp = BarcodeEngine.render(f, value, showText, scale)
        if (bmp.width <= dots) return bmp
        // Trop large même à l'échelle 1 : réduction (la lisibilité peut baisser).
        val h = (bmp.height * dots.toFloat() / bmp.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bmp, dots, h, false)
        bmp.recycle()
        return small
    }

    /** Image noir et blanc → commande raster GS v 0, découpée en bandes de 255 lignes max. */
    fun raster(bmp: Bitmap, dots: Int): ByteArray {
        val w = bmp.width.coerceAtMost(dots)
        val h = bmp.height
        val bytesPerRow = dots / 8
        val offset = (dots - w) / 2
        val px = IntArray(bmp.width * h)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, h)
        val out = ByteArrayOutputStream()
        var y0 = 0
        while (y0 < h) {
            val band = minOf(255, h - y0)
            out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00,
                (bytesPerRow and 0xFF).toByte(), (bytesPerRow shr 8).toByte(),
                (band and 0xFF).toByte(), (band shr 8).toByte()))
            val data = ByteArray(bytesPerRow * band)
            for (y in 0 until band) {
                val row = (y0 + y) * bmp.width
                for (x in 0 until w) {
                    val c = px[row + x]
                    val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                    if (lum < 128) {
                        val col = x + offset
                        val i = y * bytesPerRow + col / 8
                        data[i] = (data[i].toInt() or (0x80 shr (col % 8))).toByte()
                    }
                }
            }
            out.write(data)
            y0 += band
        }
        return out.toByteArray()
    }

    /** Envoie tout le lot. [progress] reçoit le nombre d'étiquettes envoyées. */
    fun printBatch(
        link: PrinterLink, f: Fmt, values: List<String>, showText: Boolean,
        paperMm: Int, copies: Int, cut: Boolean, progress: (Int, Int) -> Unit
    ) {
        val dots = dotsFor(paperMm)
        val total = values.size * copies
        var done = 0
        link.write(INIT)
        for (v in values) {
            val bmp = barcodeBitmap(f, v, showText, dots)
            val job = raster(bmp, dots) + feed(2)
            bmp.recycle()
            repeat(copies) {
                link.write(job)
                Thread.sleep(link.pauseMs)
                done++
                if (done % 5 == 0 || done == total) progress(done, total)
            }
        }
        link.write(feed(4))
        if (cut) link.write(CUT)
    }

    /** Petit ticket pour vérifier la liaison et la largeur du papier. */
    fun printTest(link: PrinterLink, paperMm: Int, cut: Boolean) {
        val dots = dotsFor(paperMm)
        val lines = listOf(
            "Lot Codes-Barres ${BuildConfig.VERSION_NAME}",
            "Test d'impression : OK",
            "Papier $paperMm mm ($dots points)"
        )
        val lh = 34
        val bmp = Bitmap.createBitmap(dots, lh * lines.size + 10, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        lines.forEachIndexed { i, s -> c.drawText(s, dots / 2f, lh * (i + 1f) - 6f, p) }
        // Règle de bord à bord : elle doit toucher les deux marges du papier.
        c.drawRect(0f, bmp.height - 4f, dots.toFloat(), bmp.height.toFloat(), Paint().apply { color = Color.BLACK })
        val code = barcodeBitmap(Fmt.CODE128, "TEST-123", true, dots)
        link.write(INIT)
        link.write(raster(bmp, dots) + feed(1))
        link.write(raster(code, dots) + feed(4))
        bmp.recycle(); code.recycle()
        if (cut) link.write(CUT)
    }
}
