package org.fontmanager

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FontEngineTest {
    private fun fixture(): File {
        val stream = checkNotNull(javaClass.getResourceAsStream("/fonts/Inter-variable.ttf"))
        return File.createTempFile("inter-test", ".ttf").also { file -> stream.use { input -> file.outputStream().use { out -> input.copyTo(out) } } }
    }

    @Test fun readsRealVariableFontAndGsub() {
        val file = fixture()
        try {
            val info = AndroidFontEngine().inspect(file)
            assertEquals("TTF", info.format)
            assertTrue(info.names[1].orEmpty().any { it.contains("Inter") })
            val weight = info.axes.single { it.tag == "wght" }
            assertEquals(100f, weight.min, .01f)
            assertEquals(400f, weight.default, .01f)
            assertEquals(900f, weight.max, .01f)
            assertTrue(info.axes.any { it.tag == "opsz" })
            assertTrue(info.features.contains("ss01"))
            assertTrue(info.features.contains("calt"))
            assertFalse(info.features.contains("ccmp"))
        } finally { file.delete() }
    }

    @Test fun readsMetadataUsedByTheFilters() {
        val file = fixture()
        try {
            val info = AndroidFontEngine().inspect(file)
            assertEquals(400, info.weightClass)
            assertTrue(info.isVariable)
            assertFalse("Inter has no colour tables", info.colored)
            assertFalse("Inter does not cover Arabic", info.arabicScript)
            assertFalse(info.hasPersianLetters)
            assertEquals(PERSIAN_LETTERS, info.missingPersianLetters)
            assertTrue(info.hasCopyright)
            assertEquals(FontLicense.OFL, info.license)
        } finally { file.delete() }
    }

    /**
     * Minimal SFNT with a `cmap` format 4 table and an `OS/2` weight, which is enough to exercise
     * the Arabic/Persian coverage detection without shipping another binary fixture.
     */
    private fun syntheticFont(weightClass: Int, range: IntRange): File {
        val segCount = 2
        val endCodes = listOf(range.last, 0xFFFF)
        val startCodes = listOf(range.first, 0xFFFF)
        val deltas = listOf((1 - range.first) and 0xFFFF, 1)
        // Format 4: 14-byte header, then endCode, reservedPad, startCode, idDelta and idRangeOffset.
        val subtableLength = 16 + segCount * 8
        val cmap = java.nio.ByteBuffer.allocate(12 + subtableLength).order(java.nio.ByteOrder.BIG_ENDIAN).apply {
            putShort(0); putShort(1)                       // version, one subtable
            putShort(3); putShort(1); putInt(12)           // Windows Unicode BMP
            putShort(4); putShort(subtableLength.toShort())
            putShort(0); putShort((segCount * 2).toShort()); putShort(4); putShort(1); putShort(0)
            endCodes.forEach { putShort(it.toShort()) }
            putShort(0)
            startCodes.forEach { putShort(it.toShort()) }
            deltas.forEach { putShort(it.toShort()) }
            repeat(segCount) { putShort(0) }
        }.array()
        // OS/2 layout: version (offset 0), xAvgCharWidth (2), usWeightClass (4).
        val os2 = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.BIG_ENDIAN).apply {
            putShort(4); putShort(0); putShort(weightClass.toShort()); putShort(0)
        }.array()

        // The parser does not care about order, and Collator-dependent sorting would be fragile,
        // so the directory and the body are both written in this explicit order.
        val tables = listOf("OS/2" to os2, "cmap" to cmap)
        val headerSize = 12 + tables.size * 16
        var offset = headerSize
        val body = java.io.ByteArrayOutputStream()
        val directory = java.nio.ByteBuffer.allocate(tables.size * 16).order(java.nio.ByteOrder.BIG_ENDIAN)
        tables.forEach { (tag, bytes) ->
            directory.put(tag.toByteArray(Charsets.ISO_8859_1)).putInt(0).putInt(offset).putInt(bytes.size)
            body.write(bytes)
            offset += bytes.size
        }
        val header = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.BIG_ENDIAN).apply {
            putInt(0x00010000); putShort(tables.size.toShort()); putShort(0); putShort(0); putShort(0)
        }.array()
        val bytes = java.io.ByteArrayOutputStream().apply { write(header); write(directory.array()); write(body.toByteArray()) }
        return File.createTempFile("synthetic", ".ttf").also { it.writeBytes(bytes.toByteArray()) }
    }

    @Test fun detectsArabicAndPersianOnlyLetters() {
        val persian = syntheticFont(700, 0x0600..0x06FF)
        try {
            val info = AndroidFontEngine().inspect(persian)
            assertEquals(700, info.weightClass)
            assertTrue("Arabic letters must be detected from cmap", info.arabicScript)
            assertTrue("Persian-only letters must all be found", info.hasPersianLetters)
            assertTrue(info.missingPersianLetters.isEmpty())
            assertFalse(info.isVariable)
        } finally { persian.delete() }

        // An Arabic-only font covers ا/ب but none of پ چ ژ گ ک ی.
        val arabicOnly = syntheticFont(400, 0x0620..0x064F)
        try {
            val info = AndroidFontEngine().inspect(arabicOnly)
            assertTrue(info.arabicScript)
            assertFalse(info.hasPersianLetters)
            assertTrue(info.missingPersianLetters.isNotEmpty())
        } finally { arabicOnly.delete() }
    }

    @Test fun weightFilterMatchesVariableRangeAndStaticClass() {
        val variable = FontInspection("TTF", emptySet(), emptyList(), axes = listOf(VariableAxis("wght", "Weight", 100f, 400f, 900f)), weightClass = 400)
        assertTrue(FontFilters.matchesWeight(variable, setOf(700)))
        assertFalse(FontFilters.matchesWeight(variable, setOf(950)))
        val static = FontInspection("TTF", emptySet(), emptyList(), weightClass = 700)
        assertTrue(FontFilters.matchesWeight(static, setOf(700)))
        assertFalse(FontFilters.matchesWeight(static, setOf(400)))

        // Unrelated weights round to the nearest bucket instead of being dropped.
        assertEquals(700, weightBucket(730))
        assertEquals(400, weightBucket(380))
    }

    @Test fun filtersCombineAsAConjunction() {
        val info = FontInspection("OTF", emptySet(), emptyList(), axes = listOf(VariableAxis("wght", "Weight", 100f, 400f, 900f)), weightClass = 400, colored = false, arabicScript = true, persianLetters = PERSIAN_LETTERS, license = FontLicense.OFL, hasCopyright = true)
        assertTrue(FontFilters().matches(info))
        assertTrue(FontFilters(formats = setOf("OTF"), variableOnly = true, persianLetters = TriState.Yes, licenses = setOf(FontLicense.OFL)).matches(info))
        assertTrue(FontFilters(weights = setOf(700)).matches(info))
        assertFalse(FontFilters(colored = true).matches(info))
        assertFalse(FontFilters(staticOnly = true).matches(info))
        assertFalse(FontFilters(persianLetters = TriState.No).matches(info))
        assertFalse(FontFilters(copyright = TriState.No).matches(info))
        assertFalse(FontFilters(arabicScript = TriState.No).matches(info))
        assertTrue(FontFilters(copyright = TriState.Yes, arabicScript = TriState.Yes).isActive)
    }

    @Test fun searchNormalisesPersianLetterVariants() {
        assertEquals(normalizeSearch("كتاب"), normalizeSearch("کتاب"))
        assertEquals(normalizeSearch("ياسمين"), normalizeSearch("یاسمین"))
        assertTrue(normalizeSearch("وزیر   فونت").contains("وزیر"))
    }

    @Test fun rejectsInvalidOrTruncatedInput() {
        val file = File.createTempFile("bad-font", ".bin")
        try {
            file.writeBytes(byteArrayOf(0, 1, 0, 0))
            assertThrows(IllegalArgumentException::class.java) { AndroidFontEngine().inspect(file) }
            file.writeText("not a font")
            assertThrows(IllegalArgumentException::class.java) { AndroidFontEngine().inspect(file) }
        } finally { file.delete() }
    }

    @Test fun unsupportedContainerIsExplicit() {
        val file = File.createTempFile("woff-test", ".bin")
        try {
            file.writeBytes(byteArrayOf(0x77, 0x4f, 0x46, 0x32))
            val result = AndroidFontEngine().inspect(file)
            assertEquals("WOFF2", result.format)
            assertTrue(result.warnings.isNotEmpty())
            assertTrue(result.axes.isEmpty())
            assertFalse(result.isVariable)
        } finally { file.delete() }
    }
}
