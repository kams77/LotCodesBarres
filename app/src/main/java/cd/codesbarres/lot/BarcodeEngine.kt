package cd.codesbarres.lot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

/** Symbologies proposées dans l'application. */
enum class Fmt(val label: String, val zx: BarcodeFormat, val hint: String) {
    CODE128("Code 128 — texte libre", BarcodeFormat.CODE_128,
        "Lettres, chiffres et symboles, sans accents. Longueur libre."),
    EAN13("EAN-13 — produits", BarcodeFormat.EAN_13,
        "12 chiffres : le 13e (contrôle) est calculé. 13 chiffres : il est vérifié."),
    EAN8("EAN-8 — petits emballages", BarcodeFormat.EAN_8,
        "7 chiffres : le 8e est calculé. 8 chiffres : il est vérifié."),
    UPCA("UPC-A — produits", BarcodeFormat.UPC_A,
        "11 chiffres : le 12e est calculé. 12 chiffres : il est vérifié."),
    CODE39("Code 39 — industrie, badges", BarcodeFormat.CODE_39,
        "Majuscules, chiffres, espace et - . \$ / + %"),
    ITF14("ITF-14 — cartons, colis", BarcodeFormat.ITF,
        "13 chiffres : le 14e est calculé. 14 chiffres : il est vérifié."),
    CODABAR("Codabar — bibliothèques, labos", BarcodeFormat.CODABAR,
        "Chiffres et - \$ : / . + ; A, B, C ou D au début et à la fin (ajoutés sinon)."),
    QR("QR code — texte, lien", BarcodeFormat.QR_CODE,
        "N'importe quel texte ou lien.");
}

object BarcodeEngine {

    private const val CODE39_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. \$/+%"
    private const val CODABAR_BODY = "0123456789-\$:/.+"

    /** Chiffre de contrôle GS1 (modulo 10, poids 3-1 depuis la droite). */
    fun gs1Check(data: String): Int {
        var sum = 0
        for (i in data.indices) {
            val n = data[data.length - 1 - i] - '0'
            sum += if (i % 2 == 0) n * 3 else n
        }
        return (10 - sum % 10) % 10
    }

    private fun gs1(v: String, total: Int): String {
        require(v.all { it.isDigit() }) { "chiffres uniquement" }
        return when (v.length) {
            total - 1 -> v + gs1Check(v)
            total -> {
                val expected = gs1Check(v.dropLast(1))
                require(v.last() - '0' == expected) { "chiffre de contrôle faux (attendu $expected)" }
                v
            }
            else -> throw IllegalArgumentException("${total - 1} ou $total chiffres attendus, ${v.length} reçus")
        }
    }

    /** Valide la valeur et la met sous sa forme finale (chiffre de contrôle, majuscules…). */
    fun normalize(f: Fmt, raw: String): String {
        val v = raw.trim()
        require(v.isNotEmpty()) { "valeur vide" }
        return when (f) {
            Fmt.EAN13 -> gs1(v, 13)
            Fmt.EAN8 -> gs1(v, 8)
            Fmt.UPCA -> gs1(v, 12)
            Fmt.ITF14 -> gs1(v, 14)
            Fmt.CODE39 -> v.uppercase().also { s ->
                val bad = s.firstOrNull { it !in CODE39_CHARS }
                require(bad == null) { "caractère « $bad » non autorisé" }
            }
            Fmt.CODE128 -> v.also { s ->
                val bad = s.firstOrNull { it.code !in 32..126 }
                require(bad == null) { "caractère « $bad » non autorisé (pas d'accents)" }
            }
            Fmt.CODABAR -> {
                var s = v.uppercase()
                val guards = "ABCD"
                if (s.first() !in guards) s = "A$s"
                if (s.last() !in guards) s = "${s}A"
                val body = s.substring(1, s.length - 1)
                require(body.isNotEmpty()) { "valeur vide" }
                val bad = body.firstOrNull { it !in CODABAR_BODY }
                require(bad == null) { "caractère « $bad » non autorisé" }
                s
            }
            Fmt.QR -> v
        }
    }

    /**
     * Dessine le code en image. [scale] = pixels par module.
     * Format RGB_565 pour limiter la mémoire sur les gros lots.
     */
    fun render(f: Fmt, value: String, showText: Boolean, scale: Int = 3): Bitmap {
        return if (f == Fmt.QR) renderQr(value, showText, scale) else render1d(f, value, showText, scale)
    }

    private fun render1d(f: Fmt, value: String, showText: Boolean, scale: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 10)
        val m: BitMatrix = MultiFormatWriter().encode(value, f.zx, 0, 1, hints)
        val w = m.width * scale
        val top = 6 * scale
        val barH = 50 * scale
        val textH = if (showText) 16 * scale else 4 * scale
        val bmp = Bitmap.createBitmap(w, top + barH + textH, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.BLACK; isAntiAlias = false }
        var x = 0
        while (x < m.width) {
            if (m[x, 0]) {
                var e = x
                while (e < m.width && m[e, 0]) e++
                c.drawRect((x * scale).toFloat(), top.toFloat(), (e * scale).toFloat(), (top + barH).toFloat(), p)
                x = e
            } else x++
        }
        if (showText) drawCaption(c, value, w, top + barH + 13f * scale, 11f * scale)
        return bmp
    }

    private fun renderQr(value: String, showText: Boolean, scale: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8")
        val m = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 0, 0, hints)
        val px = scale * 3
        val size = m.width * px
        val textH = if (showText) 14 * scale else 0
        val bmp = Bitmap.createBitmap(size, size + textH, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.BLACK }
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m[x, y]) c.drawRect((x * px).toFloat(), (y * px).toFloat(), ((x + 1) * px).toFloat(), ((y + 1) * px).toFloat(), p)
        }
        if (showText) {
            val shown = if (value.length > 40) value.take(38) + "…" else value
            drawCaption(c, shown, size, size + 9f * scale, 10f * scale)
        }
        return bmp
    }

    /** Texte centré, réduit si besoin pour tenir dans la largeur. */
    private fun drawCaption(c: Canvas, text: String, width: Int, baseline: Float, maxSize: Float) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            textSize = maxSize
        }
        while (tp.measureText(text) > width * 0.94f && tp.textSize > 6f) tp.textSize -= 1f
        c.drawText(text, width / 2f, baseline, tp)
    }
}
