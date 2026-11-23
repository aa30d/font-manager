package org.fontmanager

import android.app.Application
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.io.File
import java.util.ArrayDeque

private const val MAX_FONT_SIZE = 64L * 1024 * 1024
private const val SAMPLE = "کلاغ فرز و چابک ظهر هر روز با صدای ضخیم و عذاب‌آورش به جستجوی یک مثقال گنج پنهان در حیاط رژه می‌رفت\nThe quick brown fox jumps over the lazy dog."

data class FontEntry(val uri: Uri, val name: String, val source: String, val info: FontInspection, val privateFile: File? = null, val location: String = "")
data class FontSource(val uri: Uri, val deep: Boolean, val status: String = "آمادهٔ بررسی")

class FontLibrary(app: Application) : AndroidViewModel(app) {
    private val resolver = app.contentResolver
    private val prefs = app.getSharedPreferences("settings", 0)
    private val engine: FontEngine = AndroidFontEngine()
    val fonts = mutableStateListOf<FontEntry>()
    val sources = mutableStateListOf<FontSource>()
    var query by mutableStateOf("")
    var status by mutableStateOf("برای بررسی پوشه‌ها، تازه‌سازی را بزنید.")
    var scanning by mutableStateOf(false); private set
    var selected by mutableStateOf<FontEntry?>(null); private set
    var face by mutableStateOf<Typeface?>(null); private set
    var opening by mutableStateOf(false); private set
    var text by mutableStateOf(SAMPLE)
    var size by mutableFloatStateOf(48f)
    var lineSpacing by mutableFloatStateOf(1f)
    val axisValues = mutableStateMapOf<String, Float>()
    val featureValues = mutableStateMapOf<String, Boolean>()
    var alignment by mutableIntStateOf(1)
    private var scanJob: Job? = null
    private var openJob: Job? = null
    private var variationJob: Job? = null
    private var openedFile: File? = null
    private var temporaryOpenedFile: File? = null
    private var faceGeneration = 0
    private var generation = 0

