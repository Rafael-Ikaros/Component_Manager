package com.componentvault.android.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse

class EasyEdaCatalogTest {
    // Selected public fields from pro.lceda.cn's 2026-11-01 anonymous C70565 response.
    private val response = """{"code":200,"result":{"productList":[
      {"code":"C705650","model":"WRONG"},
      {"code":"C70565","id":71663,"model":"X322512MOB4SI","standard":"SMD3225-4P","catalogName":"无源晶振",
       "image":"https://alimg.szlcsc.com/photo.jpg<$>https://alimg.szlcsc.com/second.jpg","pdfFileUrl":"/upload/public/pdf/source/test.pdf",
       "isShowPrice":true,"priceList":[{"startNumber":50,"price":0.4639},{"startNumber":5,"price":0.5803}],
       "device_info":{"attributes":{"Manufacturer":"YXC(扬兴晶振)","Value":"12MHz","Load Capacitance":"12pF","Symbol":"internal-id"},
       "tags":{"parent_tag":{"name_cn":"振荡器/谐振器"},"child_tag":{"name_cn":"无源晶振"}}}}
    ]}}"""

    @Test fun exactCodeChineseMetadataAndDomesticYuanQuote() {
        val metadata = requireNotNull(EasyEdaCatalog.parse("C70565", response))
        assertEquals("X322512MOB4SI", metadata.model)
        assertEquals("无源晶振", metadata.category)
        assertEquals("12pF", metadata.parameters["Load Capacitance"])
        assertFalse(metadata.parameters.containsKey("Symbol"))
        assertEquals("lceda_public_library", metadata.source)
        assertEquals("0.5803", metadata.referencePrice?.unitPrice)
        assertEquals("CNY", metadata.referencePrice?.currency)
        assertEquals(5, metadata.referencePrice?.minimumQuantity)
        assertEquals("https://item.szlcsc.com/71663.html", metadata.referencePrice?.sourceUrl)
        assertEquals("https://alimg.szlcsc.com/photo.jpg", metadata.imageUrl)
        assertEquals("https://atta.szlcsc.com/upload/public/pdf/source/test.pdf", metadata.datasheetUrl)
        assertNull(EasyEdaCatalog.parse("C7056", response))
    }

    @Test fun searchUsesDomesticCodeAndMissingOrHiddenPriceDoesNotBreakMetadata() {
        val products = EasyEdaCatalog.parseSearch(response)
        assertEquals(listOf("C705650", "C70565"), products.map { it.metadata.sku })
        val missingPrice = requireNotNull(EasyEdaCatalog.parse("C70565", response.replace("priceList", "unused")))
        assertEquals("X322512MOB4SI", missingPrice.model)
        assertNull(missingPrice.referencePrice)
        assertNull(EasyEdaCatalog.parse("C70565", response.replace("\"isShowPrice\":true", "\"isShowPrice\":false"))?.referencePrice)
        assertEquals("https://so.szlcsc.com/global.html?k=C70565", EasyEdaCatalog.parse("C70565", response.replace("\"id\":71663,", ""))?.referencePrice?.sourceUrl)
    }

    @Test fun onlyExplicitOfferPriceAndCurrencyCreateReferencePrice() {
        fun page(offer: String) = """<script type="application/ld+json">{"@type":"Product","sku":"C1","offers":$offer}</script>"""
        val price = LcscPublicCatalog.parsePage("C1", page("""{"@type":"Offer","price":"0.1200","priceCurrency":"USD","eligibleQuantity":{"minValue":5}}"""))?.referencePrice
        assertEquals("0.12", price?.unitPrice)
        assertEquals(5, price?.minimumQuantity)
        assertNull(LcscPublicCatalog.parsePage("C1", page("""{"@type":"AggregateOffer","lowPrice":"0.1","priceCurrency":"USD"}"""))?.referencePrice)
        assertNull(LcscPublicCatalog.parsePage("C1", page("""{"price":"0.1"}"""))?.referencePrice)
    }

    @Test fun internationalKeywordsUseInternationalSchemaAndSourceOnly() {
        val response = """{"code":0,"result":{"productList":[{"number":"C70565","mpn":"X322512MOB4SI","package":"SMD3225-4P","manufacturer":"YXC","price":[[5,"0.0959","0.0959"]],"device_info":{"attributes":{"Value":"12MHz","Symbol":"internal"},"tags":{"child_tag":{"name":"Crystals","name_cn":"无源晶振"}}}}]}}"""
        val metadata = EasyEdaCatalog.parseInternationalSearch(response).single().metadata
        assertEquals("easyeda_public_library", metadata.source)
        assertEquals("X322512MOB4SI", metadata.model)
        assertEquals("SMD3225-4P", metadata.packageName)
        assertEquals("Crystals", metadata.category)
        assertEquals("12MHz", metadata.parameters["Value"])
        assertFalse(metadata.parameters.containsKey("Symbol"))
        assertNull(metadata.referencePrice)
    }
}
