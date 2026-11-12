package com.componentvault.android.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class SavedLabelPaper(
    val name: String,
    val widthMm: Float,
    val heightMm: Float,
    val media: LabelMediaType,
    val rotationDegrees: Int = 0,
    val offsetXmm: Float = 0f,
    val offsetYmm: Float = 0f,
) {
    init {
        require(name.length <= 60)
        paper // Validate all settings, including rotation and offsets.
    }

    val paper get() = M1TestPaperProfile(widthMm, heightMm, rotationDegrees, offsetXmm, offsetYmm)

    fun matches(width: Float?, height: Float?, type: LabelMediaType): Boolean =
        widthMm == width && heightMm == height && media == type
}

/** Paper settings only. Component content and queued snapshots are never stored here. */
internal class LabelPaperPresetStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("m1_label_print", Context.MODE_PRIVATE),
    ) {
        // Preserve presets and the last paper from the earlier P1 test build once.
        if (!preferences.getBoolean("p1_papers_migrated", false)) {
            val previous = context.getSharedPreferences("label_paper_settings", Context.MODE_PRIVATE)
            val old = runCatching { JSONArray(previous.getString("paper_presets", "[]")) }.getOrNull()
            val migrated = (0 until (old?.length() ?: 0)).mapNotNull { index ->
                runCatching {
                    val entry = old!!.getJSONObject(index)
                    val paper = readPaper(entry)
                    SavedLabelPaper(entry.getString("name"), paper.widthMm, paper.heightMm,
                        LabelMediaType.Gap, paper.rotationDegrees, paper.offsetXmm, paper.offsetYmm)
                }.getOrNull()
            }
            if (migrated.isNotEmpty()) {
                val existing = load()
                save(existing + migrated.filter { oldPreset -> existing.none { it.name == oldPreset.name } })
            }
            if (last() == null) previous.getString("last_paper", null)?.let { raw ->
                runCatching { readPaper(JSONObject(raw)) }.getOrNull()?.let(::saveLast)
            }
            preferences.edit().putBoolean("p1_papers_migrated", true).apply()
        }
    }

    fun load(): List<SavedLabelPaper> {
        val entries = runCatching { JSONArray(preferences.getString(Key, "[]")) }.getOrNull()
            ?: return emptyList()
        return (0 until entries.length()).mapNotNull { index ->
            runCatching {
                val entry = entries.getJSONObject(index)
                SavedLabelPaper(entry.optString("name"), entry.getDouble("width_mm").toFloat(),
                    entry.getDouble("height_mm").toFloat(), LabelMediaType.fromId(entry.getString("media")),
                    entry.optInt("rotation", 0), entry.optDouble("offsetX", 0.0).toFloat(),
                    entry.optDouble("offsetY", 0.0).toFloat())
            }.getOrNull()
        }
    }

    fun save(papers: List<SavedLabelPaper>) {
        val entries = JSONArray()
        papers.forEach { paper ->
            entries.put(writePaper(paper.paper).put("name", paper.name)
                .put("width_mm", paper.widthMm.toDouble()).put("height_mm", paper.heightMm.toDouble())
                .put("media", paper.media.id))
        }
        preferences.edit().putString(Key, entries.toString()).apply()
    }

    fun save(name: String, paper: M1TestPaperProfile): List<SavedLabelPaper> {
        val title = name.trim()
        require(title.isNotEmpty() && title.length <= 60)
        val presets = load().toMutableList()
        val index = presets.indexOfFirst { it.name == title }
        val preset = SavedLabelPaper(title, paper.widthMm, paper.heightMm, LabelMediaType.Gap,
            paper.rotationDegrees, paper.offsetXmm, paper.offsetYmm)
        if (index >= 0) presets[index] = preset else {
            require(presets.size < MaxPresets)
            presets.add(preset)
        }
        save(presets)
        return presets
    }

    fun delete(name: String): List<SavedLabelPaper> = load().filterNot { it.name == name }.also(::save)

    fun saveLast(paper: M1TestPaperProfile) {
        preferences.edit().putString("last_paper", writePaper(paper).toString()).apply()
    }

    fun last(): M1TestPaperProfile? = runCatching {
        readPaper(JSONObject(preferences.getString("last_paper", null) ?: return null))
    }.getOrNull()

    private fun writePaper(paper: M1TestPaperProfile) = JSONObject()
        .put("width", paper.widthMm.toDouble()).put("height", paper.heightMm.toDouble())
        .put("rotation", paper.rotationDegrees)
        .put("offsetX", paper.offsetXmm.toDouble()).put("offsetY", paper.offsetYmm.toDouble())

    private fun readPaper(value: JSONObject) = M1TestPaperProfile(
        value.getDouble("width").toFloat(), value.getDouble("height").toFloat(),
        value.getInt("rotation"), value.getDouble("offsetX").toFloat(), value.getDouble("offsetY").toFloat(),
    )

    companion object {
        const val MaxPresets = 12
        private const val Key = "saved_label_papers"
    }
}
