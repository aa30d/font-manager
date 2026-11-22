package org.fontmanager

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val engine: FontEngine = AndroidFontEngine()
    private val worker = Executors.newSingleThreadExecutor()
    private val fonts = mutableListOf<Entry>()
    private val sources = mutableListOf<Source>()
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private var selected: Entry? = null
    private var previewText = "درخت دوستی بنشان که کام دل به بار آرد\nThe quick brown fox jumps over the lazy dog."
    private var size = 48f
    private var direction = 0
    private var alignment = 1
    private var dark = false
    @Volatile private var scanGeneration = 0
    private data class Entry(val file: File, val name: String, val source: String, val info: FontInspection)
    private data class Source(val uri: Uri, var deep: Boolean)
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = prefs.getBoolean("dark", false)
        loadSources(); home(); handleIntent(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    override fun onDestroy() { scanGeneration++; worker.shutdownNow(); super.onDestroy() }
    private fun handleIntent(intent: Intent?) {
        val uris = mutableListOf<Uri>()
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uris.add(it) }
            Intent.ACTION_SEND -> (intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)?.filterIsInstance<Uri>()?.let { uris.addAll(it) }
        }
        if (uris.isNotEmpty()) importUris(uris, "اشتراک‌گذاری")
    }
    private fun loadSources() {
        prefs.getStringSet("sources", emptySet())?.forEach { value ->
            val parts = value.split("|", limit = 2)
            if (parts.size == 2) sources.add(Source(Uri.parse(parts[1]), parts[0] == "1"))
        }
    }
    private fun saveSources() { prefs.edit().putStringSet("sources", sources.map { "${if (it.deep) 1 else 0}|${it.uri}" }.toSet()).apply() }
    private fun screen(title: String): LinearLayout {
        val scroll = ScrollView(this); root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(if (dark) Color.BLACK else Color.WHITE) }
        scroll.addView(root); setContentView(scroll)
        label(title, 24f)
        return root
    }
    private fun label(text: String, textSize: Float = 16f): TextView = TextView(this).also {
        it.text = text; it.textSize = textSize; it.setTextColor(if (dark) Color.WHITE else Color.BLACK); it.setPadding(0, 12, 0, 12); root.addView(it)
    }
    private fun button(text: String, action: () -> Unit) { root.addView(Button(this).apply { this.text = text; setOnClickListener { action() } }) }
    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun home() {
        screen("مدیریت فونت")
        button("باز کردن فایل") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }, 1) }
        button("افزودن پوشه") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 2) }
        button("تازه‌سازی پوشه‌ها") { scanSources() }
        button(if (dark) "تم روز" else "تم شب") { dark = !dark; prefs.edit().putBoolean("dark", dark).apply(); home() }
        if (sources.isNotEmpty()) label("پوشه‌ها", 20f)
        sources.toList().forEach { source ->
            label(source.uri.toString())
            root.addView(Switch(this).apply { text = "جست‌وجوی عمیق"; setTextColor(if (dark) Color.WHITE else Color.BLACK); isChecked = source.deep; setOnCheckedChangeListener { _, checked -> source.deep = checked; saveSources() } })
            button("حذف این پوشه") { sources.remove(source); saveSources(); try { contentResolver.releasePersistableUriPermission(source.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}; home() }
        }
        status = label("${fonts.size} فونت در فهرست. برای بررسی منابع، تازه‌سازی را بزنید.")
        val search = EditText(this).apply { hint = "جست‌وجوی نام فایل یا منبع"; setSingleLine(); setTextColor(if (dark) Color.WHITE else Color.BLACK); setHintTextColor(if (dark) Color.LTGRAY else Color.DKGRAY) }
        root.addView(search)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(list)
        fun update() {
            list.removeAllViews(); val query = search.text.toString()
            fonts.filter { it.name.contains(query, true) || it.source.contains(query, true) }.forEach { entry ->
                list.addView(Button(this).apply { text = "${entry.name} · ${entry.info.format}\n${entry.source}"; setOnClickListener { selected = entry; preview() } })
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {} ; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { update() }; override fun afterTextChanged(s: android.text.Editable?) {} })
        update()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            1 -> { val uris = mutableListOf<Uri>(); data.data?.let { uris.add(it) }; data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }; importUris(uris.distinct(), "فایل انتخاب‌شده") }
            2 -> data.data?.let { uri -> try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (sources.none { it.uri == uri }) sources.add(Source(uri, false)); saveSources(); home()
            } catch (e: Exception) { message("دسترسی پایدار به پوشه ممکن نشد: ${e.message}") } }
            3 -> data.data?.let { uri -> exportPng(uri) }
        }
    }
    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "فونت"
    } catch (_: Exception) { "فونت" }
    private fun copyAndInspect(uri: Uri, name: String, source: String): Entry {
        val file = File.createTempFile("font-", ".bin", cacheDir)
        try {
            contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(32768); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 64L * 1024 * 1024) { "فایل از حد ۶۴ مگابایت بزرگ‌تر است" }; output.write(buffer, 0, n) }
            } } ?: error("فایل خواندنی نیست")
            val info = engine.inspect(file)
            if (info.format == "TTF" || info.format == "OTF") engine.typeface(file) // Reject files the renderer cannot load.
            return Entry(file, name, source, info)
        } catch (e: Exception) { file.delete(); throw e }
    }
    private fun importUris(uris: List<Uri>, source: String) {
        status.text = "در حال خواندن…"
        worker.execute { uris.forEach { uri ->
            try { val entry = copyAndInspect(uri, displayName(uri), source); runOnUiThread { fonts.add(entry); home() } }
            catch (e: Exception) { runOnUiThread { message("فایل خوانده نشد: ${e.message}") } }
        }; runOnUiThread { home() } }
    }
    private fun scanSources() {
        val generation = ++scanGeneration
        fonts.removeAll { it.source.startsWith("پوشه: ") }; home(); status.text = "در حال بررسی پوشه‌ها…"
        val snapshot = sources.map { Source(it.uri, it.deep) }
        worker.execute {
            snapshot.forEach { source ->
                var count = 0; var errors = 0
                try {
                    val visited = mutableSetOf<String>()
                    fun scan(id: String) {
                        if (Thread.currentThread().isInterrupted || generation != scanGeneration || !visited.add(id)) return
                        val children = DocumentsContract.buildChildDocumentsUriUsingTree(source.uri, id)
                        contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                            while (c.moveToNext() && generation == scanGeneration) {
                                val childId = c.getString(0); val name = c.getString(1) ?: "فونت"; val mime = c.getString(2)
                                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { if (source.deep) scan(childId) }
                                else if (listOf(".ttf", ".otf", ".woff", ".woff2").any { name.endsWith(it, true) }) {
                                    try {
                                        val entry = copyAndInspect(DocumentsContract.buildDocumentUriUsingTree(source.uri, childId), name, "پوشه: ${source.uri}")
                                        count++; runOnUiThread { if (generation == scanGeneration) { fonts.add(entry); home(); status.text = "در حال بررسی… $count فونت" } }
                                    } catch (_: Exception) { errors++ }
                                }
                            }
                        } ?: error("پوشه خواندنی نیست")
                    }
                    scan(DocumentsContract.getTreeDocumentId(source.uri))
                    runOnUiThread { if (generation == scanGeneration) status.text = "${source.uri}: $count فونت، $errors فایل نامعتبر یا خوانده‌نشده" }
                } catch (e: Exception) { runOnUiThread { if (generation == scanGeneration) { status.text = "دسترسی به ${source.uri} ممکن نیست؛ پوشه را دوباره انتخاب کنید."; message(status.text.toString()) } } }
            }
        }
    }
    private fun preview() {
        val entry = selected ?: return
        screen("پیش‌نمایش · ${entry.name}")
        button("بازگشت به فهرست") { home() }
        label("${entry.info.format} · ${entry.source}")
        entry.info.warnings.forEach { label(it) }
        label("جدول‌های شناسایی‌شده: ${entry.info.tables.sorted().joinToString().ifEmpty { "قابل‌خواندن نیست" }}")
        if (entry.info.format == "WOFF" || entry.info.format == "WOFF2") return
        val face = try { engine.typeface(entry.file) } catch (e: Exception) { label("رندر فونت ممکن نیست: ${e.message}"); return }
        val editor = EditText(this).apply { setText(previewText); setTextColor(if (dark) Color.WHITE else Color.BLACK); hint = "متن پیش‌نمایش"; minLines = 3 }
        root.addView(editor)
        val sample = TextView(this).apply { typeface = face; setTextColor(if (dark) Color.WHITE else Color.BLACK); setPadding(8, 20, 8, 20) }; root.addView(sample)
        fun update() {
            sample.text = previewText; sample.textSize = size
            sample.textDirection = when (direction) { 1 -> View.TEXT_DIRECTION_RTL; 2 -> View.TEXT_DIRECTION_LTR; else -> View.TEXT_DIRECTION_FIRST_STRONG }
            sample.gravity = when (alignment) { 0 -> Gravity.LEFT; 2 -> Gravity.RIGHT; else -> Gravity.CENTER_HORIZONTAL }
            sample.justificationMode = if (alignment == 3) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE
        }
        editor.addTextChangedListener(object : android.text.TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { previewText = s.toString(); update() }; override fun afterTextChanged(s: android.text.Editable?) {} })
        label("اندازه: با نوار تغییر دهید")
        root.addView(SeekBar(this).apply { max = 120; progress = size.toInt() - 12; setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { size = (value + 12).toFloat(); update() }; override fun onStartTrackingTouch(bar: SeekBar?) {}; override fun onStopTrackingTouch(bar: SeekBar?) {} }) })
        fun choices(title: String, items: List<String>, selectedValue: Int, onChange: (Int) -> Unit) {
            label(title)
            root.addView(Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
                setSelection(selectedValue)
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener { override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}; override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { onChange(position); update() } }
            })
        }
        choices("جهت متن", listOf("خودکار", "راست به چپ", "چپ به راست"), direction) { direction = it }
        choices("هم‌ترازی", listOf("چپ", "وسط", "راست", "دوطرفه"), alignment) { alignment = it }
        button("نمونه فارسی") { editor.setText("درخت دوستی بنشان که کام دل به بار آرد") }
        button("نمونه انگلیسی") { editor.setText("The quick brown fox jumps over the lazy dog.") }
        button("ذخیره پیش‌نمایش PNG شفاف (۱۰۸۰ × ۱۰۸۰)") { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = "image/png"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE, "font-preview.png") }, 3) }
        label("خروجی SVG، گلیف‌ها، محورهای متغیر و قابلیت‌های OpenType در این نسخه پیاده‌سازی نشده‌اند.")
        update()
    }
    private fun exportPng(uri: Uri) {
        val entry = selected ?: return
        val text = previewText; val textSize = size; val dir = direction; val align = alignment
        worker.execute {
            try {
                val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; this.textSize = textSize; typeface = engine.typeface(entry.file) }
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 1000)
                    .setAlignment(when (align) { 0 -> Layout.Alignment.ALIGN_LEFT; 2 -> Layout.Alignment.ALIGN_RIGHT; else -> Layout.Alignment.ALIGN_CENTER })
                    .setTextDirection(when (dir) { 1 -> TextDirectionHeuristics.RTL; 2 -> TextDirectionHeuristics.LTR; else -> TextDirectionHeuristics.FIRSTSTRONG_LTR })
                    .setJustificationMode(if (align == 3) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE).build()
                val bitmap = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap); canvas.translate(40f, ((1080 - layout.height) / 2f).coerceAtLeast(0f)); layout.draw(canvas)
                    contentResolver.openOutputStream(uri, "w")?.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } ?: error("فایل خروجی باز نشد")
                } finally { bitmap.recycle() }
                runOnUiThread { message("PNG ذخیره شد") }
            } catch (e: Exception) { runOnUiThread { message("ذخیره ممکن نشد: ${e.message}") } }
        }
    }
}
