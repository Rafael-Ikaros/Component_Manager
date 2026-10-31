package com.componentvault.android.data

import android.app.Application
import android.content.Context
import com.componentvault.android.model.AppLanguage
import com.componentvault.android.model.ComponentOfficialMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LcscCatalogCacheTest {
    private lateinit var context: Application

    @Before fun resetPreferences() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("component_vault_sync", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun scopedCacheRoundTripsDescriptionParametersAndDatasheet() {
        val repository = InventoryRepository(context)
        val metadata = ComponentOfficialMetadata(
            source = "lceda_public_library",
            sku = "C70565",
            name = "X3225",
            description = "中文长描述",
            model = "X3225",
            parameters = linkedMapOf("频率" to "12MHz", "负载电容" to "12pF"),
            datasheetUrl = "https://atta.szlcsc.com/public.pdf",
            referencePrice = com.componentvault.android.model.ReferencePriceSnapshot.create("0.1250", "USD", 5, "https://www.lcsc.com/product-detail/C70565.html", "2026-11-01T00:00:00Z"),
        )

        repository.cacheLookup(metadata, "lceda_public_library")
        val cached = repository.readCachedLookup("C70565", "", "lceda_public_library")

        assertEquals(metadata.description, cached?.description)
        assertEquals(metadata.parameters, cached?.parameters)
        assertEquals(metadata.datasheetUrl, cached?.datasheetUrl)
        assertEquals(metadata.referencePrice, cached?.referencePrice)
    }

    @Test fun initializationRemovesLegacyMalformedAndExpiredKeysButKeepsValidScopedEntry() {
        val preferences = context.getSharedPreferences("component_vault_sync", Context.MODE_PRIVATE)
        val valid = JSONObject().put("fetched_at", System.currentTimeMillis()).put("sku", "C1").toString()
        val expired = JSONObject().put("fetched_at", 1L).put("sku", "C2").toString()
        preferences.edit()
            .putString("lcsc_lookup_cache:sku:c1", valid)
            .putString("lcsc_lookup_cache:lcsc_domestic_web:sku:c1", valid)
            .putString("lcsc_lookup_cache:lcsc_public_web:sku:c2", expired)
            .putString("lcsc_lookup_cache:lcsc_public_web:sku:c3", "not-json")
            .commit()

        InventoryRepository(context)

        assertFalse(preferences.contains("lcsc_lookup_cache:sku:c1"))
        assertTrue(preferences.contains("lcsc_lookup_cache:lcsc_domestic_web:sku:c1"))
        assertFalse(preferences.contains("lcsc_lookup_cache:lcsc_public_web:sku:c2"))
        assertFalse(preferences.contains("lcsc_lookup_cache:lcsc_public_web:sku:c3"))
    }

    @Test fun changingLanguageClearsOnlyLookupCachePrefix() {
        val repository = InventoryRepository(context)
        val preferences = context.getSharedPreferences("component_vault_sync", Context.MODE_PRIVATE)
        repository.cacheLookup(
            ComponentOfficialMetadata(source = "lcsc_domestic_web", sku = "C9", name = "part"),
            "lcsc_domestic_web",
        )
        preferences.edit().putString("unrelated_cache", "keep").commit()

        repository.saveAppPreferences(repository.loadAppPreferences().copy(appLanguage = AppLanguage.English))

        assertNull(repository.readCachedLookup("C9", "", "lcsc_domestic_web"))
        assertEquals("keep", preferences.getString("unrelated_cache", null))
    }
}
