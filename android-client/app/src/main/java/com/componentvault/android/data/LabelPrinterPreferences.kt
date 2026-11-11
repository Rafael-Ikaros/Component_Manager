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
        val entries = JSONArray()
        for (printer in printers) {
            entries.put(JSONObject().apply {
                put("address", printer.address)
                put("name", printer.name)
                put("profile", printer.profileId)
                put("media", printer.mediaType)
            })
        }
        preferences.edit()
            .putString(Key, entries.toString())
            .putString("device_address", activeAddress)
            .putString("printer_profile", profileId)
            .putString("media_type", mediaType)
            .apply()
    }

    private companion object {
        const val Key = "saved_label_printers"
    }
}
