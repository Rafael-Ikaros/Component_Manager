package com.componentvault.android.data

import android.graphics.Bitmap
import android.graphics.Color
import com.componentvault.android.model.ComponentLabelSeed
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class M1ComponentLabelRendererTest {
    private val seed = ComponentLabelSeed("C70565", "贴片电阻 RC0603FR", "电阻", "0603", "A01", 15, 1,
        model = "RC0603FR", brand = "Example")

    @Test fun hmPrintableWidthUsesExistingRasterWithoutRescalingQr() {
        val template = ComponentLabelTemplate.Qr10x40
        val bitmap = M1ComponentLabelRenderer.render(seed, template, ComponentTextLabelTemplate.default,
            M1TestPaperProfile(72f, 60f))
        try {
            assertEquals(575, bitmap.width)
            assertEquals(ComponentLabelCodec.buildQrPayload(seed, template)!!.rawValue, decode(bitmap))
        } finally { bitmap.recycle() }
    }

    @Test fun actualPaperRasterDecodesTheOriginalPayloadInAllRotations() {
        for (rotation in listOf(0, 90, 180, 270)) {
            for (template in listOf(ComponentLabelTemplate.Qr10x40, ComponentLabelTemplate.Qr30x40)) {
                val bitmap = M1ComponentLabelRenderer.render(seed, template, ComponentTextLabelTemplate.default,
                    M1TestPaperProfile(40f, 60f, rotation))
                try {
                    assertEquals(320, bitmap.width)
                    assertEquals(480, bitmap.height)
                    assertEquals(ComponentLabelCodec.buildQrPayload(seed, template)!!.rawValue, decode(bitmap))
                    val packed = M1SppPrintProtocol.frames(bitmap)
                    assertEquals(480, packed.sumOf { it.rows })
                    assertTrue(packed.all { it.rawBytes <= 1024 })
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test fun offsetsPreserveQrQuietZoneAndDoNotClipThePaper() {
        val template = ComponentLabelTemplate.Qr10x40
        val bitmap = M1ComponentLabelRenderer.render(seed, template, ComponentTextLabelTemplate.default,
            M1TestPaperProfile(40f, 60f, 90, 0.5f, -0.5f))
        try {
            assertEquals(ComponentLabelCodec.buildQrPayload(seed, template)!!.rawValue, decode(bitmap))
            assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
        } finally { bitmap.recycle() }
    }

    @Test fun horizontalOffsetTranslatesInkExactlyWithoutResizingAndPaperEdgesRemainWhite() {
        val template = ComponentLabelTemplate.Qr10x40
        val original = M1ComponentLabelRenderer.render(seed, template, ComponentTextLabelTemplate.default,
            M1TestPaperProfile(40f, 60f))
        val shifted = M1ComponentLabelRenderer.render(seed, template, ComponentTextLabelTemplate.default,
            M1TestPaperProfile(40f, 60f, offsetXmm = 0.5f))
        try {
            for (y in 0 until original.height) for (x in 0 until original.width - 4) {
                assertEquals(original.getPixel(x, y), shifted.getPixel(x + 4, y))
            }
            for (x in 0 until original.width) {
                assertEquals(Color.WHITE, original.getPixel(x, 0))
                assertEquals(Color.WHITE, original.getPixel(x, original.height - 1))
            }
            for (y in 0 until original.height) {
                assertEquals(Color.WHITE, original.getPixel(0, y))
                assertEquals(Color.WHITE, original.getPixel(original.width - 1, y))
            }
        } finally { original.recycle(); shifted.recycle() }
    }

    @Test fun impossibleOffsetAndDenseTinyQrAreRejectedBeforeSending() {
        assertFailsWith<IllegalArgumentException> {
            M1ComponentLabelRenderer.render(seed, ComponentLabelTemplate.default, ComponentTextLabelTemplate.default,
                M1TestPaperProfile(40f, 60f, offsetXmm = 25f))
        }
        val dense = seed.copy(name = "元件参数".repeat(90))
        assertFailsWith<IllegalArgumentException> {
            M1ComponentLabelRenderer.render(dense, ComponentLabelTemplate.default, ComponentTextLabelTemplate.default,
                M1TestPaperProfile(20f, 10f))
        }
    }

    @Test fun textOnlyLabelsUseTheSelectedTextFields() {
        val template = ComponentLabelTemplate.entries.first { !it.isQrLabel }
        val bitmap = M1ComponentLabelRenderer.render(seed, template, ComponentTextLabelTemplate.NamePackageSku,
            M1TestPaperProfile(40f, 60f))
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(pixels.any { Color.red(it) < 128 })
            assertEquals(320, bitmap.width)
        } finally { bitmap.recycle() }
    }

    @Test fun editedTextAndMovedQrStillDecodeTheOriginalInventoryPayload() {
        val template = ComponentLabelTemplate.Qr30x40
        val paper = M1TestPaperProfile(40f, 60f)
        val original = LabelDesign.default(seed, template, ComponentTextLabelTemplate.default, paper)
        val qr = original.elements.first { it.id == "qr" }
        val edited = original.withText("name", "用户自定义显示文字")
            .moved("qr", qr.xMm + 0.5f, qr.yMm, paper)
        val bitmap = M1ComponentLabelRenderer.render(seed, template,
            ComponentTextLabelTemplate.default, paper, edited)
        try {
            assertEquals(ComponentLabelCodec.buildQrPayload(seed, template)!!.rawValue, decode(bitmap))
        } finally { bitmap.recycle() }
    }

    @Test fun freeQrUsesEditablePayloadWithoutAnInventorySeed() {
        val paper = M1TestPaperProfile(40f, 60f)
        val payload = "https://example.test/shelf/A1"
        val design = LabelDesign.free(FreeLabelTemplate.Qr, paper).withQrPayload("qr", payload)
        val bitmap = M1ComponentLabelRenderer.render(null, ComponentLabelTemplate.default,
            ComponentTextLabelTemplate.default, paper, design,
            FreeLabel("Shelf A1", FreeLabelTemplate.Qr))
        try { assertEquals(payload, decode(bitmap)) } finally { bitmap.recycle() }
    }

    @Test fun editedQrCannotBeMovedIntoTextOrBeyondPaper() {
        val paper = M1TestPaperProfile(40f, 60f)
        val design = LabelDesign.default(seed, ComponentLabelTemplate.Qr30x40,
            ComponentTextLabelTemplate.default, paper)
        assertFailsWith<IllegalArgumentException> {
            M1ComponentLabelRenderer.render(seed, ComponentLabelTemplate.Qr30x40,
                ComponentTextLabelTemplate.default, paper,
                design.copy(elements = design.elements.map {
                    if (it.id == "qr") it.copy(xMm = 0f, yMm = 0f) else it
                }))
        }
    }

    @Test fun editedDesignKeepsQrScannableAfterPaperRotation() {
        val compactSeed = seed.copy(name = "R")
        for (rotation in listOf(90, 180, 270)) {
            val paper = M1TestPaperProfile(40f, 60f, rotation)
            val design = LabelDesign.default(compactSeed, ComponentLabelTemplate.Qr10x40,
                ComponentTextLabelTemplate.default, paper).withText("name", "Custom")
            val bitmap = M1ComponentLabelRenderer.render(compactSeed, ComponentLabelTemplate.Qr10x40,
                ComponentTextLabelTemplate.default, paper, design)
            try {
                assertEquals(ComponentLabelCodec.buildQrPayload(compactSeed,
                    ComponentLabelTemplate.Qr10x40)!!.rawValue, decode(bitmap))
            } finally { bitmap.recycle() }
        }
    }

    @Test fun editableMultilineTextDoesNotChangeQrPayload() {
        val compactSeed = seed.copy(name = "R")
        val paper = M1TestPaperProfile(40f, 60f)
        val template = ComponentLabelTemplate.Qr30x40
        val design = LabelDesign.default(compactSeed, template,
            ComponentTextLabelTemplate.default, paper).withText("name", "R\nNew")
        val bitmap = M1ComponentLabelRenderer.render(compactSeed, template,
            ComponentTextLabelTemplate.default, paper, design)
        try {
            assertEquals(ComponentLabelCodec.buildQrPayload(compactSeed, template)!!.rawValue, decode(bitmap))
        } finally { bitmap.recycle() }
    }

    private fun decode(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))),
            mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")).text
    }
}
