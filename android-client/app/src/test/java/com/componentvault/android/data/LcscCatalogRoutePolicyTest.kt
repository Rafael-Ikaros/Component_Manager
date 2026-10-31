package com.componentvault.android.data

import com.componentvault.android.model.AppLanguage
import com.componentvault.android.model.ComponentOfficialMetadata
import java.io.IOException
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LcscCatalogRoutePolicyTest {
    private val internationalPage = """<script type="application/ld+json">{"@type":"Product","sku":"C88001","mpn":"EN-MODEL","offers":{"@type":"Offer","price":"0.125","priceCurrency":"USD"}}</script>"""
    private val eda = ComponentOfficialMetadata(source = "lceda_public_library", sku = "C88001", name = "中文型号", model = "EDA-MODEL", category = "无源晶振")

    @Test fun languageRoutesNeverIncludeTheOtherRegion() {
        assertEquals(LcscCatalogSource.Eda, LcscCatalogRoutePolicy.preferredSource(AppLanguage.ZhCn))
        assertEquals(LcscCatalogSource.Eda, LcscCatalogRoutePolicy.preferredSource(Locale.SIMPLIFIED_CHINESE))
        assertEquals(listOf(LcscCatalogSource.Eda), LcscCatalogRoutePolicy.order(LcscCatalogSource.Eda))
        assertEquals(listOf(LcscCatalogSource.International), LcscCatalogRoutePolicy.order(LcscCatalogSource.International))
        assertEquals(listOf(LcscCatalogSource.Eda), LcscCatalogRoutePolicy.order(LcscCatalogSource.Domestic))
    }

    @Test fun chineseMissingPriceNeverCallsInternational() {
        var internationalCalls = 0
        val result = LcscCombinedLookup(
            international = LcscPublicLookup(fetch = { internationalCalls++; internationalPage }),
            edaLookup = { eda },
        ).lookupWithRoute("C88001", LcscCatalogSource.Eda)
        assertEquals("无源晶振", result.metadata?.category)
        assertNull(result.metadata?.referencePrice)
        assertEquals(0, internationalCalls)
        assertFalse(result.usedFallback)
    }

    @Test fun chineseFailureNeverFallsBackToInternational() {
        var internationalCalls = 0
        val result = LcscCombinedLookup(
            international = LcscPublicLookup(fetch = { internationalCalls++; internationalPage }),
            edaLookup = { throw IOException("offline") },
        ).lookupWithRoute("C88001", LcscCatalogSource.Eda)
        assertNull(result.metadata)
        assertEquals(0, internationalCalls)
        assertEquals(LcscCatalogFailureKind.Unreachable, result.attempts.single().failure)
    }

    @Test fun englishSuccessNeverQueriesDomestic() {
        var domesticCalls = 0
        val result = LcscCombinedLookup(
            international = LcscPublicLookup(fetch = { internationalPage }),
            edaLookup = { domesticCalls++; eda },
        ).lookupWithRoute("C88001", LcscCatalogSource.International)
        assertEquals("EN-MODEL", result.metadata?.model)
        assertEquals(0, domesticCalls)
        assertFalse(result.usedFallback)
    }

    @Test fun englishFailureNeverQueriesDomestic() {
        var domesticCalls = 0
        val result = LcscCombinedLookup(
            international = LcscPublicLookup(fetch = { throw IOException("offline") }),
            edaLookup = { domesticCalls++; eda },
        ).lookupWithRoute("C88001", LcscCatalogSource.International)
        assertNull(result.metadata)
        assertEquals(0, domesticCalls)
        assertEquals(LcscCatalogFailureKind.Unreachable, result.attempts.single().failure)
    }

    @Test fun keywordLanguageSelectsOnlyItsOwnProvider() {
        val calls = mutableListOf<String>()
        val lookup = LcscCombinedLookup(
            edaSearch = { calls += "domestic:$it"; listOf(LcscDomesticProduct(eda, emptyMap(), null)) },
            internationalSearch = { calls += "international:$it"; listOf(LcscDomesticProduct(eda.copy(source = "easyeda_public_library"), emptyMap(), null)) },
        )
        assertEquals("lceda_public_library", lookup.searchPublicCatalog("STM32", AppLanguage.ZhCn).single().metadata.source)
        assertEquals("easyeda_public_library", lookup.searchPublicCatalog("STM32", AppLanguage.English).single().metadata.source)
        assertEquals(listOf("domestic:STM32", "international:STM32"), calls)
    }
}
