package org.fontmanager

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
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
 * Preview text area.
 *
 * Two platform behaviours make a plain `EditText` unsuitable here:
 *  - It always builds a `DynamicLayout`, and `setJustificationMode()` only takes effect for text
 *    that is not spannable, so "دوطرفه" silently fell back to left alignment.
 *  - The editable buffer keeps its old layout (and its old paint) until the next layout pass or
 *    after a frame, which showed the system font for a moment and ignored axis changes.
 *
 * Therefore this view never relies on `TextView`'s drawing path: it paints its own `Layout` from
 * the current settings on every frame. Justify uses a `StaticLayout` with
 * `JUSTIFICATION_MODE_INTER_WORD`; other alignments use a `StaticLayout` too. Variations and font
 * features are written on the paint right before drawing, so a slider drag is visible immediately
 * and a different font can never be shown with the previous font or a stale colour.
 */
class PreviewEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : EditText(context, attrs, defStyleAttr) {

    data class State(
        val text: String = "",
        val typeface: Typeface? = null,
        val textSizePx: Float = 48f,
        val lineMultiplier: Float = 1f,
        val textColor: Int = 0xFF000000.toInt(),
        val alignment: TextAlignment = TextAlignment.Center,
        val fontFeatureSettings: String? = null,
        val fontVariationSettings: String? = null
    )

    /** Raised when the user edits the buffer, so the settings holder stays in sync. */
    var onTextEdited: ((String) -> Unit)? = null

    private val inkPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isAntiAlias = true }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val selectionPath = Path()
    private var applying = false

    /**
     * The view's own buffer, which is what the IME, caret and accessibility services act on.
     * `TextView.setText` copies an `Editable` it is given, so a separate builder would silently
     * drift from the text the view actually holds.
     */
    private val buffer: Editable get() = text as Editable

    /** Latest settings; written by [update] from Compose. */
    private var state = State()

    /** `TextView`'s own colour is always transparent; the visible text is painted by this view. */
    private var foreground = 0xFF000000.toInt()

    private var cachedLayout: Layout? = null
    private var cachedKey: Any? = null

    init {
        background = null
        // The buffer exists only for the IME, caret, selection and accessibility services. Nothing
        // from TextView's layout is painted, so its own colours never leak into the preview.
        super.setTextColor(android.graphics.Color.TRANSPARENT)
        setHintTextColor(android.graphics.Color.TRANSPARENT)
        highlightColor = 0x33000000
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

    /** Applies settings; cheap enough to run on every frame of a slider drag. */
    fun update(next: State) {
        val previous = state
        val textChanged = next.text != buffer.toString()
        state = next
        foreground = next.textColor
        if (textChanged) {
            applying = true
            val hadFocus = isFocused
            val start = selectionStart
            val end = selectionEnd
            buffer.replace(0, buffer.length, next.text)
            if (hadFocus) {
                val length = buffer.length
                setSelection(start.coerceIn(0, length), end.coerceIn(0, length))
            }
            applying = false
        }
        if (previous.alignment != next.alignment) {
            gravity = (when (next.alignment) {
                TextAlignment.Left -> Gravity.LEFT
                TextAlignment.Right -> Gravity.RIGHT
                TextAlignment.Center -> Gravity.CENTER_HORIZONTAL
                TextAlignment.Justify -> Gravity.LEFT
            }) or Gravity.TOP
        }
        val changed = textChanged ||
            previous.typeface !== next.typeface ||
            previous.textSizePx != next.textSizePx ||
            previous.lineMultiplier != next.lineMultiplier ||
            previous.alignment != next.alignment ||
            previous.fontFeatureSettings != next.fontFeatureSettings ||
            previous.fontVariationSettings != next.fontVariationSettings
        if (changed) {
            cachedKey = null
            requestLayout()
            invalidate()
        } else if (previous.textColor != next.textColor) {
            invalidate()
        }
    }

    /** The layout currently painted; also the place tests read line positions from. */
    fun drawnLayout(): Layout {
        val width = (width - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(1)
        return layoutFor(width)
    }

    /** The view is measured from the layout it draws, not from `TextView`'s unused layout. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val contentWidth = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize - compoundPaddingLeft - compoundPaddingRight
            MeasureSpec.AT_MOST -> widthSize - compoundPaddingLeft - compoundPaddingRight
            else -> suggestedMinimumWidth
        }.coerceAtLeast(1)
        val layout = layoutFor(contentWidth)
        val desiredWidth = (layout.width + compoundPaddingLeft + compoundPaddingRight).coerceAtLeast(suggestedMinimumWidth)
        val desiredHeight = (layout.height + compoundPaddingTop + compoundPaddingBottom).coerceAtLeast(suggestedMinimumHeight)
        val width = if (widthMode == MeasureSpec.EXACTLY) widthSize else desiredWidth
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> minOf(desiredHeight, heightSize)
            else -> desiredHeight
        }
        setMeasuredDimension(width, height)
    }

    private fun paint(): TextPaint = inkPaint.apply {
        typeface = state.typeface
        textSize = state.textSizePx.coerceAtLeast(1f)
        color = foreground
        fontFeatureSettings = state.fontFeatureSettings
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) setFontVariationSettings(state.fontVariationSettings)
        // Justification computes its own word spacing; a leftover value would shift every line.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) setWordSpacing(0f)
    }

    private fun layoutFor(width: Int): Layout {
        val key = listOf(width, buffer.toString(), state)
        cachedLayout?.let { if (cachedKey == key) return it }
        val layout = StaticLayout.Builder.obtain(buffer, 0, buffer.length, paint(), width)
            .setAlignment(
                when (state.alignment) {
                    TextAlignment.Center -> Layout.Alignment.ALIGN_CENTER
                    TextAlignment.Right -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                }
            )
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setLineSpacing(0f, state.lineMultiplier)
            .setIncludePad(true)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .apply {
                if (state.alignment == TextAlignment.Justify && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
                }
            }
            .build()
        cachedLayout = layout
        cachedKey = key
        return layout
    }

    override fun onDraw(canvas: Canvas) {
        val width = width - compoundPaddingLeft - compoundPaddingRight
        if (width <= 0) return
        // Repaint with the live settings: the layout may have been built with an older typeface.
        val layout = layoutFor(width)
        canvas.save()
        canvas.translate(compoundPaddingLeft.toFloat(), compoundPaddingTop.toFloat() - scrollY)
        layout.draw(canvas)
        drawSelection(canvas, layout)
        canvas.restore()
    }

    /** Caret and selection are drawn here because `TextView`'s own text is transparent. */
    private fun drawSelection(canvas: Canvas, layout: Layout) {
        if (!isFocused) return
        val length = layout.text.length
        val start = selectionStart.coerceIn(0, length)
        val end = selectionEnd.coerceIn(0, length)
        if (start == end) {
            val line = layout.getLineForOffset(start)
            val x = layout.getPrimaryHorizontal(start)
            selectionPaint.color = foreground
            canvas.drawRect(x, layout.getLineTop(line).toFloat(), x + 2f, layout.getLineBottom(line).toFloat(), selectionPaint)
        } else {
            selectionPath.reset()
            layout.getSelectionPath(minOf(start, end), maxOf(start, end), selectionPath)
            selectionPaint.color = (foreground and 0x00FFFFFF) or 0x33000000
            canvas.drawPath(selectionPath, selectionPaint)
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (isFocused) invalidate()
    }
}