    init {
        prefs.getStringSet("sources", emptySet())?.forEach {
            val parts = it.split("|", limit = 2)
            if (parts.size == 2) sources.add(FontSource(Uri.parse(parts[1]), parts[0] == "1"))
        }
    }
    private fun saveSources() { prefs.edit().putStringSet("sources", sources.map { "${if (it.deep) 1 else 0}|${it.uri}" }.toSet()).apply() }
    fun addSource(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (sources.none { it.uri == uri }) sources.add(FontSource(uri, false))
            saveSources(); status = "پوشه افزوده شد؛ برای بررسی، تازه‌سازی را بزنید."
        } catch (e: Exception) { status = "دسترسی پایدار به پوشه ممکن نشد: ${e.localizedMessage}" }
    }
    fun setDeep(uri: Uri, deep: Boolean) {
        val index = sources.indexOfFirst { it.uri == uri }; if (index < 0) return
        stopScan(); sources[index] = sources[index].copy(deep = deep, status = "تنظیم تغییر کرد؛ دوباره بررسی کنید")
        saveSources()
    }
    fun removeSource(uri: Uri) {
        stopScan(); sources.removeAll { it.uri == uri }; fonts.removeAll { it.source == "پوشه: $uri" }
        saveSources()
        try { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
    }
    private fun setSourceStatus(uri: Uri, value: String) {
        val i = sources.indexOfFirst { it.uri == uri }; if (i >= 0) sources[i] = sources[i].copy(status = value)
    }
    private fun stopScan() { generation++; scanJob?.cancel(); scanJob = null; scanning = false }
    fun cancelScan() { stopScan(); status = "بررسی متوقف شد؛ نتایج موجود حفظ شدند." }

    /** Copy only for inspection/opening; directory scans delete temporary copies immediately. */
    private suspend fun copy(uri: Uri, directory: File): File {
        directory.mkdirs()
        val file = File.createTempFile("font-", ".bin", directory)
        try {
            resolver.openInputStream(uri)?.use { input -> file.outputStream().use { out ->
                val buffer = ByteArray(32768); var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    total += n; require(total <= MAX_FONT_SIZE) { "فایل از ۶۴ مگابایت بزرگ‌تر است" }
                    out.write(buffer, 0, n)
                }
            } } ?: error("فایل خواندنی نیست")
            currentCoroutineContext().ensureActive()
            return file
        } catch (t: Throwable) { file.delete(); throw t }
    }
    // The external-storage document provider explicitly encodes volume and relative document ID.
    // Other providers are opaque: do not pretend that their document IDs are filesystem paths.
    private fun displayLocation(uri: Uri): String {
        if (uri.scheme == "content" && uri.authority == "com.android.externalstorage.documents") {
            val id = try { if (DocumentsContract.isDocumentUri(getApplication(), uri)) DocumentsContract.getDocumentId(uri) else DocumentsContract.getTreeDocumentId(uri) } catch (_: Exception) { null }
            if (id != null && id.startsWith("primary:")) {
                val relative = id.substringAfter(':')
                if (relative.split('/').none { it == ".." || it == "." }) {
                    @Suppress("DEPRECATION")
                    return Environment.getExternalStorageDirectory().absolutePath + if (relative.isEmpty()) "" else "/$relative"
                }
            }
            if (id != null) return "شناسهٔ سند: $id"
        }
        return "مسیر واقعی از ارائه‌دهنده در دسترس نیست: $uri"
    }
    fun sourceLocation(uri: Uri): String = displayLocation(uri)
    private suspend fun inspect(uri: Uri, name: String, source: String, keep: Boolean): FontEntry {
        val file = copy(uri, if (keep) File(getApplication<Application>().filesDir, "imports") else getApplication<Application>().cacheDir)
        try {
            val info = engine.inspect(file)
            if (info.format == "TTF" || info.format == "OTF") engine.typeface(file)
            return FontEntry(if (keep) Uri.fromFile(file) else uri, name, source, info, if (keep) file else null, displayLocation(uri))
        } catch (t: Throwable) { file.delete(); throw t }
        finally { if (!keep) file.delete() }
    }
    private fun name(uri: Uri): String = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "فونت"
    } catch (_: Exception) { "فونت" }
    fun importFiles(uris: List<Uri>) {
        viewModelScope.launch {
            status = "در حال وارد کردن فایل‌ها…"
            var success = 0; var failed = 0; var lastError = ""
            for (uri in uris.distinct()) {
                try {
                    // Complete each copy before returning so transient grants are consumed promptly.
                    val entry = withContext(Dispatchers.IO) { inspect(uri, name(uri), "فایل واردشده", true) }
                    fonts.add(entry); success++
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failed++; lastError = e.localizedMessage ?: "خطای ناشناخته" }
            }
            status = "$success فایل وارد شد؛ $failed خطا" + if (failed > 0) " ($lastError)" else ""
        }
    }
    fun refresh() {
        stopScan(); val current = ++generation
        fonts.removeAll { it.source.startsWith("پوشه: ") }
        val snapshot = sources.toList()
        if (snapshot.isEmpty()) { status = "پوشه‌ای انتخاب نشده است."; return }
        scanning = true; status = "در حال بررسی پوشه‌ها…"
        scanJob = viewModelScope.launch {
            try {
                for (source in snapshot) {
                    ensureActive(); setSourceStatus(source.uri, "در حال بررسی…")
                    var found = 0; var errors = 0
                    try {
                        withContext(Dispatchers.IO) {
                            val pending = ArrayDeque<String>(); val visited = HashSet<String>()
                            pending.add(DocumentsContract.getTreeDocumentId(source.uri))
                            val batch = ArrayList<FontEntry>(32)
                            suspend fun flush() {
                                if (batch.isEmpty()) return
                                val ready = batch.toList(); batch.clear()
                                withContext(Dispatchers.Main) {
                                    if (generation == current) {
                                        fonts.addAll(ready); status = "در حال بررسی… ${fonts.size} فونت"
                                        setSourceStatus(source.uri, "$found فونت، $errors خطا")
                                    }
                                }
                            }
                            while (pending.isNotEmpty()) {
                                ensureActive()
                                val id = pending.removeFirst(); if (!visited.add(id)) continue
                                val children = DocumentsContract.buildChildDocumentsUriUsingTree(source.uri, id)
                                resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                                    while (c.moveToNext()) {
                                        ensureActive()
                                        val childId = c.getString(0); val fileName = c.getString(1) ?: "فونت"; val mime = c.getString(2)
                                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { if (source.deep) pending.add(childId) }
                                        else if (listOf(".ttf", ".otf", ".woff", ".woff2").any { fileName.endsWith(it, true) }) {
                                            try { batch.add(inspect(DocumentsContract.buildDocumentUriUsingTree(source.uri, childId), fileName, "پوشه: ${source.uri}", false)); found++ }
                                            catch (e: CancellationException) { throw e }
                                            catch (_: Exception) { errors++ }
                                            if (batch.size >= 32) flush()
                                        }
                                    }
                                } ?: error("پوشه خواندنی نیست")
                                flush()
                            }
                            flush()
                        }
                        setSourceStatus(source.uri, "$found فونت، $errors فایل نامعتبر یا خوانده‌نشده")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { setSourceStatus(source.uri, "دسترسی یا بررسی ممکن نیست؛ پوشه را دوباره انتخاب کنید: ${e.localizedMessage}") }
                }
                status = "بررسی پایان یافت؛ ${fonts.size} فونت در فهرست."
            } finally { if (generation == current) scanning = false }
        }
    }
    fun open(entry: FontEntry) {
        back()
        selected = entry; face = null; opening = true
        axisValues.clear(); entry.info.axes.forEach { axisValues[it.tag] = it.default }
        featureValues.clear(); entry.info.features.forEach { featureValues[it] = it in setOf("liga", "clig", "calt") }
        val generation = ++faceGeneration
        openJob = viewModelScope.launch {
            var temporary: File? = null
            try {
                if (entry.info.format in listOf("WOFF", "WOFF2")) return@launch
                val file = entry.privateFile ?: withContext(Dispatchers.IO) {
                    copy(entry.uri, getApplication<Application>().cacheDir).also { temporary = it }
                }
                val settings = variationSettings()
                val initial = withContext(Dispatchers.IO) { engine.typeface(file, settings) }
                if (generation == faceGeneration) {
                    openedFile = file; temporaryOpenedFile = temporary; temporary = null; face = initial
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == faceGeneration) status = "باز کردن فونت ممکن نیست: ${e.localizedMessage}" }
            finally {
                temporary?.delete()
                if (generation == faceGeneration) opening = false
            }
        }
    }
    fun setAxis(tag: String, value: Float) {
        axisValues[tag] = value
        val file = openedFile ?: return
        val generation = ++faceGeneration
        val settings = variationSettings()
        variationJob?.cancel()
        variationJob = viewModelScope.launch {
            try {
                // Avoid rebuilding a typeface for every intermediate slider event.
                delay(40)
                val updated = withContext(Dispatchers.IO) { engine.typeface(file, settings) }
                if (generation == faceGeneration) face = updated
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == faceGeneration) status = "اعمال محور ممکن نیست: ${e.localizedMessage}" }
        }
    }
    fun back() {
        faceGeneration++; openJob?.cancel(); variationJob?.cancel()
        selected = null; face = null; opening = false; openedFile = null
        temporaryOpenedFile?.delete(); temporaryOpenedFile = null
    }
    fun variationSettings(): String = axisValues.entries.joinToString(", ") { "'${it.key}' ${it.value}" }
    fun featureSettings(): String = featureValues.entries.joinToString(", ") { "'${it.key}' ${if (it.value) 1 else 0}" }
    override fun onCleared() { scanJob?.cancel(); back(); super.onCleared() }
}
