package org.fontmanager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Rendering-level checks for the two behaviours that were reported as broken:
 * two-sided (justified) alignment and axis values that must follow the slider live.
 */
@RunWith(AndroidJUnit4::class)
class PreviewRenderingTest {

    private fun interFile(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("inter-preview", ".ttf", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("Inter-variable.ttf").use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun pixels(paint: TextPaint, text: String, width: Int = 900, height: Int = 220): IntArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawText(text, 10f, 140f, paint)
            return IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
        } finally { bitmap.recycle() }
    }

    @Test fun paintLevelVariationSettingsChangeRenderingImmediately() {
        val file = interFile()
        try {
            // One base typeface, as the preview now keeps; the slider only rewrites the paint.
            val base = AndroidFontEngine().typeface(file)
            val thin = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { typeface = base; textSize = 100f }
            val black = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { typeface = base; textSize = 100f }
            assertTrue(thin.setFontVariationSettings("'wght' 100"))
            assertTrue(black.setFontVariationSettings("'wght' 900"))
            assertFalse(
                "Changing wght on the paint must change the drawn glyphs without rebuilding the typeface",
                pixels(thin, "Hamburgefonts").contentEquals(pixels(black, "Hamburgefonts"))
            )
        } finally { file.delete() }
    }

    /** Ink column range of one horizontal band of the drawn view. */
    private fun inkRange(view: View, top: Int, bottom: Int): Pair<Int, Int>? {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            var min = Int.MAX_VALUE
            var max = -1
            val row = IntArray(view.width)
            for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(view.height)) {
                bitmap.getPixels(row, 0, view.width, 0, y, view.width, 1)
                for (x in row.indices) {
                    if (Color.alpha(row[x]) > 0 && Color.red(row[x]) < 200) {
                        if (x < min) min = x
                        if (x > max) max = x
                    }
                }
            }
            return if (max < 0) null else min to max
        } finally { bitmap.recycle() }
    }

    private fun mountedView(file: File, alignment: TextAlignment, text: String, widthPx: Int, textSizePx: Float): PreviewEditText {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = PreviewEditText(context)
        view.update(
            PreviewEditText.State(
                text = text,
                typeface = AndroidFontEngine().typeface(file),
                textSizePx = textSizePx,
                lineMultiplier = 1.2f,
                textColor = Color.BLACK,
                alignment = alignment
            )
        )
        view.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    /**
     * The sample is deliberately narrow: a few words per line make ragged and justified lines
     * clearly distinguishable when the drawn ink is measured.
     */
    private val paragraph = "کلاغ فرز و چابک ظهر هر روز با صدای ضخیم در حیاط رژه می‌رفت و گنج پنهان را جستجو کرد. "

    @Test fun justifyStretchesEveryLineExceptTheLastOne() {
        val file = interFile()
        try {
            val view = mountedView(file, TextAlignment.Justify, paragraph.repeat(2), 420, 30f)
            assertTrue("The view must lay out more than two lines for this test", view.measuredHeight > 150)

            // Line bands come from the same layout the view draws (a justified StaticLayout).
            val drawn = view.drawnLayout()
            assertTrue("A justify view must expose a StaticLayout", drawn is StaticLayout)
            // getJustificationMode() only exists from API 35 on, so it is not asserted here; the
            // measured line widths below are the real evidence that justification was applied.
            val reference = drawn as StaticLayout
            assertTrue("The text must wrap onto several lines", reference.lineCount >= 3)

            val left = view.compoundPaddingLeft
            val right = view.width - view.compoundPaddingRight
            val available = right - left
            var justifiedLines = 0
            for (line in 0 until reference.lineCount - 1) {
                val top = view.compoundPaddingTop + reference.getLineTop(line)
                val bottom = view.compoundPaddingTop + reference.getLineBottom(line)
                val range = inkRange(view, top, bottom) ?: continue
                val span = range.second - range.first
                assertTrue(
                    "Line $line is only ${span}px wide out of $available and is not justified",
                    span >= (available * 0.85f).toInt()
                )
                justifiedLines++
            }
            assertTrue("At least two lines must have been measured", justifiedLines >= 2)
        } finally { file.delete() }
    }

    @Test fun leftAlignmentStaysRaggedSoTheJustifyCheckIsMeaningful() {
        val file = interFile()
        try {
            val view = mountedView(file, TextAlignment.Left, paragraph.repeat(2), 420, 30f)
            val reference = view.drawnLayout()
            val available = 420 - view.compoundPaddingLeft - view.compoundPaddingRight
            val widths = (0 until reference.lineCount - 1).mapNotNull { line ->
                inkRange(view, view.compoundPaddingTop + reference.getLineTop(line), view.compoundPaddingTop + reference.getLineBottom(line))
                    ?.let { it.second - it.first }
            }
            assertTrue("Some lines must be shorter than the full width when not justified", widths.any { it < available * 0.85f })
        } finally { file.delete() }
    }

    @Test fun updateAppliesTextSizeAndKeepsTheEditedText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = PreviewEditText(context)
        view.update(PreviewEditText.State(text = "سلام", textSizePx = 40f, alignment = TextAlignment.Center))
        assertEquals("سلام", view.text.toString())
        view.update(PreviewEditText.State(text = "سلام دنیا", textSizePx = 80f, alignment = TextAlignment.Justify))
        assertEquals("سلام دنیا", view.text.toString())
        assertEquals(80f, view.textSize, 0.01f)
    }
}
