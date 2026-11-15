package com.componentvault.android.data

import com.componentvault.android.model.ComponentOfficialMetadata
import com.componentvault.android.model.ComponentImportCandidate
import com.componentvault.android.model.ComponentImportSourceType
import com.componentvault.android.model.withOfficialMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BatchJlcModelsTest {
    @Test fun batchCandidateUsesBrandModelWhileParametersRemainInDescription() {
        val metadata = ComponentOfficialMetadata(model = "R0603", brand = "Maker", category = "电阻",
            parameters = mapOf("阻值" to "10kΩ", "精度" to "±1%"))
        val candidate = ComponentImportCandidate(sourceType = ComponentImportSourceType.JlcQr,
            rawPayload = "C1", sourceLabel = "JLC", sku = "C1", name = "R0603", model = "R0603")
            .withOfficialMetadata(metadata)
        assertEquals("Maker R0603", candidate.name)
        val description = metadata.toBatchDescription()
        assertTrue("参数：阻值：10kΩ" in description)
        assertTrue("参数：精度：±1%" in description)
    }
    @Test fun deduplicatesExactRawButKeepsDifferentBagsForSameSku(){
        val first="pc:C70565,qty:10,pdi:A";val second="pc:C70565,qty:20,pdi:B"
        val (draft,duplicates)=BatchJlcDraftCodec.add(BatchJlcDraft(),listOf(first,"  $first  ",second))
        assertEquals(2,draft.rows.size);assertEquals(1,duplicates)
    }
    @Test fun draftJsonRoundTripPreservesStableIdsRawAndSelection(){
        val draft = BatchJlcDraft(rows = listOf(
            BatchJlcRow(raw = "pc:C1,qty:2", selected = false, description = "商品图片：https://assets.lcsc.com/a.png"),
            BatchJlcRow(raw = "lcsc-order:example", purchaseAmount = "8",
                originalOrderAmount = OrderPurchaseAmount("10", OrderAmountBasis.Subtotal)),
        ))
        assertEquals(draft,BatchJlcDraftCodec.decode(BatchJlcDraftCodec.encode(draft)))
    }
    @Test fun batchDescriptionContainsOnlyPublicMetadataNotPackagingPayload(){
        val description=ComponentOfficialMetadata(
            source="lcsc_domestic_web",model="M1",brand="Maker",description="官方 10kΩ 电阻",
            imageUrl="https://assets.lcsc.com/images/a.png",
            parameters=mapOf("阻值" to "10k"),
        ).toBatchDescription()
        assertTrue("型号：M1" in description)
        assertTrue("商品图片：https://assets.lcsc.com/images/a.png" in description)
        assertTrue("参数：阻值：10k" in description)
        assertTrue("官方描述：官方 10kΩ 电阻" in description)
        assertTrue("pc:" !in description)
        assertTrue("订单" !in description)
    }
    @Test fun invalidQuantityIsNotReadyAndAggregateOverflowFails(){
        val invalid=BatchJlcRow(raw="x",status=BatchJlcStatus.Pending,sku="C1",quantityText="0",location="A")
        assertTrue(BatchJlcCommitPlanner.plan(listOf(invalid),emptySet()).isEmpty())
        val rows=listOf(
            BatchJlcRow(raw="a",status=BatchJlcStatus.Ready,sku="C1",quantityText=Int.MAX_VALUE.toString(),location="A"),
            BatchJlcRow(raw="b",status=BatchJlcStatus.Ready,sku="C1",quantityText="1",location="A"),
        )
        assertFailsWith<ArithmeticException>{BatchJlcCommitPlanner.plan(rows,emptySet())}
    }
    @Test fun receiptsExcludeRowsFromReplayPlan(){val row=BatchJlcRow(id="row-1",raw="x",status=BatchJlcStatus.Ready,sku="C1",quantityText="2",location="A");assertTrue(BatchJlcCommitPlanner.plan(listOf(row),setOf("row-1")).isEmpty())}
}
