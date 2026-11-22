package org.fontmanager

import android.graphics.Typeface
import java.io.File
import java.io.RandomAccessFile

interface FontEngine {
    fun inspect(file: File): FontInspection
    fun typeface(file: File): Typeface
}

data class VariableAxis(val tag: String, val name: String, val min: Float, val default: Float, val max: Float)
data class FontInspection(
    val format: String,
    val tables: Set<String>,
    val warnings: List<String>,
    val names: Map<Int, List<String>> = emptyMap(),
    val axes: List<VariableAxis> = emptyList(),
    val features: List<String> = emptyList()
)

/** Bounded SFNT reader. Optional malformed tables are reported, never fabricated. */
class AndroidFontEngine : FontEngine {
    private data class Table(val offset: Long, val length: Long)
    private class TableReader(private val file: RandomAccessFile, private val table: Table) {
        fun seek(offset: Long, count: Long) {
            require(offset >= 0 && count >= 0 && offset <= table.length && count <= table.length - offset) { "دادهٔ جدول ناقص است" }
            file.seek(table.offset + offset)
        }
        fun u16(offset: Long): Int { seek(offset, 2); return file.readUnsignedShort() }
        fun fixed(offset: Long): Float { seek(offset, 4); return file.readInt() / 65536f }
        fun bytes(offset: Long, length: Int): ByteArray { seek(offset, length.toLong()); return ByteArray(length).also { file.readFully(it) } }
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
                    .filter { it in setOf("liga", "clig", "dlig", "hlig", "calt") || (it.startsWith("ss") && (it.substring(2).toIntOrNull()?.let { number -> number in 1..20 } == true)) }.distinct().sorted()
            }
            val color = listOf("COLR", "CPAL", "SVG ", "CBDT", "CBLC", "sbix").filter { it in tables }
            if (color.isNotEmpty()) warnings.add("جدول رنگی ${color.joinToString()} شناسایی شد؛ پشتیبانی رندر به دستگاه و قالب فونت بستگی دارد.")
            return FontInspection(format, tables.keys, warnings, names, axes, features)
        }
    }
    override fun typeface(file: File): Typeface = Typeface.createFromFile(file)
}
