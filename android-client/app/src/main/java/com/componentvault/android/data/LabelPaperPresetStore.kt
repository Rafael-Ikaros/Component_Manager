package com.componentvault.android.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class LabelPaperPreset(val name: String, val paper: M1TestPaperProfile)

/** Local settings only; never changes a queued label's immutable paper snapshot. */
internal class LabelPaperPresetStore(private val preferences: SharedPreferences) {
    fun load(): List<LabelPaperPreset> = runCatching {
        val values = JSONArray(preferences.getString("paper_presets", "[]"))
        (0 until values.length()).mapNotNull { index ->
            runCatching {
                val value = values.getJSONObject(index)
                val name = value.getString("name").trim()
                require(name.isNotEmpty() && name.length <= 40)
                LabelPaperPreset(name, readPaper(value))
            }.getOrNull()
        }.distinctBy { it.name }.take(MaxPresets)
    }.getOrDefault(emptyList())

    fun save(name: String, paper: M1TestPaperProfile): List<LabelPaperPreset> {
        val title = name.trim()
        require(title.isNotEmpty() && title.length <= 40)
        val presets = load().toMutableList()
        val index = presets.indexOfFirst { it.name == title }
        if (index >= 0) presets[index] = LabelPaperPreset(title, paper)
        else {
            require(presets.size < MaxPresets)
            presets.add(LabelPaperPreset(title, paper))
        }
        persist(presets)
        return presets
    }

    fun delete(name: String): List<LabelPaperPreset> = load().filterNot { it.name == name }.also(::persist)

    fun saveLast(paper: M1TestPaperProfile) {
        preferences.edit().putString("last_paper", writePaper(paper).toString()).apply()
    }

    fun last(): M1TestPaperProfile? = runCatching {
        readPaper(JSONObject(preferences.getString("last_paper", null) ?: return null))
    }.getOrNull()

    private fun persist(presets: List<LabelPaperPreset>) {
        val values = JSONArray()
        presets.forEach { values.put(writePaper(it.paper).put("name", it.name)) }
        preferences.edit().putString("paper_presets", values.toString()).apply()
    }

    private fun writePaper(paper: M1TestPaperProfile) = JSONObject()
        .put("width", paper.widthMm.toDouble()).put("height", paper.heightMm.toDouble())
        .put("rotation", paper.rotationDegrees)
        .put("offsetX", paper.offsetXmm.toDouble()).put("offsetY", paper.offsetYmm.toDouble())

    private fun readPaper(value: JSONObject) = M1TestPaperProfile(
        value.getDouble("width").toFloat(), value.getDouble("height").toFloat(),
        value.getInt("rotation"), value.getDouble("offsetX").toFloat(), value.getDouble("offsetY").toFloat(),
    )

    companion object { const val MaxPresets = 12 }
}
