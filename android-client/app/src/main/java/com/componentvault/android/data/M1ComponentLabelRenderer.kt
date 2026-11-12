package com.componentvault.android.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.componentvault.android.model.ComponentLabelSeed
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Renders a shared 203 dpi raster for preview, export, and Bluetooth printing. */
internal object M1ComponentLabelRenderer {
    private const val Dpi = 203
    private const val EdgeInsetDots = 8f // Approximately 1 mm at 203 dpi; not a printed frame.
    private const val GapDots = 6f
    private const val MinQrModuleDots = 2
    private const val LegacyMaxTextDots = 16
    private const val PrintInkThreshold = 160_000

    fun render(
        seed: ComponentLabelSeed?,
        template: ComponentLabelTemplate,
        textTemplate: ComponentTextLabelTemplate,
        paper: M1TestPaperProfile,
        design: LabelDesign? = null,
        freeLabel: FreeLabel? = null,
    ): Bitmap {
        require((seed == null) != (freeLabel == null)) { "标签来源无效。" }
        if (freeLabel != null) require(design != null) { "自由标签缺少设计。" }
        val width = dots(paper.widthMm)
        val height = dots(paper.heightMm)
        val offsetX = dots(paper.offsetXmm)
        val offsetY = dots(paper.offsetYmm)
        val quarterTurn = paper.rotationDegrees == 90 || paper.rotationDegrees == 270
        val logicalWidth = if (quarterTurn) height else width
        val logicalHeight = if (quarterTurn) width else height
        val safeWidth = width - 2f * EdgeInsetDots
        val safeHeight = height - 2f * EdgeInsetDots
        val contentWidth = if (quarterTurn) safeHeight else safeWidth
        val contentHeight = if (quarterTurn) safeWidth else safeHeight
        val bounds = RectF(
            (logicalWidth - contentWidth) / 2f,
            (logicalHeight - contentHeight) / 2f,
            (logicalWidth + contentWidth) / 2f,
            (logicalHeight + contentHeight) / 2f,
        )
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.save()
            if (design == null) when (paper.rotationDegrees) {
                90 -> { canvas.translate(width.toFloat(), 0f); canvas.rotate(90f) }
                180 -> { canvas.translate(width.toFloat(), height.toFloat()); canvas.rotate(180f) }
                270 -> { canvas.translate(0f, height.toFloat()); canvas.rotate(-90f) }
            }
            val qrBounds = if (design != null) {
                drawEdited(canvas, seed, template, paper, design, freeLabel)
            } else if (template.isQrLabel) {
                val component = requireNotNull(seed)
                val payload = requireNotNull(ComponentLabelCodec.buildQrPayload(component, template))
                drawQrLabel(canvas, bounds, component, payload.rawValue).also { canvas.matrix.mapRect(it) }
            } else {
                drawTextOnly(canvas, bounds, ComponentLabelRenderer.buildTextLabelContent(requireNotNull(seed), textTemplate))
                null
            }
            canvas.restore()
            // Check the dots that will be printed before translating the image on the paper axes.
            applyPrintInk(bitmap, offsetX, offsetY)
            if (offsetX != 0 || offsetY != 0) {
                qrBounds?.let {
                    require(it.left + offsetX >= 0 && it.top + offsetY >= 0 &&
                        it.right + offsetX <= width && it.bottom + offsetY <= height) {
                        "偏移后二维码静区不完整，请减小偏移。"
                    }
                }
                val shifted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                Canvas(shifted).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, offsetX.toFloat(), offsetY.toFloat(), null)
                }
                bitmap.recycle()
                return shifted
            }
            return bitmap
        } catch (error: Exception) {
            bitmap.recycle()
            throw error
        }
    }

    private fun drawEdited(
        canvas: Canvas,
        seed: ComponentLabelSeed?,
        template: ComponentLabelTemplate,
        paper: M1TestPaperProfile,
        design: LabelDesign,
        freeLabel: FreeLabel?,
    ): RectF? {
        design.validate(paper)
        val qrElements = design.elements.filter { it.type == LabelElementType.Qr }
        val expectedQrCount = if (template.isQrLabel) 1 else 0
        require(if (freeLabel != null) qrElements.size <= 1 else qrElements.size == expectedQrCount) {
            "标签模板与二维码元素不匹配。"
        }
        val boxes = design.elements.associateWith { element ->
            RectF(
                element.xMm * Dpi / 25.4f,
                element.yMm * Dpi / 25.4f,
                (element.xMm + element.widthMm) * Dpi / 25.4f,
                (element.yMm + element.heightMm) * Dpi / 25.4f,
            )
        }
        val qrBounds = qrElements.singleOrNull()?.let { qrElement ->
            val payload = if (freeLabel != null) {
                qrElement.text.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("请输入二维码内容。")
            } else requireNotNull(ComponentLabelCodec.buildQrPayload(requireNotNull(seed), template)).rawValue
            val qr = try {
                QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0,
                    mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"))
            } catch (error: Exception) {
                throw IllegalArgumentException("二维码内容无法编码，请缩短数据后重试。", error)
            }
            val box = boxes.getValue(qrElement)
            val moduleDots = floor(min(box.width(), box.height()) / qr.width).toInt()
            require(moduleDots >= MinQrModuleDots) {
                "二维码需要至少 $MinQrModuleDots 点/模块和完整静区，请增大二维码或纸张。"
            }
            val side = qr.width * moduleDots
            val x = floor(box.centerX() - side / 2f).toInt()
            val y = floor(box.centerY() - side / 2f).toInt()
            val actual = RectF(x.toFloat(), y.toFloat(), (x + side).toFloat(), (y + side).toFloat())
            require(actual.left >= 0f && actual.top >= 0f &&
                actual.right <= canvas.width && actual.bottom <= canvas.height) {
                "二维码静区超出纸张。"
            }
            val paint = Paint().apply { color = Color.BLACK; isAntiAlias = false; style = Paint.Style.FILL }
            for (row in 0 until qr.height) for (column in 0 until qr.width) {
                if (qr[column, row]) canvas.drawRect(
                    (x + column * moduleDots).toFloat(), (y + row * moduleDots).toFloat(),
                    (x + (column + 1) * moduleDots).toFloat(), (y + (row + 1) * moduleDots).toFloat(), paint)
            }
            actual
        }
        boxes.forEach { (element, box) ->
            if (element.type == LabelElementType.Text && element.text.isNotBlank()) {
                require(qrBounds == null || !RectF.intersects(box, qrBounds)) {
                    "文字与二维码静区重叠，请移动文字或二维码。"
                }
                canvas.save()
                val textBox = when (paper.rotationDegrees) {
                    90 -> {
                        canvas.translate(canvas.width.toFloat(), 0f)
                        canvas.rotate(90f)
                        RectF(box.top, canvas.width - box.right, box.bottom, canvas.width - box.left)
                    }
                    180 -> {
                        canvas.translate(canvas.width.toFloat(), canvas.height.toFloat())
                        canvas.rotate(180f)
                        RectF(canvas.width - box.right, canvas.height - box.bottom,
                            canvas.width - box.left, canvas.height - box.top)
                    }
                    270 -> {
                        canvas.translate(0f, canvas.height.toFloat())
                        canvas.rotate(-90f)
                        RectF(canvas.height - box.bottom, box.left,
                            canvas.height - box.top, box.right)
                    }
                    else -> box
                }
                try {
                    LabelTextRenderer.draw(canvas, textBox,
                        element.text.split(Regex("\\r\\n|\\r|\\n| \\| ")), font = element.font,
                        textSizeDots = element.fontSizePt?.let { it * Dpi / 72f })
                } finally {
                    canvas.restore()
                }
            }
        }
        require(boxes.keys.any { it.type == LabelElementType.Text && it.text.isNotBlank() } || qrBounds != null) {
            "标签没有可打印内容。"
        }
        return qrBounds
    }

    private fun drawQrLabel(canvas: Canvas, bounds: RectF, seed: ComponentLabelSeed, payload: String): RectF {
        val qr = try {
            QRCodeWriter().encode(
                payload,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"),
            )
        } catch (error: Exception) {
            throw IllegalArgumentException("库存二维码内容无法编码，请缩短数据后重试。", error)
        }
        val horizontal = bounds.width() >= bounds.height() * 1.2f
        val textReserve = if (horizontal) 36f else 24f
        val qrBudget = if (horizontal) {
            min(bounds.height(), bounds.width() - textReserve - GapDots)
        } else {
            min(bounds.width(), bounds.height() - textReserve - GapDots)
        }
        val moduleDots = floor(qrBudget / qr.width).toInt()
        require(moduleDots >= MinQrModuleDots) {
            "纸张或偏移后可用空间不足：二维码需要至少 $MinQrModuleDots 点/模块和完整静区，请增大纸张或改用短二维码模板。"
        }
        val side = qr.width * moduleDots
        val qrLeft: Int
        val qrTop: Int
        val textBounds: RectF
        if (horizontal) {
            qrLeft = floor(bounds.right - side).toInt()
            qrTop = floor(bounds.centerY() - side / 2f).toInt()
            textBounds = RectF(bounds.left, bounds.top, qrLeft - GapDots, bounds.bottom)
        } else {
            qrLeft = floor(bounds.centerX() - side / 2f).toInt()
            qrTop = floor(bounds.bottom - side).toInt()
            textBounds = RectF(bounds.left, bounds.top, bounds.right, qrTop - GapDots)
        }
        require(textBounds.width() >= 24f && textBounds.height() >= 16f) {
            "纸张没有足够空间同时放置库存文字和可扫描二维码，请增大纸张。"
        }
        val qrPaint = Paint().apply { color = Color.BLACK; isAntiAlias = false; style = Paint.Style.FILL }
        for (y in 0 until qr.height) {
            for (x in 0 until qr.width) {
                if (qr[x, y]) {
                    canvas.drawRect(
                        (qrLeft + x * moduleDots).toFloat(),
                        (qrTop + y * moduleDots).toFloat(),
                        (qrLeft + (x + 1) * moduleDots).toFloat(),
                        (qrTop + (y + 1) * moduleDots).toFloat(),
                        qrPaint,
                    )
                }
            }
        }
        LabelTextRenderer.draw(
            canvas,
            textBounds,
            listOf(seed.name.ifBlank { seed.model.orEmpty().ifBlank { seed.sku } }, seed.sku,
                "${seed.packageName}  QTY ${seed.quantity}"),
            ellipsize = true,
            maxTextDots = LegacyMaxTextDots,
        )
        return RectF(qrLeft.toFloat(), qrTop.toFloat(), (qrLeft + side).toFloat(), (qrTop + side).toFloat())
    }

    private fun drawTextOnly(canvas: Canvas, bounds: RectF, content: String) {
        require(content.isNotBlank()) { "文字标签没有可打印内容。" }
        LabelTextRenderer.draw(canvas, bounds, content.split(" | "), maxTextDots = LegacyMaxTextDots)
    }

    private fun applyPrintInk(bitmap: Bitmap, offsetX: Int, offsetY: Int) {
        val width = bitmap.width
        val height = bitmap.height
        val row = IntArray(width)
        // Preserve more of the anti-aliased stroke coverage when converting to one-bit ink.
        // QR modules and their quiet zones are already black or white and stay unchanged.
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            var changed = false
            for (x in row.indices) {
                val color = row[x]
                if (color == Color.WHITE) continue
                val ink = if (color == Color.BLACK || Color.red(color) * 299 +
                    Color.green(color) * 587 + Color.blue(color) * 114 < PrintInkThreshold
                ) Color.BLACK else Color.WHITE
                require(ink == Color.WHITE || (x + offsetX in 0 until width && y + offsetY in 0 until height)) {
                    "偏移后会裁掉标签内容，请减小偏移或调整纸张尺寸。"
                }
                if (ink != color) {
                    row[x] = ink
                    changed = true
                }
            }
            if (changed) bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
    }

    internal fun dots(mm: Float): Int = (mm * Dpi / 25.4f).roundToInt()
}
