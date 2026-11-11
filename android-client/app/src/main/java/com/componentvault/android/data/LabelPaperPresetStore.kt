package com.componentvault.android.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class SavedLabelPaper(
    val name: String,
    val widthMm: Float,
    val heightMm: Float,
    val media: LabelMediaType,
) {
    init {
        require(name.length <= 60)
        require(widthMm in M1TestPaperProfile.WidthRange && heightMm in M1TestPaperProfile.HeightRange)
    }

    fun matches(width: Float?, height: Float?, type: LabelMediaType): Boolean =
        widthMm == width && heightMm == height && media == type
}

internal class LabelPaperPresetStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("m1_label_print", Context.MODE_PRIVATE)

    fun load(): List<SavedLabelPaper> {
        val entries = runCatching { JSONArray(preferences.getString(Key, null) ?: "[]") }.getOrNull()
            ?: return emptyList()
        return (0 until entries.length()).mapNotNull { index ->
            runCatching {
                val entry = entries.getJSONObject(index)
                SavedLabelPaper(
                    name = entry.optString("name"),
                    widthMm = entry.getDouble("width_mm").toFloat(),
                    heightMm = entry.getDouble("height_mm").toFloat(),
                    media = LabelMediaType.fromId(entry.getString("media")),
                )
            }.getOrNull()
        }
    }

    fun save(papers: List<SavedLabelPaper>) {
        val entries = JSONArray()
        for (paper in papers) {
            entries.put(JSONObject().apply {
                put("name", paper.name)
                put("width_mm", paper.widthMm.toDouble())
                put("height_mm", paper.heightMm.toDouble())
                put("media", paper.media.id)
            })
        }
        preferences.edit().putString(Key, entries.toString()).apply()
    }

    private companion object {
        const val Key = "saved_label_papers"
    }
}
