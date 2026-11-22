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
        } finally { file.delete() }
    }
}
