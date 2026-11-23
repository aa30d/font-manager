package org.fontmanager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VariableFontTest {
    private fun pixels(typeface: Typeface): IntArray {
        val bitmap = Bitmap.createBitmap(900, 150, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; color = Color.BLACK; textSize = 100f }
            canvas.drawText("Hamburgefonts", 0f, 110f, paint)
            return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        } finally { bitmap.recycle() }
    }
    @Test fun selectedVariableFontRendersAndWeightChanges() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val file = File.createTempFile("inter-variable", ".ttf", context.cacheDir)
        try {
            context.assets.open("Inter-variable.ttf").use { input -> file.outputStream().use { input.copyTo(it) } }
            val engine = AndroidFontEngine()
            val thin = engine.typeface(file, "'wght' 100")
            val bold = engine.typeface(file, "'wght' 900")
            assertFalse("The selected font must not silently be the system typeface", pixels(thin).contentEquals(pixels(Typeface.DEFAULT)))
            assertFalse("Changing wght must change the selected font's rendering", pixels(thin).contentEquals(pixels(bold)))
        } finally { file.delete() }
    }
    @Test fun badFontDoesNotSilentlyUseSystemFont() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val file = File.createTempFile("invalid", ".ttf", context.cacheDir)
        try {
            file.writeText("not a font")
            try {
                AndroidFontEngine().typeface(file)
                fail("Invalid font should fail instead of showing system font")
            } catch (_: RuntimeException) { }
        } finally { file.delete() }
    }
}
