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
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_FONT_SIZE = 64L * 1024 * 1024

/** Sample text for fonts that do not cover Persian, so the specimen is never rendered blank. */
private const val LATIN_SAMPLE = "The quick brown fox jumps over the lazy dog"
private const val PERSIAN_SAMPLE = "کلاغ فرز و چابک ظهر هر روز با صدای ضخیم و عذاب‌آورش به جستجوی یک مثقال گنج پنهان در حیاط رژه می‌رفت\nThe quick brown fox jumps over the lazy dog."

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
    val lastModified: Long = -1,
    val numericId: Long = -1
) {
    /** Family name as the font itself declares it, falling back to the file name. */
    val familyName: String get() = info.names[16]?.firstOrNull() ?: info.names[1]?.firstOrNull() ?: name

    /**
     * Text drawn as the specimen. A font without Persian coverage would show a row of "missing
     * glyph" boxes, so Latin-only fonts get Latin sample text instead.
     */
    val specimen: String get() = if (info.arabicScript) PERSIAN_SAMPLE else LATIN_SAMPLE
}

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
    var text by mutableStateOf(PERSIAN_SAMPLE)
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
        restoreSources()
        restoreLibrary()
    }

    // ---------------------------------------------------------------- persistence

    private fun restoreSources() {
        prefs.getStringSet("sources", emptySet())?.forEach {
            val parts = it.split("|", limit = 2)
            if (parts.size == 2) sources.add(FontSource(Uri.parse(parts[1]), parts[0] == "1"))
        }
    }

    /**
     * Loads the cached library in the background.
     *
     * Nothing here may query the document provider per font: with a few thousand fonts that meant
     * thousands of resolver round-trips before the first frame, which is what made startup slow.
     * Cached rows are trusted as-is, and removed or changed files are reconciled by the next scan,
     * which already sees the size and modification date in its directory listing.
     */
    private fun restoreLibrary() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { cache.load() }
            if (snapshot.entries.isEmpty()) return@launch
            val knownSources = (sources.map { it.uri.toString() } + IMPORTED_SOURCE).toSet()
            val restored = snapshot.entries.mapNotNull { cached ->
                when {
                    cached.source == IMPORTED_SOURCE -> {
                        val path = Uri.parse(cached.uri).path ?: return@mapNotNull null
                        val file = File(path)
                        if (!file.isFile) return@mapNotNull null
                        entryOf(cached, file)
                    }
                    cached.source.removePrefix("پوشه: ") !in knownSources -> null
                    else -> entryOf(cached, null)
                }
            }
            if (restored.isEmpty()) return@launch
            // The user may have started a scan while the cache was loading.
            if (scanning || fonts.isNotEmpty()) return@launch
            fonts.addAll(restored)
            status = "${restored.size} فونت از حافظهٔ ذخیره‌شده بارگذاری شد؛ برای بررسی دوباره، تازه‌سازی را بزنید."
        }
    }

    private fun entryOf(cached: FontCache.CachedEntry, file: File?) = FontEntry(
        uri = Uri.parse(cached.uri),
        name = cached.displayName,
        source = cached.source,
        info = cached.info,
        privateFile = file,
        location = cached.location,
        cacheKey = cached.uri,
        size = cached.size,
        lastModified = cached.lastModified,
        numericId = cached.numericId
    )

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
                info = entry.info,
                numericId = entry.numericId
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
    private suspend fun copy(uri: Uri, directory: File): File = withContext(Dispatchers.IO) { copyOnIoThread(uri, directory) }

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

    private fun numericIdOf(uri: Uri): Long = try {
        val id = DocumentsContract.getDocumentId(uri)
        id.substringAfterLast(':').substringAfterLast('/').toLongOrNull()
            ?: id.toLongOrNull()
            ?: -1L
    } catch (_: Exception) { -1L }

    private suspend fun inspect(
        uri: Uri,
        name: String,
        source: String,
        keep: Boolean,
        knownSize: Long = -1,
        knownModified: Long = -1,
        numericId: Long = -1
    ): FontEntry {
        val file = copy(uri, if (keep) File(getApplication<Application>().filesDir, "imports") else getApplication<Application>().cacheDir)
        try {
            val info = engine.inspect(file)
            if (info.format == "TTF" || info.format == "OTF") engine.typeface(file)
            val size = if (keep) file.length() else knownSize
            val modified = if (keep) file.lastModified() else knownModified
            val entryUri = if (keep) Uri.fromFile(file) else uri
            return FontEntry(
                uri = entryUri,
                name = name,
                source = source,
                info = info,
                privateFile = if (keep) file else null,
                location = displayLocation(uri),
                cacheKey = entryUri.toString(),
                size = size,
                lastModified = modified,
                numericId = if (keep) -1 else numericId
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
                    // Copies complete on the IO dispatcher; the loop stays cancellable.
                    val entry = inspect(uri, name(uri), IMPORTED_SOURCE, true)
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
        val knownNumeric = known.values.filter { it.numericId >= 0 }.associateBy { it.numericId }
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
                                            val numeric = childId.substringAfterLast(':').substringAfterLast('/').toLongOrNull() ?: -1L
                                            val cached = known[childUri.toString()] ?: knownNumeric[numeric]
                                            val usable = cached != null &&
                                                (modified <= 0 || modified == cached.lastModified) &&
                                                (size <= 0 || size == cached.size)
                                            if (usable) {
                                                batch.add(
                                                    cached.copy(
                                                        uri = childUri,
                                                        name = fileName,
                                                        source = "پوشه: ${source.uri}",
                                                        location = displayLocation(childUri),
                                                        cacheKey = childUri.toString(),
                                                        size = size,
                                                        lastModified = modified,
                                                        numericId = numeric
                                                    )
                                                )
                                                reused++; found++
                                            } else try {
                                                batch.add(inspect(childUri, fileName, "پوشه: ${source.uri}", false, size, modified, numeric)); found++
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
        // Fresh sample text for a font that does not cover Persian, so nothing renders as boxes.
        text = entry.specimen
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

    fun variationSettings(): String = axisValues.entries.joinToString(", ") { "'${it.key}' ${formatAxisValue(it.value)}" }
    fun featureSettings(): String = featureValues.entries.joinToString(", ") { "'${it.key}' ${if (it.value) 1 else 0}" }

    /** Whole numbers stay whole in `font-variation-settings`; finer axes keep decimals. */
    private fun formatAxisValue(value: Float): String = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')

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
