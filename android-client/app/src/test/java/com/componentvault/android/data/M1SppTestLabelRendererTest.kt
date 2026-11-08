package com.componentvault.android.data

import android.app.Application
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class M1SppTestLabelRendererTest {
    @Test
    fun existingPaperSizesProduce203DpiRasterAndReadableQr() {
        listOf(ComponentLabelTemplate.Qr10x40 to 80, ComponentLabelTemplate.Qr30x40 to 240).forEach { (template, expectedHeight) ->
            val bitmap = M1TestLabelRenderer.render(template)
            try {
                assertEquals(320, bitmap.width)
                assertEquals(expectedHeight, bitmap.height)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                assertEquals(M1TestLabelRenderer.QrContent, MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun variableWidthTextStripIsNotSilentlyResized() {
        assertFailsWith<IllegalArgumentException> { M1TestLabelRenderer.render(ComponentLabelTemplate.TextOnly) }
    }

    @Test
    fun customSquareAndLandscapePaperKeepReadableIntegerScaledQr() {
        listOf(
            M1TestPaperProfile(widthMm = 25f, heightMm = 25f),
            M1TestPaperProfile(widthMm = 30f, heightMm = 20f),
        ).forEach { profile ->
            val bitmap = M1TestLabelRenderer.render(profile)
            try {
                assertEquals(dots(profile.widthMm), bitmap.width)
                assertEquals(dots(profile.heightMm), bitmap.height)
                assertEquals(M1TestLabelRenderer.QrContent, decode(bitmap))
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun rotationKeepsPhysicalCanvasAndReadableQr() {
        M1TestPaperProfile.Rotations.forEach { rotation ->
            val profile = M1TestPaperProfile(widthMm = 30f, heightMm = 20f, rotationDegrees = rotation)
            val bitmap = M1TestLabelRenderer.render(profile)
            try {
                assertEquals(dots(30f), bitmap.width)
                assertEquals(dots(20f), bitmap.height)
                assertEquals(M1TestLabelRenderer.QrContent, decode(bitmap))
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun offsetClipsAtPhysicalCanvasWithoutChangingItsSize() {
        val centered = M1TestLabelRenderer.render(M1TestPaperProfile(30f, 20f))
        val shifted = M1TestLabelRenderer.render(M1TestPaperProfile(30f, 20f, offsetXmm = 20f))
        try {
            assertEquals(centered.width, shifted.width)
            assertEquals(centered.height, shifted.height)
            assertTrue(blackPixels(shifted) < blackPixels(centered))
        } finally {
            centered.recycle()
            shifted.recycle()
        }
    }

    @Test
    fun smallestPaperAtEveryRotationKeepsDefaultContentInsideCanvas() {
        M1TestPaperProfile.Rotations.forEach { rotation ->
            val bitmap = M1TestLabelRenderer.render(M1TestPaperProfile(20f, 10f, rotation))
            try {
                val bounds = blackBounds(bitmap)
                assertTrue(bounds.first > 0, "rotation $rotation touched the left edge")
                assertTrue(bounds.second > 0, "rotation $rotation touched the top edge")
                assertTrue(bounds.third < bitmap.width - 1, "rotation $rotation touched the right edge")
                assertTrue(bounds.fourth < bitmap.height - 1, "rotation $rotation touched the bottom edge")
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun profileRejectsValuesOutsideActualRasterLimits() {
        assertFailsWith<IllegalArgumentException> { M1TestPaperProfile(19.9f, 20f) }
        assertFailsWith<IllegalArgumentException> { M1TestPaperProfile(72.1f, 20f) }
        assertFailsWith<IllegalArgumentException> { M1TestPaperProfile(30f, 9.9f) }
        assertFailsWith<IllegalArgumentException> { M1TestPaperProfile(30f, 300.1f) }
        assertFailsWith<IllegalArgumentException> { M1TestPaperProfile(30f, 20f, rotationDegrees = 45) }
        assertFailsWith<IllegalArgumentException> { M1TestPaperProfile(30f, 20f, offsetXmm = Float.NaN) }
    }

    @Test
    fun profilePersistsAsOneLocalSettingsRecordAndFallsBackFromCorruption() {
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("m1-test-profile", 0)
        val profile = M1TestPaperProfile(25f, 40f, 270, -1.5f, 2.25f)
        profile.save(preferences)
        assertEquals(profile, M1TestPaperProfile.load(preferences, ComponentLabelTemplate.Qr10x40))

        preferences.edit().putFloat("width_mm", 80f).apply()
        assertEquals(
            M1TestPaperProfile.fromTemplate(ComponentLabelTemplate.Qr10x40),
            M1TestPaperProfile.load(preferences, ComponentLabelTemplate.Qr10x40),
        )
    }

    private fun decode(bitmap: android.graphics.Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    private fun blackPixels(bitmap: android.graphics.Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { it and 0x00ffffff == 0 }
    }

    private fun blackBounds(bitmap: android.graphics.Bitmap): PixelBounds {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var minX = bitmap.width
        var minY = bitmap.height
        var maxX = -1
        var maxY = -1
        pixels.forEachIndexed { index, color ->
            if (color and 0x00ffffff == 0) {
                val x = index % bitmap.width
                val y = index / bitmap.width
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
            }
        }
        assertTrue(maxX >= 0, "renderer produced no black content")
        return PixelBounds(minX, minY, maxX, maxY)
    }

    private data class PixelBounds(
        val first: Int,
        val second: Int,
        val third: Int,
        val fourth: Int,
    )

    private fun dots(mm: Float): Int = (mm * M1TestLabelRenderer.Dpi / 25.4f).roundToInt()
}
