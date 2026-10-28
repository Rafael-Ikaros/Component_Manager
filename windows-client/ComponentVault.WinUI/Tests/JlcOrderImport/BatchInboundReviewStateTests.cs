using ComponentVault.WinUI.Services.BatchInbound;
using Xunit;
using ComponentVault.WinUI.Views;

namespace ComponentVault.WinUI.OrderImportTests;

public sealed class BatchInboundReviewStateTests
{
    [Fact]
    public void EditingOrderSku_ImmediatelyClearsOldMetadataBeforeSaveOrManualConfirmation()
    {
        var row = new BatchInboundEditableRow
        {
            ReceiptId = Guid.NewGuid().ToString(), Raw = "lcsc-order:test", Sku = "C123",
            Name = "Part A", Category = "resistor", PackageName = "0603", Description = "A details",
            SourceWarning = "refund", SourceWarningConfirmed = true, QuantityInput = 10, LocationId = "drawer",
        };
        row.Sku = "C456";
        Assert.Empty(row.Name);
        Assert.Empty(row.Category);
        Assert.Empty(row.PackageName);
        Assert.Empty(row.Description);
        Assert.False(row.SourceWarningConfirmed);
        Assert.Equal("refund", row.SourceWarning);
        Assert.Equal(10, row.Quantity);
        Assert.Equal("drawer", row.LocationId);
    }

    [Theory]
    [InlineData("退款订单，请人工确认。")]
    [InlineData("缺少订单编号，请人工确认。")]
    [InlineData("商品资料冲突，请人工确认。")]
    public void RetryAndDraftRoundTrip_DoNotAcknowledgeSourceWarnings(string warning)
    {
        var source = Order() with { Error = warning };
        var captured = BatchInboundReviewState.Restore(source);
        var pending = RoundTrip(captured with { Status = "pending", Error = "查询失败" });
        var retrySuccess = BatchInboundReviewState.Restore(pending with { Error = "" });

        Assert.Equal(warning, retrySuccess.SourceWarning);
        Assert.Equal(warning, BatchInboundReviewState.UnconfirmedWarning(retrySuccess.SourceWarning!, retrySuccess.SourceWarningConfirmed));
        var confirmed = RoundTrip(retrySuccess with { SourceWarningConfirmed = true, Status = "ready" });
        Assert.Empty(BatchInboundReviewState.UnconfirmedWarning(confirmed.SourceWarning!, confirmed.SourceWarningConfirmed));
        Assert.Equal(warning, confirmed.SourceWarning);
    }

    [Fact]
    public void ChangedSkuAfterSave_ClearsOldMetadataAndAcknowledgement()
    {
        var item = RoundTrip(Order() with
        {
            MetadataSku = "C123", Sku = "C456", SourceWarning = "退款", SourceWarningConfirmed = true,
        });
        var restored = BatchInboundReviewState.Restore(item);
        Assert.Equal("C456", restored.Sku);
        Assert.Equal("C456", restored.MetadataSku);
        Assert.Empty(restored.Name);
        Assert.Empty(restored.Category);
        Assert.Empty(restored.PackageName);
        Assert.Empty(restored.Description);
        Assert.False(restored.SourceWarningConfirmed);
        Assert.Equal("退款", restored.SourceWarning);
        Assert.Equal(item.QuantityText, restored.QuantityText);
        Assert.Equal(item.LocationId, restored.LocationId);
    }

    [Fact]
    public void LegacyDraft_PreservesDataAndPendingWarning()
    {
        var legacy = Order() with { Status = "pending", Error = "退款待确认" };
        var restored = BatchInboundReviewState.Restore(legacy);
        Assert.Equal(legacy.Name, restored.Name);
        Assert.Equal(legacy.Category, restored.Category);
        Assert.Equal(legacy.Description, restored.Description);
        Assert.Equal("退款待确认", restored.SourceWarning);
        Assert.False(restored.SourceWarningConfirmed);
        Assert.Equal(legacy.Sku, restored.MetadataSku);
    }

    [Fact]
    public void QrDraft_IsUnchanged() => Assert.Equal(new BatchScanItem("receipt", "QR") { Error = "parse" },
        BatchInboundReviewState.Restore(new BatchScanItem("receipt", "QR") { Error = "parse" }));

    [Theory]
    [InlineData("C123", " c123 ", false)]
    [InlineData("C123", "C456", true)]
    [InlineData("C123", "", true)]
    public void SkuComparison_TracksProductIdentity(string previous, string current, bool changed) =>
        Assert.Equal(changed, BatchInboundReviewState.SkuChanged(previous, current));

    private static BatchScanItem Order() => new(Guid.NewGuid().ToString(), "lcsc-order:order-test")
    {
        Sku = "C123", Name = "Old part", Category = "resistor", PackageName = "0603",
        Description = "old description", QuantityText = "10", LocationId = "drawer",
    };

    private static BatchScanItem RoundTrip(BatchScanItem item)
    {
        var path = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString(), "draft.json");
        try
        {
            var store = new BatchInboundDraftStore(path);
            store.Save([item]);
            return Assert.Single(store.Load());
        }
        finally { Directory.Delete(Path.GetDirectoryName(path)!, true); }
    }
}
