package com.componentvault.android.data

import android.content.SharedPreferences

/** Local-only raster settings for the M1 diagnostic label. */
internal data class M1TestPaperProfile(
    val widthMm: Float,
    val heightMm: Float,
    val rotationDegrees: Int = 0,
    val offsetXmm: Float = 0f,
    val offsetYmm: Float = 0f,
) {
    init {
        require(widthMm.isFinite() && widthMm in WidthRange) {
            "Print-head width must be between ${WidthRange.start} and ${WidthRange.endInclusive} mm"
        }
        require(heightMm.isFinite() && heightMm in HeightRange) {
            "Feed height must be between ${HeightRange.start} and ${HeightRange.endInclusive} mm"
        }
        require(rotationDegrees in Rotations) { "Rotation must be 0, 90, 180, or 270 degrees" }
        require(offsetXmm.isFinite() && offsetXmm in OffsetXRange) {
            "Horizontal offset must be between ${OffsetXRange.start} and ${OffsetXRange.endInclusive} mm"
        }
        require(offsetYmm.isFinite() && offsetYmm in OffsetYRange) {
            "Vertical offset must be between ${OffsetYRange.start} and ${OffsetYRange.endInclusive} mm"
        }
    }

    fun save(preferences: SharedPreferences) {
        preferences.edit()
            .putFloat(KeyWidth, widthMm)
            .putFloat(KeyHeight, heightMm)
            .putInt(KeyRotation, rotationDegrees)
            .putFloat(KeyOffsetX, offsetXmm)
            .putFloat(KeyOffsetY, offsetYmm)
            .apply()
    }

    companion object {
        val WidthRange = 20f..72f
        val HeightRange = 10f..300f
        val OffsetXRange = -72f..72f
        val OffsetYRange = -300f..300f
        val Rotations = setOf(0, 90, 180, 270)

        private const val KeyWidth = "width_mm"
        private const val KeyHeight = "height_mm"
        private const val KeyRotation = "rotation_degrees"
        private const val KeyOffsetX = "offset_x_mm"
        private const val KeyOffsetY = "offset_y_mm"

        fun fromTemplate(template: ComponentLabelTemplate): M1TestPaperProfile {
            val qrTemplate = if (template.isQrLabel && template.physicalWidthMm != null) {
                template
            } else {
                ComponentLabelTemplate.default
            }
            return M1TestPaperProfile(
                widthMm = requireNotNull(qrTemplate.physicalWidthMm),
                heightMm = qrTemplate.physicalHeightMm,
            )
        }

        fun load(
            preferences: SharedPreferences,
            defaultTemplate: ComponentLabelTemplate,
        ): M1TestPaperProfile {
            val fallback = fromTemplate(defaultTemplate)
            return runCatching {
                M1TestPaperProfile(
                    widthMm = preferences.getFloat(KeyWidth, fallback.widthMm),
                    heightMm = preferences.getFloat(KeyHeight, fallback.heightMm),
                    rotationDegrees = preferences.getInt(KeyRotation, fallback.rotationDegrees),
                    offsetXmm = preferences.getFloat(KeyOffsetX, fallback.offsetXmm),
                    offsetYmm = preferences.getFloat(KeyOffsetY, fallback.offsetYmm),
                )
            }.getOrElse { fallback }
        }
    }
}
