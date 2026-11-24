package org.fontmanager

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText

/**
 * Preview text area used instead of a plain `EditText`.
 *
 * `EditText` always builds a `DynamicLayout`, and `TextView.setJustificationMode()` has no effect
 * there: AOSP only justifies text that is not a `Spannable`, which an editable buffer always is.
 * That is why "دوطرفه" silently fell back to left alignment. This view keeps normal editing, but
 * when justify is selected it draws a `StaticLayout` with `JUSTIFICATION_MODE_INTER_WORD`, so both
 * Persian and Latin lines are stretched to the full width.
 *
 * Every setting is pushed through [update], which only reconfigures the existing text paint, so
 * dragging an axis slider repaints with the new variation settings on the next frame.
 */
class PreviewEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : EditText(context, attrs, defStyleAttr) {

    data class State(
        val text: String = "",
        val typeface: android.graphics.Typeface? = null,
        val textSizePx: Float = 48f,
        val lineMultiplier: Float = 1f,
        val textColor: Int = 0xFF000000.toInt(),
        val alignment: TextAlignment = TextAlignment.Center,
        val fontFeatureSettings: String? = null,
        val fontVariationSettings: String? = null
    )

    /** Raised when the user edits the buffer, so the settings holder stays in sync. */
    var onTextEdited: ((String) -> Unit)? = null

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val selectionPath = Path()
    private var applying = false
    private var current = State()
    private var cachedLayout: StaticLayout? = null
    private var cachedKey: Any? = null

    init {
        background = null
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setPadding(24, 48, 24, 48)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                cachedKey = null
                if (!applying) onTextEdited?.invoke(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    /** Applies preview settings; cheap enough to be called on every frame of a slider drag. */
    fun update(state: State) {
        val previous = current
        current = state
        if (state.text != text?.toString()) {
            applying = true
            val hadFocus = this.isFocused
            val start = selectionStart.coerceAtLeast(0)
            val end = selectionEnd.coerceAtLeast(0)
            setText(state.text)
            if (hadFocus) {
                val length = this.text.length
                setSelection(start.coerceIn(0, length), end.coerceIn(0, length))
            }
            applying = false
        }
        if (previous.typeface !== state.typeface) typeface = state.typeface
        if (previous.textSizePx != state.textSizePx || paint.textSize != state.textSizePx) {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, state.textSizePx)
        }
        if (previous.textColor != state.textColor) setTextColor(state.textColor)
        if (previous.lineMultiplier != state.lineMultiplier) { setLineSpacing(0f, state.lineMultiplier); cachedKey = null }
        if (previous.fontFeatureSettings != state.fontFeatureSettings) { fontFeatureSettings = state.fontFeatureSettings; cachedKey = null }
        if (previous.fontVariationSettings != state.fontVariationSettings && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            setFontVariationSettings(state.fontVariationSettings); cachedKey = null
        }
        if (previous.alignment != state.alignment) cachedKey = null
        gravity = (when (state.alignment) {
            TextAlignment.Left -> Gravity.LEFT
            TextAlignment.Right -> Gravity.RIGHT
            TextAlignment.Center -> Gravity.CENTER_HORIZONTAL
            TextAlignment.Justify -> Gravity.LEFT
        }) or Gravity.TOP
        if (state.alignment == TextAlignment.Justify) {
            // Justification is drawn by this view; leave the stock layout untouched underneath.
            justificationMode = Layout.JUSTIFICATION_MODE_NONE
        }
        requestLayout()
        invalidate()
    }

    private fun justifyPaint(): TextPaint = paint.apply {
        // Justification computes its own word spacing; a leftover value would shift every line.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) setWordSpacing(0f)
    }

    private fun justifiedLayout(width: Int): StaticLayout {
        val source = text?.toString() ?: ""
        val key = listOf(width, source, current)
        cachedLayout?.let { if (cachedKey == key) return it }
        val layout = StaticLayout.Builder.obtain(source, 0, source.length, justifyPaint(), width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setLineSpacing(0f, current.lineMultiplier)
            .setIncludePad(true)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
            .build()
        cachedLayout = layout
        cachedKey = key
        return layout
    }

    /**
     * The layout the view actually paints. It is a justified `StaticLayout` while "دوطرفه" is
     * selected and the stock layout otherwise; [onDraw] and tests both go through it.
     */
    fun drawnLayout(): Layout {
        if (current.alignment != TextAlignment.Justify) return super.getLayout()
        val width = (width - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(1)
        return justifiedLayout(width)
    }

    override fun onDraw(canvas: Canvas) {
        if (current.alignment != TextAlignment.Justify) {
            cachedLayout = null
            super.onDraw(canvas)
            return
        }
        val width = width - compoundPaddingLeft - compoundPaddingRight
        if (width <= 0) return
        val layout = justifiedLayout(width)
        canvas.save()
        canvas.translate(compoundPaddingLeft.toFloat(), compoundPaddingTop.toFloat() - scrollY)
        layout.draw(canvas)
        drawSelection(canvas, layout)
        canvas.restore()
    }

    /** The caret and selection are drawn here because the stock layout is not what is painted. */
    private fun drawSelection(canvas: Canvas, layout: StaticLayout) {
        if (!isFocused) return
        val length = layout.text.length
        val start = selectionStart.coerceIn(0, length)
        val end = selectionEnd.coerceIn(0, length)
        if (start == end) {
            val line = layout.getLineForOffset(start)
            val x = layout.getPrimaryHorizontal(start)
            selectionPaint.color = current.textColor
            canvas.drawRect(x, layout.getLineTop(line).toFloat(), x + 2f, layout.getLineBottom(line).toFloat(), selectionPaint)
        } else {
            selectionPath.reset()
            layout.getSelectionPath(minOf(start, end), maxOf(start, end), selectionPath)
            selectionPaint.color = (current.textColor and 0x00FFFFFF) or 0x33000000
            canvas.drawPath(selectionPath, selectionPaint)
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (isFocused) invalidate()
    }
}
