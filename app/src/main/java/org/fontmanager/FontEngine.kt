package org.fontmanager

import android.graphics.Typeface
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

interface FontEngine {
    fun inspect(file: File): FontInspection
    fun typeface(file: File, variations: String = ""): Typeface
}

data class VariableAxis(val tag: String, val name: String, val min: Float, val default: Float, val max: Float)

/** Broad licence families recognised from the `name` table; never inferred from the file name. */
enum class FontLicense { OFL, Apache, GPL, MIT, CreativeCommons, Proprietary, Other, Unknown }

/** Letters that exist in Persian but not in Arabic; their absence is the usual sign of an Arabic-only font. */
val PERSIAN_LETTERS: List<Char> = listOf('\u067E', '\u0686', '\u0698', '\u06AF', '\u06A9', '\u06CC')

data class FontInspection(
    val format: String,
    val tables: Set<String>,
    val warnings: List<String>,
    val names: Map<Int, List<String>> = emptyMap(),
    val axes: List<VariableAxis> = emptyList(),
    val features: List<String> = emptyList(),
    val weightClass: Int = 400,
    val colored: Boolean = false,
    val arabicScript: Boolean = false,
    val persianLetters: List<Char> = emptyList(),
    val license: FontLicense = FontLicense.Unknown,
    val hasCopyright: Boolean = false
) {
    val isVariable: Boolean get() = axes.isNotEmpty()

    /** True when the font covers the Persian-only letters that Arabic fonts usually lack. */
    val hasPersianLetters: Boolean get() = persianLetters.size >= PERSIAN_LETTERS.size - 2

    val missingPersianLetters: List<Char> get() = PERSIAN_LETTERS.filter { it !in persianLetters }
}

private val ONLY_OPTIONAL_FEATURES =
    setOf("liga", "clig", "dlig", "hlig", "calt")

private fun classifyLicense(names: Map<Int, List<String>>): FontLicense {
    val text = ((names[13] ?: emptyList()) + (names[14] ?: emptyList()) + (names[0] ?: emptyList()))
        .joinToString(" ").lowercase(Locale.ROOT)
    val tokens = text.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
    return when {
        "openfontlicense" in tokens || "open font license" in text -> FontLicense.OFL
        "apache" in tokens || "apache" in text -> FontLicense.Apache
        "gpl" in tokens || "gnu general public" in text -> FontLicense.GPL
        "mit" in tokens && "license" in tokens -> FontLicense.MIT
        "creative commons" in text || ("cc" in tokens && "by" in tokens) -> FontLicense.CreativeCommons
        "proprietary" in text || "all rights reserved" in text || "commercial use" in text -> FontLicense.Proprietary
        text.isBlank() -> FontLicense.Unknown
        else -> FontLicense.Other
    }
}

/** Bounded SFNT reader. Optional malformed tables are reported, never fabricated. */
class AndroidFontEngine : FontEngine {
    private data class Table(val offset: Long, val length: Long)

    private class TableReader(private val file: RandomAccessFile, private val table: Table) {
        fun seek(offset: Long, count: Long) {
            require(offset >= 0 && count >= 0 && offset <= table.length && count <= table.length - offset) { "دادهٔ جدول ناقص است" }
            file.seek(table.offset + offset)
        }
        fun u16(offset: Long): Int { seek(offset, 2); return file.readUnsignedShort() }
        fun i16(offset: Long): Int { seek(offset, 2); return file.readShort().toInt() }
        fun fixed(offset: Long): Float { seek(offset, 4); return file.readInt() / 65536f }
        fun bytes(offset: Long, length: Int): ByteArray { seek(offset, length.toLong()); return ByteArray(length).also { file.readFully(it) } }
    }

    /** Character lookup for the two `cmap` formats that carry real Unicode coverage. */
    private class Coverage(private val readU16At: (Long) -> Int, private val format: Int, private val base: Long) {
        fun contains(codePoint: Int): Boolean = try {
            when (format) {
                4 -> containsFormat4(codePoint)
                12 -> containsFormat12(codePoint)
                else -> false
            }
        } catch (_: Exception) { false }

