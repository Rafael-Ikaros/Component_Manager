package com.componentvault.android.data

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Measures and draws text directly on the printer's dot grid. */
internal object LabelTextRenderer {
    private const val MinTextDots = 8

    fun draw(
        canvas: Canvas,
        bounds: RectF,
        lines: List<String>,
        font: LabelFont = LabelFont.Default,
        ellipsize: Boolean = false,
        maxTextDots: Int = Int.MAX_VALUE,
    ) {
        val visible = lines.filter(String::isNotBlank)
        require(visible.isNotEmpty()) { "标签没有可打印内容。" }
        val box = Rect(ceil(bounds.left).toInt(), ceil(bounds.top).toInt(),
            floor(bounds.right).toInt(), floor(bounds.bottom).toInt())
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            hinting = Paint.HINTING_ON
            isSubpixelText = false
            typeface = when (font) {
                LabelFont.Default -> Typeface.DEFAULT
                LabelFont.Bold -> Typeface.DEFAULT_BOLD
                LabelFont.Condensed -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                LabelFont.Monospace -> Typeface.MONOSPACE
                LabelFont.Serif -> Typeface.SERIF
            }
        }

        // Search whole-dot sizes using the selected face's actual glyph bounds.
        var low = MinTextDots
        var high = minOf(box.height() / visible.size, maxTextDots)
        var best: TextLayout? = null
        while (low <= high) {
            val size = (low + high) / 2
            paint.textSize = size.toFloat()
            val layout = measure(visible, paint, box, ellipsize)
            if (layout == null) {
                high = size - 1
            } else {
                best = layout
                low = size + 1
            }
        }
        requireNotNull(best) { "文字内容无法完整放入当前纸张，请增大纸张或缩短文字。" }

        paint.textSize = best.textSize
        var baseline = box.top + (box.height() - best.height) / 2 - best.inkTop
        for (line in best.lines) {
            canvas.drawText(line.text, (box.left + line.leftInset).toFloat(), baseline.toFloat(), paint)
            baseline += best.lineAdvance
        }
    }

    private fun measure(lines: List<String>, paint: TextPaint, box: Rect, ellipsize: Boolean): TextLayout? {
        val ink = Rect()
        var inkTop = 0
        var inkBottom = 0
        val measured = lines.map { text ->
            val shown = if (ellipsize) TextUtils.ellipsize(text, paint, box.width().toFloat(),
                TextUtils.TruncateAt.END).toString() else text
            if (shown.isEmpty()) return null
            paint.getTextBounds(shown, 0, shown.length, ink)
            val leftInset = maxOf(0, -ink.left)
            if (leftInset + maxOf(ink.right, ceil(paint.measureText(shown)).toInt()) > box.width()) return null
            inkTop = minOf(inkTop, ink.top)
            inkBottom = maxOf(inkBottom, ink.bottom)
            TextLine(shown, leftInset)
        }
        val inkHeight = inkBottom - inkTop
        val gap = (paint.textSize * 0.16f).roundToInt().coerceAtLeast(1)
        val lineAdvance = maxOf(ceil(paint.fontSpacing).toInt(), inkHeight + gap)
        val height = inkHeight + (measured.size - 1) * lineAdvance
        if (inkHeight == 0 || height > box.height()) return null
        return TextLayout(paint.textSize, measured, inkTop, height, lineAdvance)
    }

    private data class TextLine(val text: String, val leftInset: Int)

    private data class TextLayout(
        val textSize: Float,
        val lines: List<TextLine>,
        val inkTop: Int,
        val height: Int,
        val lineAdvance: Int,
    )
}
