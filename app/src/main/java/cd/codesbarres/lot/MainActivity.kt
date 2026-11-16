package cd.codesbarres.lot

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val MAX_ITEMS = 2000
        private const val MAX_PREVIEW = 40
    }

    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var spFormat: Spinner
    private lateinit var spCols: Spinner
    private lateinit var tvHint: TextView
    private lateinit var rgMode: RadioGroup
    private lateinit var boxSeq: View
    private lateinit var boxList: View
    private lateinit var etPrefix: EditText
    private lateinit var etSuffix: EditText
    private lateinit var etStart: EditText
    private lateinit var etCount: EditText
    private lateinit var etDigits: EditText
    private lateinit var etList: EditText
    private lateinit var tvSeqPreview: TextView
    private lateinit var cbText: CheckBox
    private lateinit var btnGenerate: Button
    private lateinit var btnPdf: Button
    private lateinit var btnZip: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvErrors: TextView
    private lateinit var previews: LinearLayout

    /** Lot validé, prêt à exporter. */
    private var batchFmt = Fmt.CODE128
    private var batchValues: List<String> = emptyList()
    private var batchShowText = true

    private val formats = Fmt.values()
    private val colChoices = listOf(2, 3, 4, 5)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spFormat = findViewById(R.id.spFormat)
        spCols = findViewById(R.id.spCols)
        tvHint = findViewById(R.id.tvHint)
        rgMode = findViewById(R.id.rgMode)
        boxSeq = findViewById(R.id.boxSeq)
        boxList = findViewById(R.id.boxList)
        etPrefix = findViewById(R.id.etPrefix)
        etSuffix = findViewById(R.id.etSuffix)
        etStart = findViewById(R.id.etStart)
        etCount = findViewById(R.id.etCount)
        etDigits = findViewById(R.id.etDigits)
        etList = findViewById(R.id.etList)
        tvSeqPreview = findViewById(R.id.tvSeqPreview)
        cbText = findViewById(R.id.cbText)
        btnGenerate = findViewById(R.id.btnGenerate)
        btnPdf = findViewById(R.id.btnPdf)
        btnZip = findViewById(R.id.btnZip)
        tvStatus = findViewById(R.id.tvStatus)
        tvErrors = findViewById(R.id.tvErrors)
        previews = findViewById(R.id.previews)

        spFormat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, formats.map { it.label })
        spFormat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                tvHint.text = formats[position].hint
                invalidateBatch()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spCols.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, colChoices.map { it.toString() })
        spCols.setSelection(1) // 3 colonnes

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val seq = checkedId == R.id.rbSeq
            boxSeq.visibility = if (seq) View.VISIBLE else View.GONE
            boxList.visibility = if (seq) View.GONE else View.VISIBLE
            invalidateBatch()
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateSeqPreview(); invalidateBatch() }
        }
        listOf(etPrefix, etSuffix, etStart, etCount, etDigits, etList).forEach { it.addTextChangedListener(watcher) }
        cbText.setOnCheckedChangeListener { _, _ -> invalidateBatch() }

        btnGenerate.setOnClickListener { generate() }
        btnPdf.setOnClickListener { exportPdf() }
        btnZip.setOnClickListener { exportZip() }

        updateSeqPreview()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun currentFmt(): Fmt = formats[spFormat.selectedItemPosition]

    private fun sequence(): List<String> {
        val start = etStart.text.toString().toLongOrNull() ?: 0L
        val count = (etCount.text.toString().toIntOrNull() ?: 0).coerceAtMost(MAX_ITEMS)
        val digits = (etDigits.text.toString().toIntOrNull() ?: 0).coerceIn(0, 18)
        val prefix = etPrefix.text.toString()
        val suffix = etSuffix.text.toString()
        return (0 until count).map { i -> prefix + (start + i).toString().padStart(digits, '0') + suffix }
    }

    private fun collectValues(): List<String> =
        if (rgMode.checkedRadioButtonId == R.id.rbSeq) sequence()
        else etList.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun updateSeqPreview() {
        val s = sequence()
        tvSeqPreview.text = when {
            s.isEmpty() -> "Quantité à 0 : rien à générer."
            s.size == 1 -> "→ ${s.first()}"
            else -> "→ ${s.first()}  …  ${s.last()}  (${s.size} codes)"
        }
    }

    /** Toute modification des réglages oblige à régénérer avant d'exporter. */
    private fun invalidateBatch() {
        if (batchValues.isNotEmpty()) {
            batchValues = emptyList()
            btnPdf.isEnabled = false
            btnZip.isEnabled = false
            tvStatus.setTextColor(getColor(R.color.muted))
            tvStatus.text = "Réglages modifiés : appuyez sur « Générer le lot »."
        }
    }

    private fun generate() {
        val fmt = currentFmt()
        val raw = collectValues()
        val showText = cbText.isChecked
        if (raw.isEmpty()) {
            showStatus("Aucune valeur à générer.", error = true)
            return
        }
        if (raw.size > MAX_ITEMS) {
            showStatus("Maximum $MAX_ITEMS codes par lot (vous en avez ${raw.size}).", error = true)
            return
        }
        btnGenerate.isEnabled = false
        btnPdf.isEnabled = false
        btnZip.isEnabled = false
        showStatus("Génération de ${raw.size} codes…")

        worker.execute {
            val ok = ArrayList<String>()
            val errors = ArrayList<String>()
            val thumbs = ArrayList<Pair<String, android.graphics.Bitmap>>()
            raw.forEach { v ->
                try {
                    val n = BarcodeEngine.normalize(fmt, v)
                    val bmp = BarcodeEngine.render(fmt, n, showText, 2)  // valide aussi l'encodage
                    if (thumbs.size < MAX_PREVIEW) thumbs.add(n to bmp) else bmp.recycle()
                    ok.add(n)
                } catch (e: Exception) {
                    errors.add("$v : ${e.message ?: "valeur invalide pour ce format"}")
                }
            }
            runOnUiThread {
                btnGenerate.isEnabled = true
                batchFmt = fmt
                batchValues = ok
                batchShowText = showText
                showPreviews(thumbs, ok.size)
                if (errors.isEmpty()) {
                    showStatus("${ok.size} codes prêts. Exportez en PDF ou en PNG.")
                } else {
                    showStatus("${ok.size} codes prêts, ${errors.size} valeurs rejetées.", error = ok.isEmpty())
                }
                tvErrors.visibility = if (errors.isEmpty()) View.GONE else View.VISIBLE
                tvErrors.text = errors.take(20).joinToString("\n") +
                    if (errors.size > 20) "\n… et ${errors.size - 20} autres" else ""
                btnPdf.isEnabled = ok.isNotEmpty()
                btnZip.isEnabled = ok.isNotEmpty()
            }
        }
    }

    private fun showPreviews(thumbs: List<Pair<String, android.graphics.Bitmap>>, total: Int) {
        previews.removeAllViews()
        val pad = (8 * resources.displayMetrics.density).toInt()
        thumbs.forEach { (value, bmp) ->
            val iv = ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                setBackgroundColor(Color.WHITE)
                setPadding(pad, pad, pad, pad)
                contentDescription = value
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = pad
            previews.addView(iv, lp)
        }
        if (total > thumbs.size) {
            previews.addView(TextView(this).apply {
                text = "+ ${total - thumbs.size} autres codes (inclus dans l'export)"
                setTextColor(getColor(R.color.muted))
                setPadding(0, pad, 0, pad * 2)
            })
        }
    }

    private fun exportPdf() {
        val values = batchValues
        if (values.isEmpty()) return
        val cols = colChoices[spCols.selectedItemPosition]
        runExport("PDF") { progress ->
            Exporter.pdf(this, batchFmt, values, batchShowText, cols, progress) to "application/pdf"
        }
    }

    private fun exportZip() {
        val values = batchValues
        if (values.isEmpty()) return
        runExport("ZIP") { progress ->
            Exporter.zip(this, batchFmt, values, batchShowText, progress) to "application/zip"
        }
    }

    private fun runExport(kind: String, job: ((Int) -> Unit) -> Pair<File, String>) {
        val total = batchValues.size
        btnPdf.isEnabled = false
        btnZip.isEnabled = false
        btnGenerate.isEnabled = false
        showStatus("Création du $kind… 0 / $total")
        worker.execute {
            val result = try {
                job { n -> runOnUiThread { showStatus("Création du $kind… $n / $total") } }
            } catch (e: Throwable) {
                null.also { runOnUiThread { showStatus("Échec de l'export : ${e.message}", error = true) } }
            }
            runOnUiThread {
                btnPdf.isEnabled = true
                btnZip.isEnabled = true
                btnGenerate.isEnabled = true
                if (result != null) {
                    showStatus("$kind prêt : ${result.first.name}")
                    share(result.first, result.second)
                }
            }
        }
    }

    private fun share(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Enregistrer ou envoyer ${file.name}"))
    }

    private fun showStatus(text: String, error: Boolean = false) {
        tvStatus.setTextColor(getColor(if (error) R.color.warn else R.color.ink))
        tvStatus.text = text
    }
}
