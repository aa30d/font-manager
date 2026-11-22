package org.fontmanager

import android.graphics.Typeface
import java.io.File
import java.io.RandomAccessFile

/** All input is copied to private storage before it reaches this interface. */
interface FontEngine {
    fun inspect(file: File): FontInspection
    fun typeface(file: File): Typeface
}

data class FontInspection(val format: String, val tables: Set<String>, val warnings: List<String>)

/** Minimal bounded SFNT directory reader; does not claim that a table can be rendered. */
class AndroidFontEngine : FontEngine {
    override fun inspect(file: File): FontInspection {
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 4) { "فایل ناقص است" }
            val signature = input.readInt()
            val format = when (signature) {
                0x00010000, 0x74727565 -> "TTF"
                0x4f54544f -> "OTF"
                0x774f4646 -> "WOFF"
                0x774f4632 -> "WOFF2"
                else -> throw IllegalArgumentException("امضای فایل فونت معتبر نیست")
            }
            if (format == "WOFF" || format == "WOFF2")
                return FontInspection(format, emptySet(), listOf("این نسخه تبدیل و رندر $format را پشتیبانی نمی‌کند."))
            require(input.length() >= 12) { "سرآیند فونت ناقص است" }
            val count = input.readUnsignedShort()
            require(count in 1..4096 && 12L + count * 16L <= input.length()) { "فهرست جدول‌های فونت نامعتبر است" }
            input.skipBytes(6)
            val tables = mutableSetOf<String>()
            repeat(count) {
                val tag = ByteArray(4); input.readFully(tag)
                input.readInt()
                val offset = input.readInt().toLong() and 0xffffffffL
                val length = input.readInt().toLong() and 0xffffffffL
                require(offset <= input.length() && length <= input.length() - offset) { "جدول فونت خارج از فایل است" }
                tables.add(String(tag, Charsets.ISO_8859_1))
            }
            val warnings = mutableListOf<String>()
            if ("fvar" in tables) warnings.add("فونت متغیر شناسایی شد؛ تنظیم محورها در این نسخه پشتیبانی نمی‌شود.")
            val color = listOf("COLR", "CPAL", "SVG ", "CBDT", "CBLC", "sbix").filter { it in tables }
            if (color.isNotEmpty()) warnings.add("جدول رنگی ${color.joinToString()} شناسایی شد؛ رندر رنگی به دستگاه بستگی دارد و تأیید نشده است.")
            return FontInspection(format, tables, warnings)
        }
    }
    override fun typeface(file: File): Typeface = Typeface.createFromFile(file)
}
