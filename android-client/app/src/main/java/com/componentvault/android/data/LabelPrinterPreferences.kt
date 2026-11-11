package com.componentvault.android.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class SavedLabelPrinter(
    val address: String,
    val name: String,
    val profileId: String,
    val mediaType: String,
)

internal data class LabelPrintOffset(val xMm: Float, val yMm: Float) {
    init {
        require(xMm in M1TestPaperProfile.OffsetXRange && yMm in M1TestPaperProfile.OffsetYRange)
    }

    companion object {
        val Default = LabelPrintOffset(-2.5f, 0f)
    }
}

internal class LabelPrinterPreferences(private val preferences: SharedPreferences) {
    fun load(): List<SavedLabelPrinter> {
        val entries = runCatching { JSONArray(preferences.getString(Key, null) ?: "[]") }.getOrNull()
            ?: return emptyList()
        return (0 until entries.length()).mapNotNull { index ->
            runCatching {
                val entry = entries.getJSONObject(index)
                SavedLabelPrinter(
                    address = entry.getString("address"),
                    name = entry.optString("name"),
                    profileId = LabelPrinterProfile.fromId(entry.getString("profile")).configurable.id,
                    mediaType = LabelMediaType.fromId(entry.getString("media")).id,
                )
            }.getOrNull()
        }.distinctBy(SavedLabelPrinter::address)
    }

    fun save(printers: List<SavedLabelPrinter>, activeAddress: String, profileId: String, mediaType: String) {
        editPrinters(printers)
            .putString("device_address", activeAddress)
            .putString("printer_profile", profileId)
            .putString("media_type", mediaType)
            .apply()
    }

    fun loadOffset(address: String): LabelPrintOffset = runCatching {
        LabelPrintOffset(
            preferences.getFloat("print_offset_x:$address", LabelPrintOffset.Default.xMm),
            preferences.getFloat("print_offset_y:$address", LabelPrintOffset.Default.yMm),
        )
    }.getOrDefault(LabelPrintOffset.Default)

    fun saveOffset(address: String, offset: LabelPrintOffset) {
        preferences.edit()
            .putFloat("print_offset_x:$address", offset.xMm)
            .putFloat("print_offset_y:$address", offset.yMm)
            .apply()
    }

    fun forget(address: String, remaining: List<SavedLabelPrinter>) {
        val editor = editPrinters(remaining)
            .remove("print_offset_x:$address")
            .remove("print_offset_y:$address")
        if (preferences.getString("device_address", null) == address) {
            editor.remove("device_address").remove("printer_profile").remove("media_type")
        }
        editor.apply()
    }

    private fun editPrinters(printers: List<SavedLabelPrinter>): SharedPreferences.Editor {
        val entries = JSONArray()
        for (printer in printers) {
            entries.put(JSONObject().apply {
                put("address", printer.address)
                put("name", printer.name)
                put("profile", printer.profileId)
                put("media", printer.mediaType)
            })
        }
        return preferences.edit().putString(Key, entries.toString())
    }

    private companion object {
        const val Key = "saved_label_printers"
    }
}
