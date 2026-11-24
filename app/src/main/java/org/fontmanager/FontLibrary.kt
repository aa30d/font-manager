package org.fontmanager

import android.app.Application
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val MAX_FONT_SIZE = 64L * 1024 * 1024
private const val SAMPLE = "کلاغ فرز و چابک ظهر هر روز با صدای ضخیم و عذاب‌آورش به جستجوی یک مثقال گنج پنهان در حیاط رژه می‌رفت\nThe quick brown fox jumps over the lazy dog."

data class FontEntry(
    val uri: Uri,
    val name: String,
    val source: String,
    val info: FontInspection,
    val privateFile: File? = null,
    val location: String = "",
    /** Identity of the backing document, used to reuse cached metadata without re-reading the file. */
    val cacheKey: String = uri.toString(),
    val size: Long = -1,
    val lastModified: Long = -1
)
data class FontSource(val uri: Uri, val deep: Boolean, val status: String = "آمادهٔ بررسی")

/** How the preview text is laid out. */
enum class TextAlignment(val label: String) { Left("چپ"), Center("وسط"), Right("راست"), Justify("دوطرفه") }

class FontLibrary(app: Application) : AndroidViewModel(app) {
    private val resolver = app.contentResolver
    private val prefs = app.getSharedPreferences("settings", 0)
    private val cache = FontCache(app)
    private val engine: FontEngine = AndroidFontEngine()
    val fonts = mutableStateListOf<FontEntry>()
    val sources = mutableStateListOf<FontSource>()
    var query by mutableStateOf("")
    var filters by mutableStateOf(FontFilters())
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
    var alignment by mutableStateOf(TextAlignment.Center)
    private var scanJob: Job? = null
    private var openJob: Job? = null
    private var temporaryOpenedFile: File? = null
    private var generation = 0
    private var faceGeneration = 0

    init {
        restore()
    }

    // ---------------------------------------------------------------- persistence

    /** Restores the last library (and its metadata) before any scan runs. */
    private fun restore() {
        val snapshot = cache.load()
        prefs.getStringSet("sources", emptySet())?.forEach {
            val parts = it.split("|", limit = 2)
            if (parts.size == 2) sources.add(FontSource(Uri.parse(parts[1]), parts[0] == "1"))
        }
        val restored = staleSafeEntries(snapshot.entries)
        fonts.addAll(restored)
        if (restored.isNotEmpty()) {
            status = "${restored.size} فونت از حافظهٔ ذخیره‌شده بارگذاری شد؛ برای بررسی دوباره، تازه‌سازی را بزنید."
        }
        // Drop restored entries that belong to folders the user removed in the meantime.
        val knownSources = (sources.map { it.uri.toString() } + IMPORTED_SOURCE).toSet()
        val orphans = fonts.filter { entry -> entry.source.startsWith("پوشه: ") && entry.source.removePrefix("پوشه: ") !in knownSources }
        if (orphans.isNotEmpty()) { fonts.removeAll(orphans); persist() }
    }

    /** Turns cached rows back into library entries, dropping any whose file changed or disappeared. */
    private fun staleSafeEntries(cachedEntries: List<FontCache.CachedEntry>): List<FontEntry> = cachedEntries.mapNotNull { cached ->
        try {
            val uri = Uri.parse(cached.uri)
            if (cached.source == IMPORTED_SOURCE) {
                val path = uri.path ?: return@mapNotNull null
                val file = File(path)
                if (!file.isFile) return@mapNotNull null
                FontEntry(uri, cached.displayName, cached.source, cached.info, file, cached.location, cached.uri, cached.size, cached.lastModified)
            } else {
                // Cheap validation only: the document must still exist and be unchanged.
                val stamp = documentStampOrNull(uri) ?: return@mapNotNull null
                val (size, modified) = stamp
                if (cached.lastModified > 0 && modified > 0 && cached.lastModified != modified) return@mapNotNull null
                if (cached.size > 0 && size > 0 && cached.size != size) return@mapNotNull null
                FontEntry(uri, cached.displayName, cached.source, cached.info, null, cached.location, cached.uri, size, modified)
            }
        } catch (_: Exception) { null }
    }

    /** Persists metadata so the next launch can skip inspecting every file again. */
    private fun persist() {
        val snapshot = fonts.map { entry ->
            FontCache.CachedEntry(
                uri = entry.cacheKey,
                source = entry.source,
                location = entry.location,
                displayName = entry.name,
                size = entry.size,
                lastModified = entry.lastModified,
                info = entry.info
            )
        }
        cache.save(FontCache.Snapshot(snapshot))
    }

    private fun saveSources() { prefs.edit().putStringSet("sources", sources.map { "${if (it.deep) 1 else 0}|${it.uri}" }.toSet()).apply() }

