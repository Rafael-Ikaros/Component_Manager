package com.componentvault.android.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.componentvault.android.model.withOfficialMetadata
import java.io.IOException

class LcscDomesticCatalogTest {
    private fun page(productCode: String = "C70565", productId: String = "123456") = """
        <html><script id="__NEXT_DATA__" type="application/json">{
          "props":{"pageProps":{"soData":{"searchResult":{"productRecordList":[{
            "lightCatalogName":"晶体/谐振器/晶振",
            "lightProductName":"YXC 12MHz 晶体",
            "lightProductModel":"X322512MOB4SI",
            "lightBrandName":"扬兴科技",
            "lightStandard":"SMD3225-4P",
            "paramLinkedMap":{"频率":"12MHz","负载电容":"12pF"},
            "productVO":{"productCode":"$productCode","productId":"$productId",
              "productModel":"X322512MOB4SI","breviaryImageUrl":"https://img.szlcsc.com/test.jpg",
              "fileTypeVOList":[{"detailVOList":[{"fileUrl":"/upload/public.pdf"}]}]}
          }]}}}}}
        }</script></html>
    """.trimIndent()

    @Test fun parsesChineseSearchCandidateParametersAndRealProductIdUrl() {
        val product = LcscDomesticCatalog.parseSearchPage(page()).single()
        assertEquals("C70565", product.metadata.sku)
        assertEquals("X322512MOB4SI", product.metadata.name)
        assertEquals("YXC 12MHz 晶体", product.metadata.description)
        assertEquals("晶体/谐振器/晶振", product.metadata.category)
        assertEquals("扬兴科技", product.metadata.brand)
        assertEquals("SMD3225-4P", product.metadata.packageName)
        assertEquals("12MHz", product.parameters["频率"])
        assertEquals("https://item.szlcsc.com/123456.html", product.metadata.officialUrl)
        assertEquals("https://atta.szlcsc.com/upload/public.pdf", product.datasheetUrl)
        assertEquals("https://img.szlcsc.com/test.jpg", product.metadata.imageUrl)
        val candidate = JlcImportParser.parseQr("{pc:C70565,qty:25}")
            .withOfficialMetadata(product.metadata)
        assertEquals(25, candidate.suggestedQuantity)
        assertEquals("扬兴科技 X322512MOB4SI", candidate.name)
        assertTrue(candidate.notes.contains("官方描述：YXC 12MHz 晶体"))
        assertTrue(candidate.notes.contains("参数：频率：12MHz"))
        assertTrue(candidate.notes.contains("数据手册：https://atta.szlcsc.com/upload/public.pdf"))
    }

    @Test fun exactLookupRequiresTheFullCNumber() {
        val results = LcscDomesticCatalog.parseSearchPage(page("C30926")) +
            LcscDomesticCatalog.parseSearchPage(page())
        assertEquals("C70565", LcscDomesticCatalog.exactMatch("c70565", results)?.metadata?.sku)
        assertNull(LcscDomesticCatalog.exactMatch("C1", results))
        assertNull(LcscDomesticCatalog.exactMatch("70565", results))
    }

    @Test fun rejectsInvalidProductIdsAndUntrustedAssets() {
        assertTrue(LcscDomesticCatalog.parseSearchPage(page(productId = "../other")).isEmpty())
        val product = LcscDomesticCatalog.parseSearchPage(
            page().replace("https://img.szlcsc.com/test.jpg", "https://evil.example/test.jpg"),
        ).single()
        assertNull(product.metadata.imageUrl)
    }

    @Test fun reportsVerificationPagesInsteadOfBypassingThem() {
        assertFailsWith<LcscDomesticBlockedException> {
            LcscDomesticCatalog.parseSearchPage("<script>var _xvasu='challenge';</script>")
        }
    }

    @Test fun validProductDataWinsEvenWhenDescriptionContainsSecurityText() {
        assertEquals("C70565", LcscDomesticCatalog.parseSearchPage(page().replace("YXC 12MHz 晶体", "Security verification part")).single().metadata.sku)
    }

    @Test fun cooldownSkipsOtherSkusThenExpiresAndManualRetryClearsIt() {
        var now = 1_000L
        val gate = LcscDomesticCooldownGate { now }
        gate.record(LcscDomesticGateReason.Blocked)
        assertEquals(LcscDomesticGateReason.Blocked, gate.current())
        now += 120_000
        assertNull(gate.current())
        gate.record(LcscDomesticGateReason.Network)
        gate.clear()
        assertNull(gate.current())
    }

    @Test fun httpStatusClassificationDistinguishesSuccessChallengePlainForbiddenAndRateLimit() {
        assertNull(classifyDomesticHttpResponse(203, "<html>normal</html>", null))
        assertEquals(LcscDomesticHttpFailure.Blocked, classifyDomesticHttpResponse(403, "var _xvasu='x'", null))
        assertEquals(LcscDomesticHttpFailure.Http(403), classifyDomesticHttpResponse(403, "Forbidden", null))
        assertEquals(LcscDomesticHttpFailure.RateLimited(600), classifyDomesticHttpResponse(429, "busy", "999"))
    }

    @Test fun missingNextDataAndSchemaDriftAreErrorsInsteadOfEmptyResults() {
        assertFailsWith<IOException> { LcscDomesticCatalog.parseSearchPage("<html></html>") }
        assertFailsWith<IOException> {
            LcscDomesticCatalog.parseSearchPage("<script id=\"__NEXT_DATA__\" type=\"application/json\">{\"props\":{}}</script>")
        }
    }

}
