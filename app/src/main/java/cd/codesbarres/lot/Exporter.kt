package cd.codesbarres.lot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Export du lot en PDF A4 (planche d'étiquettes) ou en ZIP de PNG. */
object Exporter {

    private fun exportDir(ctx: Context): File {
        val dir = File(ctx.cacheDir, "exports")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }   // on ne garde que le dernier export
        return dir
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())

    private fun safeName(v: String): String =
        v.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)

    fun pdfFileName(): String = "codes-barres_${stamp()}.pdf"

    fun pdf(ctx: Context, f: Fmt, values: List<String>, showText: Boolean, cols: Int, progress: (Int) -> Unit): File {
        val out = File(exportDir(ctx), pdfFileName())
        FileOutputStream(out).use { pdfTo(it, f, values, showText, cols, progress) }
        return out
    }

    /** Écrit la planche PDF A4 dans n'importe quel flux (fichier choisi par l'utilisateur, cache…). */
    fun pdfTo(stream: OutputStream, f: Fmt, values: List<String>, showText: Boolean, cols: Int, progress: (Int) -> Unit) {
        val pageW = 595   // A4 en points
        val pageH = 842
        val margin = 28f
        val gap = 8f
        val cellW = (pageW - 2 * margin - (cols - 1) * gap) / cols

        // Hauteur de case : on prend le rapport hauteur/largeur le plus grand parmi les premiers codes.
        var aspect = 0f
        values.take(30).forEach { v ->
            val b = BarcodeEngine.render(f, v, showText, 1)
            aspect = maxOf(aspect, b.height.toFloat() / b.width)
            b.recycle()
        }
        aspect = aspect.coerceIn(0.2f, 1.3f)
        val cellH = cellW * aspect + 6f
        val rows = ((pageH - 2 * margin + gap) / (cellH + gap)).toInt().coerceAtLeast(1)
        val perPage = rows * cols

        val doc = PdfDocument()
        val img = Paint().apply { isFilterBitmap = false }
        val cut = Paint().apply {
            color = Color.rgb(205, 210, 206)
            style = Paint.Style.STROKE
            strokeWidth = 0.5f
        }
        var done = 0
        values.chunked(perPage).forEachIndexed { pageIndex, chunk ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageIndex + 1).create())
            val c = page.canvas
            chunk.forEachIndexed { i, v ->
                val r = i / cols
                val col = i % cols
                val x = margin + col * (cellW + gap)
                val y = margin + r * (cellH + gap)
                c.drawRect(x, y, x + cellW, y + cellH, cut)
                val b: Bitmap = BarcodeEngine.render(f, v, showText, 4)
                val boxW = cellW - 6f
                val boxH = cellH - 6f
                val k = minOf(boxW / b.width, boxH / b.height)
                val dw = b.width * k
                val dh = b.height * k
                val dx = x + (cellW - dw) / 2
                val dy = y + (cellH - dh) / 2
                c.drawBitmap(b, null, RectF(dx, dy, dx + dw, dy + dh), img)
                b.recycle()
                done++
                if (done % 10 == 0) progress(done)
            }
            doc.finishPage(page)
        }
        doc.writeTo(stream)
        doc.close()
    }

    fun zip(ctx: Context, f: Fmt, values: List<String>, showText: Boolean, progress: (Int) -> Unit): File {
        val out = File(exportDir(ctx), "codes-barres_${stamp()}.zip")
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            values.forEachIndexed { i, v ->
                val b = BarcodeEngine.render(f, v, showText, 4)
                zos.putNextEntry(ZipEntry(String.format(Locale.US, "%04d_%s.png", i + 1, safeName(v))))
                b.compress(Bitmap.CompressFormat.PNG, 100, zos)
                zos.closeEntry()
                b.recycle()
                if ((i + 1) % 10 == 0) progress(i + 1)
            }
        }
        return out
    }
}
