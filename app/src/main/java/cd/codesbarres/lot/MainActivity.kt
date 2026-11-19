package cd.codesbarres.lot

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val MAX_ITEMS = 2000
        private const val MAX_PREVIEW = 40
        private const val REQ_SAVE_PDF = 10
        private const val REQ_BT = 20
        private const val ACTION_USB_PERMISSION = "cd.codesbarres.lot.USB_PERMISSION"
        private const val CONN_BT = 0
        private const val CONN_TPE = 1
        private const val CONN_NET = 2
        private const val CONN_USB = 3
        private const val CONN_SYS = 4
        /** Imprimante virtuelle des TPE Sunmi (et compatibles), toujours appairée. */
        private const val INNER_NAME = "InnerPrinter"
        private const val INNER_ADDR = "00:11:22:33:44:55"
    }

    private val worker = Executors.newSingleThreadExecutor()

    // Codes
    private lateinit var spFormat: Spinner
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
    private lateinit var etUrl: EditText
    private lateinit var cbHeader: CheckBox
    private lateinit var btnImport: Button
    private lateinit var tvSeqPreview: TextView
    private lateinit var cbText: CheckBox
    private lateinit var btnGenerate: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvErrors: TextView
    private lateinit var previews: LinearLayout
    // PDF
    private lateinit var spCols: Spinner
    private lateinit var btnSavePdf: Button
    private lateinit var btnPdf: Button
    private lateinit var btnZip: Button
    // Imprimante
    private lateinit var spConn: Spinner
    private lateinit var boxBt: View
    private lateinit var boxNet: View
    private lateinit var boxUsb: View
    private lateinit var boxTpe: View
    private lateinit var boxSys: View
    private lateinit var tvTpe: TextView
    private lateinit var rgSys: RadioGroup
    private lateinit var spBt: Spinner
    private lateinit var tvBt: TextView
    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var tvUsb: TextView
    private lateinit var spPaper: Spinner
    private lateinit var etCopies: EditText
    private lateinit var cbCut: CheckBox
    private lateinit var btnTestPrint: Button
    private lateinit var btnPrint: Button
    private lateinit var tvPrint: TextView

    /** Lot validé, prêt à exporter ou imprimer. */
    private var batchFmt = Fmt.CODE128
    private var batchValues: List<String> = emptyList()
    private var batchShowText = true

    private val formats = Fmt.values()
    private val colChoices = listOf(2, 3, 4, 5)
    private val paperChoices = listOf(58, 80)

    private var btDevices: List<BluetoothDevice> = emptyList()
    private var savedBtAddress: String? = null
    private var usbDevice: UsbDevice? = null
    private var pendingBt: (() -> Unit)? = null
    private var pendingUsb: (() -> Unit)? = null
    private var busy = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val action = pendingUsb
            pendingUsb = null
            if (granted && action != null) action()
            else if (!granted) printStatus("Accès USB refusé. Rebranchez l'imprimante et acceptez la demande.", true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        spFormat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, formats.map { it.label })
        spFormat.onItemSelectedListener = onSelect { pos -> tvHint.text = formats[pos].hint; invalidateBatch() }

        spCols.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, colChoices.map { it.toString() })
        spCols.setSelection(1)

        spPaper.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, paperChoices.map { "$it mm" })
        spConn.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Bluetooth (imprimante POS)", "TPE – imprimante intégrée", "Wi-Fi / réseau (POS)",
                "USB – câble OTG (POS)", "Impression Android (TPE, Wi-Fi, PDF)"))
        spConn.onItemSelectedListener = onSelect { showConnBox(it) }

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
        btnImport.setOnClickListener { importOnline() }
        btnSavePdf.setOnClickListener { askSavePdf() }
        btnPdf.setOnClickListener { sharePdf() }
        btnZip.setOnClickListener { shareZip() }
        btnBtRefresh().setOnClickListener { withBluetooth { refreshBtDevices() } }
        findViewById<Button>(R.id.btnUsbDetect).setOnClickListener { detectUsb() }
        findViewById<Button>(R.id.btnTpeDetect).setOnClickListener { withBluetooth { detectTpe() } }
        btnTestPrint.setOnClickListener { print(test = true) }
        btnPrint.setOnClickListener { print(test = false) }

        findViewById<TextView>(R.id.tvVersion).text =
            "Lot Codes-Barres ${BuildConfig.VERSION_NAME} · PDF · Internet · TPE · POS · Bluetooth"

        ContextCompat.registerReceiver(this, usbReceiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)

        restoreSettings()
        updateSeqPreview()
    }

    private fun btnBtRefresh(): Button = findViewById(R.id.btnBtRefresh)

    private fun bindViews() {
        spFormat = findViewById(R.id.spFormat)
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
        etUrl = findViewById(R.id.etUrl)
        cbHeader = findViewById(R.id.cbHeader)
        btnImport = findViewById(R.id.btnImport)
        tvSeqPreview = findViewById(R.id.tvSeqPreview)
        cbText = findViewById(R.id.cbText)
        btnGenerate = findViewById(R.id.btnGenerate)
        tvStatus = findViewById(R.id.tvStatus)
        tvErrors = findViewById(R.id.tvErrors)
        previews = findViewById(R.id.previews)
        spCols = findViewById(R.id.spCols)
        btnSavePdf = findViewById(R.id.btnSavePdf)
        btnPdf = findViewById(R.id.btnPdf)
        btnZip = findViewById(R.id.btnZip)
        spConn = findViewById(R.id.spConn)
        boxBt = findViewById(R.id.boxBt)
        boxNet = findViewById(R.id.boxNet)
        boxUsb = findViewById(R.id.boxUsb)
        boxTpe = findViewById(R.id.boxTpe)
        boxSys = findViewById(R.id.boxSys)
        tvTpe = findViewById(R.id.tvTpe)
        rgSys = findViewById(R.id.rgSys)
        spBt = findViewById(R.id.spBt)
        tvBt = findViewById(R.id.tvBt)
        etIp = findViewById(R.id.etIp)
        etPort = findViewById(R.id.etPort)
        tvUsb = findViewById(R.id.tvUsb)
        spPaper = findViewById(R.id.spPaper)
        etCopies = findViewById(R.id.etCopies)
        cbCut = findViewById(R.id.cbCut)
        btnTestPrint = findViewById(R.id.btnTestPrint)
        btnPrint = findViewById(R.id.btnPrint)
        tvPrint = findViewById(R.id.tvPrint)
    }

    private fun onSelect(block: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = block(position)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Valeurs

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

    /** Toute modification des réglages oblige à régénérer avant d'exporter ou d'imprimer. */
    private fun invalidateBatch() {
        if (batchValues.isNotEmpty()) {
            batchValues = emptyList()
            setOutputsEnabled(false)
            tvStatus.setTextColor(getColor(R.color.muted))
            tvStatus.text = "Réglages modifiés : appuyez sur « Générer le lot »."
        }
    }

    private fun setOutputsEnabled(on: Boolean) {
        val ok = on && batchValues.isNotEmpty() && !busy
        btnSavePdf.isEnabled = ok
        btnPdf.isEnabled = ok
        btnZip.isEnabled = ok
        btnPrint.isEnabled = ok
        btnGenerate.isEnabled = !busy
        btnTestPrint.isEnabled = !busy
        btnImport.isEnabled = !busy
    }

    private fun setBusy(b: Boolean) {
        busy = b
        setOutputsEnabled(true)
    }

    private fun importOnline() {
        val link = etUrl.text.toString().trim()
        if (link.isEmpty()) { showStatus("Collez d'abord un lien (Google Sheets, CSV ou TXT).", true); return }
        val skip = cbHeader.isChecked
        setBusy(true)
        showStatus("Téléchargement de la liste…")
        worker.execute {
            val result = try {
                Result.success(OnlineImport.parseFirstColumn(OnlineImport.download(link), skip))
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                setBusy(false)
                result.onSuccess { rows ->
                    if (rows.isEmpty()) showStatus("La liste téléchargée est vide.", true)
                    else {
                        val kept = rows.take(MAX_ITEMS)
                        etList.setText(kept.joinToString("\n"))
                        showStatus("${kept.size} valeurs importées" +
                            (if (rows.size > MAX_ITEMS) " (limitées à $MAX_ITEMS)" else "") +
                            ". Appuyez sur « Générer le lot ».")
                    }
                }.onFailure { e ->
                    val msg = when (e) {
                        is java.net.UnknownHostException -> "pas de connexion Internet ou adresse inconnue"
                        is java.net.SocketTimeoutException -> "le serveur ne répond pas (connexion lente ?)"
                        else -> e.message ?: "erreur inconnue"
                    }
                    showStatus("Import impossible : $msg", true)
                }
            }
        }
    }

    private fun generate() {
        val fmt = currentFmt()
        val raw = collectValues()
        val showText = cbText.isChecked
        if (raw.isEmpty()) { showStatus("Aucune valeur à générer.", true); return }
        if (raw.size > MAX_ITEMS) { showStatus("Maximum $MAX_ITEMS codes par lot (vous en avez ${raw.size}).", true); return }
        batchValues = emptyList()
        setBusy(true)
        showStatus("Génération de ${raw.size} codes…")

        worker.execute {
            val ok = ArrayList<String>()
            val errors = ArrayList<String>()
            val thumbs = ArrayList<Pair<String, Bitmap>>()
            raw.forEach { v ->
                try {
                    val n = BarcodeEngine.normalize(fmt, v)
                    val bmp = BarcodeEngine.render(fmt, n, showText, 2)   // valide aussi l'encodage
                    if (thumbs.size < MAX_PREVIEW) thumbs.add(n to bmp) else bmp.recycle()
                    ok.add(n)
                } catch (e: Exception) {
                    errors.add("$v : ${e.message ?: "valeur invalide pour ce format"}")
                }
            }
            runOnUiThread {
                batchFmt = fmt
                batchValues = ok
                batchShowText = showText
                setBusy(false)
                showPreviews(thumbs, ok.size)
                if (errors.isEmpty()) showStatus("${ok.size} codes prêts : enregistrez, envoyez ou imprimez.")
                else showStatus("${ok.size} codes prêts, ${errors.size} valeurs rejetées.", ok.isEmpty())
                tvErrors.visibility = if (errors.isEmpty()) View.GONE else View.VISIBLE
                tvErrors.text = errors.take(20).joinToString("\n") +
                    if (errors.size > 20) "\n… et ${errors.size - 20} autres" else ""
            }
        }
    }

    private fun showPreviews(thumbs: List<Pair<String, Bitmap>>, total: Int) {
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
                text = "+ ${total - thumbs.size} autres codes (inclus dans l'export et l'impression)"
                setTextColor(getColor(R.color.muted))
                setPadding(0, pad, 0, pad * 2)
            })
        }
    }

    // ---------------------------------------------------------------- PDF / ZIP

    @Suppress("DEPRECATION")
    private fun askSavePdf() {
        if (batchValues.isEmpty()) return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, Exporter.pdfFileName())
        }
        try {
            startActivityForResult(intent, REQ_SAVE_PDF)
        } catch (e: Exception) {
            showStatus("Aucun gestionnaire de fichiers disponible : utilisez « Envoyer PDF ».", true)
        }
    }

    @Deprecated("API Activity classique")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SAVE_PDF || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val values = batchValues
        val fmt = batchFmt
        val showText = batchShowText
        val cols = colChoices[spCols.selectedItemPosition]
        val total = values.size
        setBusy(true)
        showStatus("Enregistrement du PDF… 0 / $total")
        worker.execute {
            val err = try {
                val out = contentResolver.openOutputStream(uri) ?: throw java.io.IOException("emplacement inaccessible")
                out.use { Exporter.pdfTo(it, fmt, values, showText, cols) { n -> runOnUiThread { showStatus("Enregistrement du PDF… $n / $total") } } }
                null
            } catch (e: Throwable) { e }
            runOnUiThread {
                setBusy(false)
                if (err == null) showStatus("PDF enregistré ($total codes). Retrouvez-le dans Fichiers / Téléchargements.")
                else showStatus("Échec de l'enregistrement : ${err.message}", true)
            }
        }
    }

    private fun sharePdf() {
        val values = batchValues
        if (values.isEmpty()) return
        val cols = colChoices[spCols.selectedItemPosition]
        runExport("PDF") { progress -> Exporter.pdf(this, batchFmt, values, batchShowText, cols, progress) to "application/pdf" }
    }

    private fun shareZip() {
        val values = batchValues
        if (values.isEmpty()) return
        runExport("ZIP") { progress -> Exporter.zip(this, batchFmt, values, batchShowText, progress) to "application/zip" }
    }

    private fun runExport(kind: String, job: ((Int) -> Unit) -> Pair<File, String>) {
        val total = batchValues.size
        setBusy(true)
        showStatus("Création du $kind… 0 / $total")
        worker.execute {
            val result = try {
                job { n -> runOnUiThread { showStatus("Création du $kind… $n / $total") } }
            } catch (e: Throwable) {
                runOnUiThread { showStatus("Échec de l'export : ${e.message}", true) }
                null
            }
            runOnUiThread {
                setBusy(false)
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
        startActivity(Intent.createChooser(send, "Envoyer ${file.name}"))
    }

    // ---------------------------------------------------------------- Imprimante

    private fun showConnBox(conn: Int) {
        boxBt.visibility = if (conn == CONN_BT) View.VISIBLE else View.GONE
        boxNet.visibility = if (conn == CONN_NET) View.VISIBLE else View.GONE
        boxUsb.visibility = if (conn == CONN_USB) View.VISIBLE else View.GONE
        boxTpe.visibility = if (conn == CONN_TPE) View.VISIBLE else View.GONE
        boxSys.visibility = if (conn == CONN_SYS) View.VISIBLE else View.GONE
        // Le massicot ne concerne que l'envoi direct ESC/POS.
        cbCut.visibility = if (conn == CONN_SYS) View.GONE else View.VISIBLE
        when (conn) {
            // Au démarrage, ne pas imposer la demande d'autorisation : on liste seulement si déjà accordée.
            CONN_BT -> if (btDevices.isEmpty()) {
                if (Build.VERSION.SDK_INT < 31 ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) refreshBtDevices()
                else tvBt.text = "Appuyez sur « Actualiser » pour lister les imprimantes Bluetooth appairées."
            }
            CONN_USB -> detectUsb()
            CONN_TPE -> if (Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) detectTpe()
        }
    }

    /** Cherche l'imprimante intégrée du TPE. Renvoie null si absente (message affiché). */
    @SuppressLint("MissingPermission")
    private fun detectTpe(): BluetoothDevice? {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { tvTpe.text = "Pas d'imprimante intégrée détectée sur cet appareil."; return null }
        if (!adapter.isEnabled) {
            tvTpe.text = "Activez le Bluetooth : l'imprimante intégrée du TPE passe par lui (sans appairage)."
            return null
        }
        val dev = adapter.bondedDevices.firstOrNull {
            it.address.equals(INNER_ADDR, true) || (it.name ?: "").equals(INNER_NAME, true)
        }
        tvTpe.text = if (dev != null) "Imprimante intégrée trouvée : ${dev.name ?: INNER_NAME}. Prête."
        else "Aucune imprimante intégrée trouvée. Sur un TPE PAX, Ingenico, Verifone…, utilisez « Impression Android »."
        return dev
    }

    /** Demande l'autorisation Bluetooth (Android 12+) avant d'exécuter [then]. */
    private fun withBluetooth(then: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            pendingBt = then
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
        } else then()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BT) return
        val action = pendingBt
        pendingBt = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) action?.invoke()
        else tvBt.text = "Autorisation « Appareils à proximité » refusée : impossible de lister les imprimantes Bluetooth."
    }

    @SuppressLint("MissingPermission")
    private fun refreshBtDevices() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { tvBt.text = "Ce téléphone n'a pas de Bluetooth."; return }
        if (!adapter.isEnabled) { tvBt.text = "Activez le Bluetooth, puis appuyez sur « Actualiser »."; return }
        btDevices = adapter.bondedDevices.sortedBy { it.name ?: it.address }
        val labels = btDevices.map { "${it.name ?: "Appareil"} (${it.address})" }
        spBt.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            labels.ifEmpty { listOf("Aucun appareil appairé") })
        val idx = btDevices.indexOfFirst { it.address == savedBtAddress }
        if (idx >= 0) spBt.setSelection(idx)
        else {
            // Proposer en premier un appareil dont le nom ressemble à une imprimante.
            val guess = btDevices.indexOfFirst {
                val n = (it.name ?: "").lowercase()
                listOf("print", "pos", "mtp", "inner", "xp-", "rpp", "pt-", "goojprt", "zj", "58", "80").any { k -> n.contains(k) }
            }
            if (guess >= 0) spBt.setSelection(guess)
        }
        tvBt.text = if (btDevices.isEmpty())
            "Aucune imprimante appairée. Allez dans Réglages → Bluetooth, appairez-la (code 0000 ou 1234), puis « Actualiser »."
        else "${btDevices.size} appareil(s) appairé(s). Choisissez l'imprimante."
    }

    private fun detectUsb() {
        val mgr = getSystemService(UsbManager::class.java)
        val found = mgr?.deviceList?.values?.firstOrNull { UsbLink.isPrinterCandidate(it) }
        usbDevice = found
        tvUsb.text = if (found == null) "Aucune imprimante USB détectée. Branchez-la avec un câble OTG, puis « Détecter »."
        else "Détectée : ${found.productName ?: "imprimante USB"} (${found.manufacturerName ?: "—"})"
    }

    private fun paperMm() = paperChoices[spPaper.selectedItemPosition]

    private fun print(test: Boolean) {
        if (!test && batchValues.isEmpty()) return
        when (spConn.selectedItemPosition) {
            CONN_BT -> withBluetooth {
                if (btDevices.isEmpty()) refreshBtDevices()
                val dev = btDevices.getOrNull(spBt.selectedItemPosition)
                if (dev == null) printStatus("Choisissez une imprimante Bluetooth appairée.", true)
                else {
                    savedBtAddress = dev.address
                    runPrint(test) { BluetoothLink(dev) }
                }
            }
            CONN_TPE -> withBluetooth {
                val dev = detectTpe()
                if (dev == null) printStatus("Imprimante intégrée introuvable (voir le message ci-dessus).", true)
                else runPrint(test) { BluetoothLink(dev) }
            }
            CONN_SYS -> systemPrint(test)
            CONN_NET -> {
                val ip = etIp.text.toString().trim()
                val port = etPort.text.toString().toIntOrNull() ?: 9100
                if (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(ip)) printStatus("Saisissez l'adresse IP de l'imprimante (ex. 192.168.1.100).", true)
                else runPrint(test) { NetworkLink(ip, port) }
            }
            CONN_USB -> {
                detectUsb()
                val dev = usbDevice
                val mgr = getSystemService(UsbManager::class.java)
                if (dev == null || mgr == null) { printStatus("Aucune imprimante USB détectée.", true); return }
                val go = { runPrint(test) { UsbLink(mgr, dev) } }
                if (mgr.hasPermission(dev)) go()
                else {
                    pendingUsb = go
                    val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                    val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
                    mgr.requestPermission(dev, pi)
                    printStatus("Acceptez l'accès à l'imprimante USB dans la fenêtre qui s'affiche.")
                }
            }
        }
    }

    /** Impression par le système Android : TPE avec service d'impression, imprimantes Wi-Fi, PDF. */
    private fun systemPrint(test: Boolean) {
        val pm = getSystemService(android.print.PrintManager::class.java)
        if (pm == null) { printStatus("L'impression Android n'est pas disponible sur cet appareil.", true); return }
        val copies = (etCopies.text.toString().toIntOrNull() ?: 1).coerceIn(1, 50)
        val fmt = if (test) Fmt.CODE128 else batchFmt
        val base = if (test) listOf("TEST-123") else batchValues
        val values = if (copies > 1) base.flatMap { v -> List(copies) { v } } else base
        val roll = rgSys.checkedRadioButtonId == R.id.rbSysRoll
        val paper = paperMm()
        val name = if (test) "test-impression" else Exporter.pdfFileName().removeSuffix(".pdf")
        val adapter = BatchPrintAdapter(name, fmt, values, if (test) true else batchShowText, roll,
            colChoices[spCols.selectedItemPosition], paper)
        val attrs = android.print.PrintAttributes.Builder()
            .setMediaSize(BatchPrintAdapter.mediaFor(roll, paper))
            .setColorMode(android.print.PrintAttributes.COLOR_MODE_MONOCHROME)
            .build()
        try {
            pm.print(name, adapter, attrs)
            printStatus("Choisissez l'imprimante dans la fenêtre d'impression d'Android (${values.size} étiquette(s)).")
        } catch (e: Exception) {
            printStatus("Impression Android impossible : ${e.message}", true)
        }
    }

    private fun runPrint(test: Boolean, open: () -> PrinterLink) {
        val values = batchValues
        val fmt = batchFmt
        val showText = batchShowText
        val paper = paperMm()
        val copies = (etCopies.text.toString().toIntOrNull() ?: 1).coerceIn(1, 50)
        val cut = cbCut.isChecked
        setBusy(true)
        printStatus("Connexion à l'imprimante…")
        worker.execute {
            val err = try {
                open().use { link ->
                    if (test) {
                        runOnUiThread { printStatus("Impression de la page de test…") }
                        PosPrinter.printTest(link, paper, cut)
                    } else {
                        PosPrinter.printBatch(link, fmt, values, showText, paper, copies, cut) { n, total ->
                            runOnUiThread { printStatus("Impression… $n / $total étiquettes") }
                        }
                    }
                }
                null
            } catch (e: Throwable) { e }
            runOnUiThread {
                setBusy(false)
                if (err == null) printStatus(if (test) "Page de test envoyée à l'imprimante."
                    else "Impression terminée : ${values.size * copies} étiquettes envoyées.")
                else printStatus("Impression impossible : ${err.message ?: err.javaClass.simpleName}", true)
            }
        }
    }

    // ---------------------------------------------------------------- Réglages

    private fun saveSettings() {
        getSharedPreferences("reglages", MODE_PRIVATE).edit()
            .putString("format", currentFmt().name)
            .putBoolean("sequence", rgMode.checkedRadioButtonId == R.id.rbSeq)
            .putString("prefix", etPrefix.text.toString())
            .putString("suffix", etSuffix.text.toString())
            .putString("start", etStart.text.toString())
            .putString("count", etCount.text.toString())
            .putString("digits", etDigits.text.toString())
            .putString("list", etList.text.toString())
            .putString("url", etUrl.text.toString())
            .putBoolean("header", cbHeader.isChecked)
            .putInt("cols", spCols.selectedItemPosition)
            .putBoolean("text", cbText.isChecked)
            .putInt("conn2", spConn.selectedItemPosition)
            .putBoolean("sysRoll", rgSys.checkedRadioButtonId == R.id.rbSysRoll)
            .putString("bt", btDevices.getOrNull(spBt.selectedItemPosition)?.address ?: savedBtAddress)
            .putString("ip", etIp.text.toString())
            .putString("port", etPort.text.toString())
            .putInt("paper", spPaper.selectedItemPosition)
            .putString("copies", etCopies.text.toString())
            .putBoolean("cut", cbCut.isChecked)
            .apply()
    }

    private fun restoreSettings() {
        val p = getSharedPreferences("reglages", MODE_PRIVATE)
        savedBtAddress = p.getString("bt", null)
        if (!p.contains("format")) return
        val fi = formats.indexOfFirst { it.name == p.getString("format", null) }
        if (fi >= 0) spFormat.setSelection(fi)
        rgMode.check(if (p.getBoolean("sequence", true)) R.id.rbSeq else R.id.rbList)
        etPrefix.setText(p.getString("prefix", etPrefix.text.toString()))
        etSuffix.setText(p.getString("suffix", ""))
        etStart.setText(p.getString("start", "1"))
        etCount.setText(p.getString("count", "24"))
        etDigits.setText(p.getString("digits", "5"))
        etList.setText(p.getString("list", etList.text.toString()))
        etUrl.setText(p.getString("url", ""))
        cbHeader.isChecked = p.getBoolean("header", true)
        spCols.setSelection(p.getInt("cols", 1).coerceIn(0, colChoices.size - 1))
        cbText.isChecked = p.getBoolean("text", true)
        val conn = if (p.contains("conn2")) p.getInt("conn2", CONN_BT)
            else when (p.getInt("conn", 0)) { 1 -> CONN_NET; 2 -> CONN_USB; else -> CONN_BT }
        spConn.setSelection(conn.coerceIn(0, CONN_SYS))
        rgSys.check(if (p.getBoolean("sysRoll", true)) R.id.rbSysRoll else R.id.rbSysSheet)
        etIp.setText(p.getString("ip", ""))
        etPort.setText(p.getString("port", "9100"))
        spPaper.setSelection(p.getInt("paper", 0).coerceIn(0, paperChoices.size - 1))
        etCopies.setText(p.getString("copies", "1"))
        cbCut.isChecked = p.getBoolean("cut", true)
    }

    private fun showStatus(text: String, error: Boolean = false) {
        tvStatus.setTextColor(getColor(if (error) R.color.warn else R.color.ink))
        tvStatus.text = text
    }

    private fun printStatus(text: String, error: Boolean = false) {
        tvPrint.setTextColor(getColor(if (error) R.color.warn else R.color.ink))
        tvPrint.text = text
    }
}
