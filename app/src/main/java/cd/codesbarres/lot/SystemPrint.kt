package cd.codesbarres.lot

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Impression par le système Android (PrintManager).
 * Passe par les services d'impression installés : services des fabricants de TPE,
 * Mopria, HP, Epson, Canon, Brother… et « Enregistrer au format PDF ».
 */
class BatchPrintAdapter(
    private val jobName: String,
    private val fmt: Fmt,
    private val values: List<String>,
    private val showText: Boolean,
    private val roll: Boolean,
    private val cols: Int,
    private val paperMm: Int
) : PrintDocumentAdapter() {

    private val worker = Executors.newSingleThreadExecutor()

    override fun onLayout(
        oldAttributes: PrintAttributes?, newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?
    ) {
        if (cancellationSignal.isCanceled) { callback.onLayoutCancelled(); return }
        val info = PrintDocumentInfo.Builder("$jobName.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        callback.onLayoutFinished(info, oldAttributes == null || oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>, destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal, callback: WriteResultCallback
    ) {
        worker.execute {
            try {
                FileOutputStream(destination.fileDescriptor).use { out ->
                    if (roll) Exporter.ticketPdfTo(out, fmt, values, showText, paperMm) {}
                    else Exporter.pdfTo(out, fmt, values, showText, cols) {}
                }
                if (cancellationSignal.isCanceled) callback.onWriteCancelled()
                else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Throwable) {
                callback.onWriteFailed(e.message ?: "erreur de création du document")
            }
        }
    }

    override fun onFinish() {
        worker.shutdown()
    }

    companion object {
        /** Format de page proposé à la boîte d'impression. */
        fun mediaFor(roll: Boolean, paperMm: Int): PrintAttributes.MediaSize =
            if (!roll) PrintAttributes.MediaSize.ISO_A4
            else {
                val mils = Math.round(paperMm / 25.4f * 1000)
                // Format portrait (hauteur ≥ largeur) : la largeur reste celle du rouleau.
                PrintAttributes.MediaSize("lcb_roll_$paperMm", "Rouleau $paperMm mm", mils, Math.round(mils * 1.5f))
            }
    }
}
