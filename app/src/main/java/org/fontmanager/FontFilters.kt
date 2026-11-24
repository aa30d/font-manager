package org.fontmanager

import java.util.Locale

/** Advanced library filters. Every field is an "any" by default. */
data class FontFilters(
    val colored: Boolean = false,
    val formats: Set<String> = emptySet(),
    val variableOnly: Boolean = false,
    val staticOnly: Boolean = false,
    val weights: Set<Int> = emptySet(),
    val copyright: TriState = TriState.Any,
    val arabicScript: TriState = TriState.Any,
    val persianLetters: TriState = TriState.Any,
    val licenses: Set<FontLicense> = emptySet()
) {
    val isActive: Boolean
        get() = colored || formats.isNotEmpty() || variableOnly || staticOnly || weights.isNotEmpty() ||
            copyright != TriState.Any || arabicScript != TriState.Any || persianLetters != TriState.Any || licenses.isNotEmpty()

    /** Number of dimensions the user narrowed, shown on the "فیلترها" button. */
    val activeCount: Int
        get() = listOf(
            colored,
            formats.isNotEmpty(),
            variableOnly || staticOnly,
            weights.isNotEmpty(),
            copyright != TriState.Any,
            arabicScript != TriState.Any,
            persianLetters != TriState.Any,
            licenses.isNotEmpty()
        ).count { it }

    fun matches(entry: FontEntry): Boolean = matches(entry.info)

    fun matches(info: FontInspection): Boolean {
        if (colored && !info.colored) return false
        if (formats.isNotEmpty() && info.format !in formats) return false
        if (variableOnly && !info.isVariable) return false
        if (staticOnly && info.isVariable) return false
        if (weights.isNotEmpty() && !matchesWeight(info, weights)) return false
        if (copyright == TriState.Yes && !info.hasCopyright) return false
        if (copyright == TriState.No && info.hasCopyright) return false
        if (arabicScript == TriState.Yes && !info.arabicScript) return false
        if (arabicScript == TriState.No && info.arabicScript) return false
        if (persianLetters == TriState.Yes && !info.hasPersianLetters) return false
        if (persianLetters == TriState.No && info.hasPersianLetters) return false
        if (licenses.isNotEmpty() && info.license !in licenses) return false
        return true
    }

    companion object {
        /**
         * A variable font spans the weights between its `wght` axis limits, so it matches every
         * weight bucket its axis covers rather than only its default instance.
         */
        fun matchesWeight(info: FontInspection, weights: Set<Int>): Boolean {
            val axis = info.axes.firstOrNull { it.tag == "wght" }
            if (axis != null) return weights.any { it.toFloat() in axis.min..axis.max }
            return weightBucket(info.weightClass) in weights
        }
    }
}

enum class TriState { Any, Yes, No }

/** Weights as a user thinks of them, not the raw OS/2 numbers. */
val WEIGHT_BUCKETS: List<WeightBucket> = listOf(
    WeightBucket(300, "نازک"),
    WeightBucket(400, "معمولی"),
    WeightBucket(500, "متوسط"),
    WeightBucket(600, "نیمه‌ضخیم"),
    WeightBucket(700, "ضخیم"),
    WeightBucket(800, "خیلی ضخیم"),
    WeightBucket(900, "سیاه")
)

data class WeightBucket(val value: Int, val label: String)

/** Maps the 1..1000 OS/2 weight class onto the nearest bucket a user can pick. */
fun weightBucket(weightClass: Int): Int {
    val clamped = weightClass.coerceIn(100, 1000)
    return WEIGHT_BUCKETS.minByOrNull { kotlin.math.abs(it.value - clamped) }?.value ?: 400
}

val FORMAT_CHOICES = listOf("TTF", "OTF", "WOFF", "WOFF2")

val LICENSE_LABELS: Map<FontLicense, String> = mapOf(
    FontLicense.OFL to "OFL",
    FontLicense.Apache to "Apache",
    FontLicense.GPL to "GPL",
    FontLicense.MIT to "MIT",
    FontLicense.CreativeCommons to "CC",
    FontLicense.Proprietary to "اختصاصی",
    FontLicense.Other to "دیگر",
    FontLicense.Unknown to "نامشخص"
)

/** Filter order: recognised free licences first, then the catch-all buckets. */
val LICENSE_ORDER: List<FontLicense> = listOf(
    FontLicense.OFL, FontLicense.Apache, FontLicense.GPL, FontLicense.MIT,
    FontLicense.CreativeCommons, FontLicense.Proprietary, FontLicense.Other, FontLicense.Unknown
)

fun licenseLabel(license: FontLicense): String = LICENSE_LABELS[license] ?: license.name

/** Case- and diacritic-insensitive search so Persian text matches regardless of ی/ي or ک/ك. */
fun normalizeSearch(value: String): String {
    val builder = StringBuilder(value.length)
    for (ch in value.lowercase(Locale.ROOT)) {
        val mapped = when (ch) {
            '\u064A', '\u0649', '\u06CC' -> '\u06CC' // ي, ى, ی
            '\u0643' -> '\u06A9' // ك → ک
            '\u0629' -> '\u0647'
            '\u0640' -> null // tatweel
            else -> ch
        }
        if (mapped != null && !mapped.isWhitespace()) builder.append(mapped)
    }
    return builder.toString()
}