    // ---------------------------------------------------------------- sources

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
        saveSources(); persist()
        try { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
    }
    private fun setSourceStatus(uri: Uri, value: String) {
        val i = sources.indexOfFirst { it.uri == uri }; if (i >= 0) sources[i] = sources[i].copy(status = value)
    }
    private fun stopScan() { generation++; scanJob?.cancel(); scanJob = null; scanning = false }
    fun cancelScan() { stopScan(); status = "بررسی متوقف شد؛ نتایج موجود حفظ شدند." }

    // ---------------------------------------------------------------- importing

    /** Copy only for inspection/opening; directory scans delete temporary copies immediately. */
    private suspend fun copy(uri: Uri, directory: File): File = withContext(Dispatchers.IO) {
        copyOnIoThread(uri, directory)
    }

    private suspend fun copyOnIoThread(uri: Uri, directory: File): File {
        val context = currentCoroutineContext()
        directory.mkdirs()
        val file = File.createTempFile("font-", ".bin", directory)
        try {
            resolver.openInputStream(uri)?.use { input -> file.outputStream().use { out ->
                val buffer = ByteArray(32768); var total = 0L
                while (true) {
                    context.ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    total += n; require(total <= MAX_FONT_SIZE) { "فایل از ۶۴ مگابایت بزرگ‌تر است" }
                    out.write(buffer, 0, n)
                }
            } } ?: error("فایل خواندنی نیست")
            context.ensureActive()
            return file
        } catch (t: Throwable) { file.delete(); throw t }
    }

    /** Copy used by callers that already run on [Dispatchers.IO]; [copy] alone is safe anywhere. */
    private suspend fun copyBlocking(uri: Uri, directory: File): File = copy(uri, directory)

    // The external-storage document provider explicitly encodes volume and relative document ID.
    // Other providers are opaque: do not pretend that their document IDs are filesystem paths.
    private fun displayLocation(uri: Uri): String {
        if (uri.scheme == "file") return uri.path ?: uri.toString()
        if (uri.scheme == "content" && uri.authority == "com.android.externalstorage.documents") {
            val id = try { if (DocumentsContract.isDocumentUri(getApplication(), uri)) DocumentsContract.getDocumentId(uri) else DocumentsContract.getTreeDocumentId(uri) } catch (_: Exception) { null }
            if (id != null && id.startsWith("primary:")) {
                val relative = id.substringAfter(':')
                if (relative.split('/').none { it == ".." || it == "." }) {
                    @Suppress("DEPRECATION")
                    return android.os.Environment.getExternalStorageDirectory().absolutePath + if (relative.isEmpty()) "" else "/$relative"
                }
            }
            if (id != null) return "شناسهٔ سند: $id"
        }
        return "مسیر واقعی از ارائه‌دهنده در دسترس نیست: $uri"
    }
    fun sourceLocation(uri: Uri): String = displayLocation(uri)