        private fun containsFormat4(cp: Int): Boolean {
            if (cp > 0xFFFF) return false
            val segCount = readU16At(base + 6) / 2
            if (segCount <= 0) return false
            val endBase = base + 14
            val startBase = endBase + segCount * 2L + 2
            val deltaBase = startBase + segCount * 2L
            val rangeBase = deltaBase + segCount * 2L
            var lo = 0
            var hi = segCount - 1
            var seg = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val end = readU16At(endBase + mid * 2L)
                if (end < cp) lo = mid + 1 else { seg = mid; hi = mid - 1 }
            }
            if (seg < 0) return false
            val start = readU16At(startBase + seg * 2L)
            if (start > cp) return false
            val delta = readU16At(deltaBase + seg * 2L)
            val rangeOffset = readU16At(rangeBase + seg * 2L)
            if (rangeOffset == 0) return ((cp + delta) and 0xFFFF) != 0
            val glyphAddress = rangeBase + seg * 2L + rangeOffset + 2L * (cp - start)
            val glyph = readU16At(glyphAddress)
            return glyph != 0 && ((glyph + delta) and 0xFFFF) != 0
        }

        private fun containsFormat12(cp: Int): Boolean {
            val groups = readU16At(base + 12)
            var lo = 0
            var hi = groups - 1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val record = base + 16 + mid * 12L
                val start = readU16At(record) * 65536 + readU16At(record + 2)
                val end = readU16At(record + 4) * 65536 + readU16At(record + 6)
                if (cp < start) hi = mid - 1 else if (cp > end) lo = mid + 1 else return true
            }
            return false
        }
    }

    override fun inspect(file: File): FontInspection {
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 4) { "فایل ناقص است" }
            val format = when (input.readInt()) {
                0x00010000, 0x74727565 -> "TTF"
                0x4f54544f -> "OTF"
                0x774f4646 -> "WOFF"
                0x774f4632 -> "WOFF2"
                else -> throw IllegalArgumentException("امضای فایل فونت معتبر نیست")
            }
            if (format == "WOFF" || format == "WOFF2") return FontInspection(format, emptySet(), listOf("این نسخه تبدیل و رندر $format را پشتیبانی نمی‌کند."))
            require(input.length() >= 12) { "سرآیند فونت ناقص است" }
            val count = input.readUnsignedShort()
            require(count in 1..4096 && 12L + count * 16L <= input.length()) { "فهرست جدول‌های فونت نامعتبر است" }
            input.skipBytes(6)
            val tables = mutableMapOf<String, Table>()
            repeat(count) {
                val tag = ByteArray(4); input.readFully(tag); input.readInt()
                val offset = input.readInt().toLong() and 0xffffffffL
                val length = input.readInt().toLong() and 0xffffffffL
                require(offset <= input.length() && length <= input.length() - offset) { "جدول فونت خارج از فایل است" }
                tables[String(tag, Charsets.ISO_8859_1)] = Table(offset, length)
            }
            val warnings = mutableListOf<String>()
            fun <T> readOptional(tag: String, empty: T, read: (TableReader) -> T): T {
                val table = tables[tag] ?: return empty
                return try { read(TableReader(input, table)) } catch (_: Exception) { warnings.add("جدول $tag قابل‌خواندن نیست."); empty }
            }
            val names = readOptional("name", emptyMap<Int, List<String>>()) { r ->
                val nameCount = r.u16(2); val storage = r.u16(4).toLong()
                require(nameCount <= 8192); r.seek(6, nameCount * 12L)
                val result = linkedMapOf<Int, MutableList<String>>()
                repeat(nameCount) { i ->
                    val base = 6L + i * 12L
                    val platform = r.u16(base); val id = r.u16(base + 6)
                    val length = r.u16(base + 8); val offset = r.u16(base + 10)
                    if (length > 0 && length <= 4096 && (platform == 0 || platform == 3 || platform == 1)) {
                        val bytes = r.bytes(storage + offset, length)
                        val value = if (platform == 0 || platform == 3) String(bytes, Charsets.UTF_16BE) else try { String(bytes, charset("x-MacRoman")) } catch (_: Exception) { "" }
                        val clean = value.trim().replace("\u0000", "")
                        if (clean.isNotEmpty()) result.getOrPut(id) { mutableListOf() }.let { if (clean !in it) it.add(clean) }
                    }
                }
                result.mapValues { it.value.toList() }
            }
            val axes = readOptional("fvar", emptyList<VariableAxis>()) { r ->
                require(r.u16(0) == 1)
                val offset = r.u16(4).toLong(); val axisCount = r.u16(8); val axisSize = r.u16(10)
                require(axisCount <= 256 && axisSize >= 20); r.seek(offset, axisCount.toLong() * axisSize)
                (0 until axisCount).map { i ->
                    val base = offset + i.toLong() * axisSize
                    val tag = String(r.bytes(base, 4), Charsets.US_ASCII)
                    require(tag.all { it.code in 33..126 && it != '\'' && it != '"' && it != ',' })
                    val min = r.fixed(base + 4); val default = r.fixed(base + 8); val max = r.fixed(base + 12)
                    require(min <= default && default <= max)
                    val nameId = r.u16(base + 18)
                    VariableAxis(tag, names[nameId]?.firstOrNull() ?: tag, min, default, max)
                }
            }
            // Only expose optional features that can be safely toggled. Required shaping features stay under the renderer's control.
            val features = readOptional("GSUB", emptyList<String>()) { r ->
                require(r.u16(0) == 1)
                val offset = r.u16(6).toLong(); val featureCount = r.u16(offset)
                require(featureCount <= 4096); r.seek(offset + 2, featureCount * 6L)
                (0 until featureCount).map { String(r.bytes(offset + 2 + it * 6L, 4), Charsets.US_ASCII) }
                    .filter { it in ONLY_OPTIONAL_FEATURES || (it.startsWith("ss") && (it.substring(2).toIntOrNull()?.let { number -> number in 1..20 } == true)) }
                    .distinct().sorted()
            }
            val weightClass = readOptional("OS/2", 0) { r -> r.u16(4) }.takeIf { it in 1..1000 } ?: 400
            val coverage = readOptional<Coverage?>("cmap", null) { r ->
                val records = r.u16(2)
                require(records in 1..256)
                var best: Pair<Int, Long>? = null
                for (i in 0 until records) {
                    val base = 4L + i * 8L
                    val platform = r.u16(base); val encoding = r.u16(base + 2)
                    val offset = (r.u16(base + 4).toLong() shl 16) or r.u16(base + 6).toLong()
                    val unicode = platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10))
                    if (!unicode) continue
                    val format = r.u16(offset)
                    if (format == 12 && best?.first != 12) best = 12 to offset
                    else if (format == 4 && best == null) best = 4 to offset
                }
                val chosen = best ?: return@readOptional null
                Coverage({ relative -> r.u16(relative) }, chosen.first, chosen.second)
            }
            val persianCoverage = PERSIAN_LETTERS.filter { coverage?.contains(it.code) == true }
            val arabicScript = coverage?.let { it.contains(0x0627) && it.contains(0x0628) } == true
            val colorTables = listOf("COLR", "CPAL", "SVG ", "CBDT", "CBLC", "sbix").filter { it in tables }
            if (colorTables.isNotEmpty()) warnings.add("جدول رنگی ${colorTables.joinToString()} شناسایی شد؛ پشتیبانی رندر به دستگاه و قالب فونت بستگی دارد.")
            return FontInspection(
                format = format,
                tables = tables.keys,
                warnings = warnings,
                names = names,
                axes = axes,
                features = features,
                weightClass = weightClass,
                colored = colorTables.any { it in setOf("COLR", "SVG ", "CBDT", "sbix") },
                arabicScript = arabicScript,
                persianLetters = persianCoverage,
                license = classifyLicense(names),
                hasCopyright = !names[0].isNullOrEmpty()
            )
        }
    }

    override fun typeface(file: File, variations: String): Typeface {
        val builder = Typeface.Builder(file)
        if (variations.isNotEmpty()) builder.setFontVariationSettings(variations)
        // createFromFile() can return Typeface.DEFAULT when loading fails, silently hiding errors.
        return builder.build() ?: throw IllegalArgumentException("بارگذاری فونت توسط اندروید ممکن نیست")
    }
}
