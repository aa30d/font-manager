package org.fontmanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the inspected library so a relaunch does not rescan every folder.
 *
 * Only metadata read from the font files themselves is cached. [FontLibrary] revalidates each
 * entry against the size and modification date of its file before trusting it, so a stale cache
 * can only ever be detected as stale, never presented as fresh data.
 */
class FontCache(context: Context) {
    private val file = File(context.filesDir, "library.json")
    private val temporary = File(context.filesDir, "library.json.tmp")

    data class Snapshot(val entries: List<CachedEntry>)

    /**
     * A cached row. [numericId] is the provider's numeric document id when it has one: it lets a
     * directory listing match an existing row without a per-file resolver query.
     */
    data class CachedEntry(
        val uri: String,
        val source: String,
        val location: String,
        val displayName: String,
        val size: Long,
        val lastModified: Long,
        val info: FontInspection,
        val numericId: Long = -1
    )

    fun load(): Snapshot = try {
        if (!file.exists()) Snapshot(emptyList()) else {
            Snapshot(JSONObject(file.readText()).optJSONArray("entries").mapObjects(::entryFrom))
        }
    } catch (_: Exception) {
        // A corrupted or partially written cache is dropped instead of blocking the app.
        runCatching { file.delete() }
        Snapshot(emptyList())
    }

    /** Writes through a temporary file so an interrupted save can never truncate the cache. */
    fun save(snapshot: Snapshot) {
        runCatching {
            val root = JSONObject()
            root.put("version", VERSION)
            root.put("entries", JSONArray().apply { snapshot.entries.forEach { put(entryTo(it)) } })
            val text = root.toString()
            temporary.writeText(text)
            if (!temporary.renameTo(file)) {
                file.writeText(text)
                temporary.delete()
            }
        }
    }

    fun clear() {
        runCatching { file.delete(); temporary.delete() }
    }

    private fun entryTo(entry: CachedEntry): JSONObject = JSONObject().apply {
        put("uri", entry.uri)
        put("source", entry.source)
        put("location", entry.location)
        put("displayName", entry.displayName)
        put("size", entry.size)
        put("lastModified", entry.lastModified)
        if (entry.numericId >= 0) put("numericId", entry.numericId)
        put("info", JSONObject().apply {
            put("format", entry.info.format)
            put("tables", JSONArray(entry.info.tables.toList()))
            put("warnings", JSONArray(entry.info.warnings))
            put("names", JSONObject().apply { entry.info.names.forEach { (id, values) -> put(id.toString(), JSONArray(values)) } })
            put("axes", JSONArray().apply {
                entry.info.axes.forEach { axis ->
                    put(JSONObject().put("tag", axis.tag).put("name", axis.name)
                        .put("min", axis.min.toDouble()).put("default", axis.default.toDouble()).put("max", axis.max.toDouble()))
                }
            })
            put("features", JSONArray(entry.info.features))
            put("weightClass", entry.info.weightClass)
            put("colored", entry.info.colored)
            put("arabicScript", entry.info.arabicScript)
            put("persianLetters", JSONArray(entry.info.persianLetters.map { it.code }))
            put("license", entry.info.license.name)
            put("hasCopyright", entry.info.hasCopyright)
        })
    }

    private fun entryFrom(o: JSONObject): CachedEntry {
        val info = o.getJSONObject("info")
        val names = info.optJSONObject("names")?.let { map ->
            map.keys().asSequence().associate { key ->
                key.toInt() to map.optJSONArray(key).mapPrimitives<String>()
            }
        } ?: emptyMap()
        val inspection = FontInspection(
            format = info.getString("format"),
            tables = info.optJSONArray("tables").mapPrimitives<String>().toSet(),
            warnings = info.optJSONArray("warnings").mapPrimitives(),
            names = names,
            axes = info.optJSONArray("axes").mapObjects { a ->
                VariableAxis(a.getString("tag"), a.getString("name"), a.getDouble("min").toFloat(), a.getDouble("default").toFloat(), a.getDouble("max").toFloat())
            },
            features = info.optJSONArray("features").mapPrimitives(),
            weightClass = info.optInt("weightClass", 400),
            colored = info.optBoolean("colored"),
            arabicScript = info.optBoolean("arabicScript"),
            persianLetters = info.optJSONArray("persianLetters").mapPrimitives<Int>().mapNotNull { it.toChar() },
            license = runCatching { FontLicense.valueOf(info.optString("license")) }.getOrDefault(FontLicense.Unknown),
            hasCopyright = info.optBoolean("hasCopyright")
        )
        return CachedEntry(
            uri = o.getString("uri"),
            source = o.optString("source"),
            location = o.optString("location"),
            displayName = o.optString("displayName"),
            size = o.optLong("size", -1),
            lastModified = o.optLong("lastModified", -1),
            info = inspection,
            numericId = o.optLong("numericId", -1)
        )
    }

    companion object { private const val VERSION = 1 }
}

/** Tolerates absent or partially written object arrays instead of throwing. */
internal inline fun <T> JSONArray?.mapObjects(crossinline transform: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { i -> runCatching { transform(getJSONObject(i)) }.getOrNull() }

/** Reads a JSON array of primitives; any element that is not the expected type is skipped. */
internal inline fun <reified T> JSONArray?.mapPrimitives(): List<T> {
    if (this == null) return emptyList()
    val result = ArrayList<T>(length())
    for (i in 0 until length()) {
        val value = opt(i)
        if (value is T) result.add(value)
    }
    return result
}