    /**
     * Size and modification date of a document, used to decide whether cached metadata is still
     * valid. `null` means the document is gone or no longer readable.
     */
    private fun documentStampOrNull(uri: Uri): Pair<Long, Long>? = try {
        resolver.query(uri, arrayOf(Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else (if (cursor.isNull(0)) -1L else cursor.getLong(0)) to (if (cursor.isNull(1)) -1L else cursor.getLong(1))
        }
    } catch (_: Exception) { null }

    private fun documentStamp(uri: Uri): Pair<Long, Long> = documentStampOrNull(uri) ?: (-1L to -1L)

    private suspend fun inspect(
        uri: Uri,
        name: String,
        source: String,
        keep: Boolean,
        knownSize: Long = -1,
        knownModified: Long = -1
    ): FontEntry {
        val file = copy(uri, if (keep) File(getApplication<Application>().filesDir, "imports") else getApplication<Application>().cacheDir)
        try {
            val info = engine.inspect(file)
            if (info.format == "TTF" || info.format == "OTF") engine.typeface(file)
            val (size, modified) = when {
                keep -> file.length() to file.lastModified()
                knownSize > 0 || knownModified > 0 -> knownSize to knownModified
                else -> documentStamp(uri)
            }
            return FontEntry(
                uri = if (keep) Uri.fromFile(file) else uri,
                name = name,
                source = source,
                info = info,
                privateFile = if (keep) file else null,
                location = displayLocation(uri),
                cacheKey = if (keep) Uri.fromFile(file).toString() else uri.toString(),
                size = size,
                lastModified = modified
            )
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
                    val entry = withContext(Dispatchers.IO) { inspect(uri, name(uri), IMPORTED_SOURCE, true) }
                    fonts.add(entry); success++
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failed++; lastError = e.localizedMessage ?: "خطای ناشناخته" }
            }
            persist()
            status = "$success فایل وارد شد؛ $failed خطا" + if (failed > 0) " ($lastError)" else ""
        }
    }

    fun removeFont(entry: FontEntry) {
        if (selected == entry) back()
        fonts.remove(entry)
        entry.privateFile?.delete()
        persist()
    }

    // ---------------------------------------------------------------- scanning

    fun refresh() {
        stopScan(); val current = ++generation
        // Metadata already known for each document is kept before the old folder results are
        // dropped, so a rescan of an unchanged folder does not copy and parse everything again.
        val known = fonts.associateBy { it.cacheKey }
        fonts.removeAll { it.source.startsWith("پوشه: ") }
        val snapshot = sources.toList()
        if (snapshot.isEmpty()) { status = "پوشه‌ای انتخاب نشده است."; persist(); return }
        scanning = true; status = "در حال بررسی پوشه‌ها…"
        var reused = 0
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
                                resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                                    while (c.moveToNext()) {
                                        ensureActive()
                                        val childId = c.getString(0); val fileName = c.getString(1) ?: "فونت"; val mime = c.getString(2)
                                        if (mime == Document.MIME_TYPE_DIR) { if (source.deep) pending.add(childId) }
                                        else if (listOf(".ttf", ".otf", ".woff", ".woff2").any { fileName.endsWith(it, true) }) {
                                            val childUri = DocumentsContract.buildDocumentUriUsingTree(source.uri, childId)
                                            val size = if (c.isNull(3)) -1L else c.getLong(3)
                                            val modified = if (c.isNull(4)) -1L else c.getLong(4)
                                            val cached = known[childUri.toString()]
                                            val usable = cached != null && (modified <= 0 || modified == cached.lastModified) && (size <= 0 || size == cached.size)
                                            if (usable) {
                                                batch.add(cached.copy(name = fileName, source = "پوشه: ${source.uri}"))
                                                reused++; found++
                                            } else try {
                                                batch.add(inspect(childUri, fileName, "پوشه: ${source.uri}", false, size, modified)); found++
                                            } catch (e: CancellationException) { throw e }
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
                status = "بررسی پایان یافت؛ ${fonts.size} فونت در فهرست" + if (reused > 0) " ($reused مورد از حافظهٔ ذخیره‌شده)." else "."
                withContext(Dispatchers.IO) { persist() }
            } finally { if (generation == current) scanning = false }
        }
    }

    // ---------------------------------------------------------------- opening and rendering

    fun open(entry: FontEntry) {
        back()
        selected = entry; face = null; opening = true
        axisValues.clear(); entry.info.axes.forEach { axisValues[it.tag] = it.default }
        featureValues.clear(); entry.info.features.forEach { featureValues[it] = it in setOf("liga", "clig", "calt") }
        val token = ++faceGeneration
        openJob = viewModelScope.launch {
            var temporary: File? = null
            try {
                if (entry.info.format in listOf("WOFF", "WOFF2")) return@launch
                val file = entry.privateFile ?: copyBlocking(entry.uri, getApplication<Application>().cacheDir).also { temporary = it }
                // A single base typeface: axis changes are applied to the paint, not rebuilt here.
                val initial = withContext(Dispatchers.IO) { engine.typeface(file) }
                if (token == faceGeneration) {
                    temporaryOpenedFile = temporary; temporary = null; face = initial
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == faceGeneration) status = "باز کردن فونت ممکن نیست: ${e.localizedMessage}" }
            finally {
                temporary?.delete()
                if (token == faceGeneration) opening = false
            }
        }
    }

    /**
     * Applies an axis value. The preview view receives the resulting `font-variation-settings`
     * through its paint, so it repaints on the next frame: no debounce, no typeface rebuild, and
     * intermediate slider positions are visible while the finger is still down.
     */
    fun setAxis(tag: String, value: Float) { axisValues[tag] = value }

    fun resetAxis(tag: String) {
        val axis = selected?.info?.axes?.firstOrNull { it.tag == tag } ?: return
        axisValues[tag] = axis.default
    }

    fun back() {
        faceGeneration++; openJob?.cancel()
        selected = null; face = null; opening = false
        temporaryOpenedFile?.delete(); temporaryOpenedFile = null
    }

    fun variationSettings(): String = axisValues.entries.joinToString(", ") { "'${it.key}' ${it.value}" }
    fun featureSettings(): String = featureValues.entries.joinToString(", ") { "'${it.key}' ${if (it.value) 1 else 0}" }

    /** The library narrowed by the free-text query and the advanced filters. */
    fun visibleFonts(): List<FontEntry> {
        val needle = normalizeSearch(query.trim())
        val active = filters
        return fonts.filter { entry ->
            if (!active.matches(entry)) return@filter false
            if (needle.isEmpty()) return@filter true
            val haystack = buildString {
                append(entry.name); append(' '); append(entry.location); append(' '); append(entry.source); append(' ')
                entry.info.names[1]?.forEach { append(it); append(' ') }
                entry.info.names[16]?.forEach { append(it); append(' ') }
                entry.info.names[4]?.forEach { append(it); append(' ') }
            }
            normalizeSearch(haystack).contains(needle)
        }
    }

    override fun onCleared() { scanJob?.cancel(); back(); super.onCleared() }

    companion object { const val IMPORTED_SOURCE = "فایل واردشده" }
}
