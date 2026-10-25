using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using ComponentVault.WinUI.Services;
using ComponentVault.WinUI.Services.BatchInbound;
using ComponentVault.WinUI.Services.InventoryWrites;
using Microsoft.Data.Sqlite;
using Xunit;
using Xunit.Abstractions;

namespace ComponentVault.WinUI.OrderImportTests;

public sealed class JlcOrderImportTests(ITestOutputHelper output) : IDisposable
{
    private const string Order = "SO26101226525";
    private readonly string _root = Path.Combine(AppContext.BaseDirectory, "test-data", Guid.NewGuid().ToString("N"));
    private static readonly object?[] StatementHeaders = ["订单编号", "商品编号", "商品名称", "商品型号", "封装格式", "订购数量", "品牌", "商品类型", "订单状态"];
    private static readonly object?[] DetailHeaders = ["序号", "商品编号", "品牌", "厂家型号", "封装 ", "商品名称", "订购数量（修改后）", "发货数量"];

    [Fact]
    public void Statement_MergesPurchasedQuantityAndSkipsFeesSummariesAndRow199Note()
    {
        var rows = new object?[199][];
        for (var i = 0; i < rows.Length; i++) rows[i] = [];
        rows[0] = ["立创商城物料明细对账单"];
        rows[1] = StatementHeaders;
        rows[2] = Product(3d);
        rows[3] = [null, null, "价外费用（配送费）"];
        rows[4] = Product(5d);
        rows[5] = ["商品合计金额"];
        rows[198] = ["说明：\n1、未付款取消的订单不会导出"];
        var result = Parse(rows);
        var item = Assert.Single(result.Rows);
        Assert.Equal("C12345", item.Sku);
        Assert.Equal("8", item.QuantityText);
        Assert.Empty(item.Error);
        Assert.Equal(1, result.MergedLines);
        Assert.Equal(1, result.SkippedFees);
        Assert.True(result.SkippedSummaries >= 1);
        Assert.Contains("M-01", item.Name);
        Assert.Contains("Maker", item.Description);
        Assert.Equal("0603", item.PackageName);
        Assert.Equal("电阻器", item.Category);
    }

    [Fact]
    public void Detail_UsesEditedPurchasedQuantityAndFindsHeaderAtRow18()
    {
        var parsed = Detail("10个", shipped: 2d);
        var row = Assert.Single(parsed.Rows);
        Assert.Equal("10", row.QuantityText);
        Assert.Equal("C12345", row.Sku);
        Assert.Equal("0603", row.PackageName);
        Assert.Empty(row.Error);
    }

    [Fact]
    public void BothFormats_ShareAndroidHashAndWindowsReceiptDespiteHeaderOffsets()
    {
        var statement = Assert.Single(Parse(StatementHeaders, Product(10d)).Rows);
        var detail = Assert.Single(Detail("10个").Rows);
        var digest = SHA256.HashData(Encoding.UTF8.GetBytes(Order + "\u001fC12345"));
        Assert.Equal("lcsc-order:order-" + Convert.ToHexString(digest).ToLowerInvariant(), statement.Raw);
        Assert.Equal(new Guid(digest.AsSpan(0, 16)).ToString("D"), statement.ReceiptId);
        Assert.Equal(statement.ReceiptId, detail.ReceiptId);
        Assert.Equal(statement.Raw, detail.Raw);
        Assert.True(Guid.TryParse(statement.ReceiptId, out _));
    }

    [Theory]
    [InlineData("1,200", "1200")]
    [InlineData("1,200个", "1200")]
    [InlineData("12.0", "12")]
    public void Quantity_AcceptsFormattedPositiveIntegers(string quantity, string expected)
    {
        var row = Assert.Single(Parse(StatementHeaders, Product(quantity)).Rows);
        Assert.Empty(row.Error);
        Assert.Equal(expected, row.QuantityText);
    }

    [Theory]
    [InlineData("0")]
    [InlineData("-3")]
    [InlineData("1.5")]
    [InlineData("NaN")]
    [InlineData("2147483648")]
    [InlineData("")]
    [InlineData("abc")]
    public void Quantity_InvalidValuesRemainVisibleAndUnselected(string quantity)
    {
        var result = Parse(StatementHeaders, Product(quantity));
        var row = Assert.Single(result.Rows);
        Assert.NotEmpty(row.Error);
        Assert.False(row.Selected);
        Assert.True(result.InvalidRows > 0);
    }

    [Theory]
    [InlineData(0d)]
    [InlineData(-3d)]
    [InlineData(1.5d)]
    [InlineData(double.NaN)]
    [InlineData(double.PositiveInfinity)]
    [InlineData(2147483648d)]
    public void NumericQuantity_InvalidBiffNumbersRemainVisibleAndUnselected(double quantity)
    {
        var result = Parse(StatementHeaders, Product(quantity));
        var row = Assert.Single(result.Rows);
        Assert.NotEmpty(row.Error);
        Assert.False(row.Selected);
        Assert.Equal(1, result.InvalidRows);
    }

    [Fact]
    public void InvalidQuantity_DoesNotGetHiddenBySameOrderSkuMerge()
    {
        var result = Parse(StatementHeaders, Product(3d), Product("invalid"));
        var row = Assert.Single(result.Rows);
        Assert.NotEmpty(row.Error);
        Assert.False(row.Selected);
    }

    [Fact]
    public void NotePrefix_DoesNotHideMalformedProductRow()
    {
        var result = Parse(StatementHeaders, ["说明：", "", "Missing SKU"]);
        var row = Assert.Single(result.Rows);
        Assert.NotEmpty(row.Error);
        Assert.False(row.Selected);
    }

    [Fact]
    public void InvalidSkuAndMissingOrder_AreIndependentlyFlagged()
    {
        var invalidSku = Product(3d); invalidSku[1] = "12345";
        var skuRow = Assert.Single(Parse(StatementHeaders, invalidSku).Rows);
        Assert.Empty(skuRow.Sku);
        Assert.Contains("商品编号", skuRow.Error);
        Assert.False(skuRow.Selected);
        var orderRow = Assert.Single(Parse(StatementHeaders, Product(3d, order: "")).Rows);
        Assert.Equal("C12345", orderRow.Sku);
        Assert.Contains("订单", orderRow.Error);
        Assert.False(orderRow.Selected);
    }

    [Fact]
    public void DetailWithoutOrderLabel_KeepsDistinctUnidentifiedRowsForReview()
    {
        object?[] first = [1d, "C12345", "Maker", "M-01", "0603", "Example", "3个", 3d];
        object?[] second = [2d, "C12345", "Maker", "M-01", "0603", "Example", "5个", 5d];
        var result = Parse(DetailHeaders, first, second);
        Assert.Equal(2, result.Rows.Count);
        Assert.Equal(2, result.InvalidRows);
        Assert.Equal(2, result.Rows.Select(row => row.ReceiptId).Distinct().Count());
        Assert.All(result.Rows, row => { Assert.StartsWith("lcsc-order:unidentified:", row.Raw); Assert.Contains("订单", row.Error); Assert.False(row.Selected); });
        Assert.Equal(result.Rows.Select(row => row.ReceiptId), Parse(DetailHeaders, first, second).Rows.Select(row => row.ReceiptId));
    }

    [Theory]
    [InlineData("已取消")]
    [InlineData("已退款")]
    public void CancelledAndRefundedOrders_RequireReview(string status)
    {
        var row = Assert.Single(Parse(StatementHeaders, Product(3d, status: status)).Rows);
        Assert.NotEmpty(row.Error);
        Assert.False(row.Selected);
    }

    [Fact]
    public void SameOrderSku_MetadataConflictAndOverflowRequireReview()
    {
        var conflicting = Product(2d); conflicting[3] = "Different-model";
        var conflict = Assert.Single(Parse(StatementHeaders, Product(3d), conflicting).Rows);
        Assert.NotEmpty(conflict.Error);
        Assert.False(conflict.Selected);
        var overflow = Assert.Single(Parse(StatementHeaders, Product((double)int.MaxValue), Product(1d)).Rows);
        Assert.NotEmpty(overflow.Error);
        Assert.False(overflow.Selected);
    }

    [Fact]
    public void DifferentOrderSameSku_RemainsSeparate()
    {
        var rows = Parse(StatementHeaders, Product(3d), Product(5d, order: "SO26101226526")).Rows;
        Assert.Equal(2, rows.Count);
        Assert.Equal(2, rows.Select(row => row.ReceiptId).Distinct().Count());
    }

    [Fact]
    public void Draft_ReimportAcrossFormatsPreservesEditsAndSkipsCommittedOrders()
    {
        var store = DraftStore();
        var first = Assert.Single(Parse(StatementHeaders, Product(10d)).Rows);
        Assert.Equal(1, store.MergeImported([first]).Added);
        var edited = first with { Name = "Manually corrected", QuantityText = "11", LocationId = "A" };
        store.Save([edited]);
        var repeat = store.MergeImported(Detail("10个").Rows);
        Assert.Equal(0, repeat.Added);
        Assert.Equal(1, repeat.Duplicates);
        Assert.Equal(edited, Assert.Single(store.Load()));
        store.Clear();
        var committed = store.MergeImported([first], receipt => receipt == first.ReceiptId);
        Assert.Equal(0, committed.Added);
        Assert.Equal(1, committed.Duplicates);
        Assert.Empty(store.Load());
    }

    [Fact]
    public void Draft_ImportCapacityFailureAndInvalidRowsLeaveExistingFileUnchanged()
    {
        var store = DraftStore();
        store.Save(Enumerable.Range(0, 499).Select(i => new BatchScanItem(Guid.NewGuid().ToString("D"), "bag-" + i)).ToArray());
        var before = File.ReadAllBytes(Path.Combine(_root, "draft.json"));
        var rows = Parse(StatementHeaders, Product(3d), Product(5d, order: "SO26101226526")).Rows;
        Assert.Throws<InvalidOperationException>(() => store.MergeImported(rows));
        Assert.Equal(before, File.ReadAllBytes(Path.Combine(_root, "draft.json")));
        Assert.Throws<InvalidDataException>(() => store.MergeImported([new("not-a-guid", "bad")]));
        Assert.Equal(before, File.ReadAllBytes(Path.Combine(_root, "draft.json")));
        Assert.Equal(1, store.MergeImported([rows[0]]).Added);
        Assert.Equal(1, store.MergeImported([rows[0]]).Duplicates);
        Assert.Equal(500, store.Load().Count);
    }

    [Fact]
    public void ScannerDraft_StillDeduplicatesPackagesAndPreservesDifferentBags()
    {
        var store = DraftStore();
        const string first = "{on:bag-a,pc:C12345,qty:2}";
        const string second = "{on:bag-b,pc:C12345,qty:3}";
        var result = store.Merge([first, "  " + first + "  ", second]);
        Assert.Equal(2, result.Items.Count);
        Assert.Equal(1, result.Duplicates);
        Assert.Equal(new int?[] { 2, 3 }, result.Items.Select(row => JlcQrParser.Parse(row.Raw).Quantity));
        Assert.All(result.Items, row => Assert.True(Guid.TryParse(row.ReceiptId, out _)));
    }

    [Fact]
    public void Receipt_RealSqliteCommitSurvivesNewDraftAndOtherFormatReplay()
    {
        Directory.CreateDirectory(_root);
        var database = new InventoryDatabase(Path.Combine(_root, "inventory.db"), () => DateTimeOffset.UtcNow.ToString("O"));
        database.Initialize();
        var locations = new InventoryLocationWriter(database);
        Assert.True(locations.CreateStorageLocation("A", "A").IsSuccess);
        var writer = new InventoryBatchWriter(database);
        var original = Assert.Single(Parse(StatementHeaders, Product(10d)).Rows);
        var line = new BatchInboundLine(original.ReceiptId, original.Raw, original.Sku, 10, "A", "part", "电阻器", "0603", "keep");
        var first = writer.CommitBatchInbound(new([line]));
        Assert.True(first.IsSuccess, first.Message);
        var repeated = Assert.Single(Detail("10个").Rows);
        var replay = writer.CommitBatchInbound(new([line with { ReceiptId = repeated.ReceiptId, Raw = repeated.Raw }]));
        Assert.True(replay.IsSuccess, replay.Message);
        Assert.True(writer.IsBatchReceiptCommitted(repeated.ReceiptId));
        var draft = DraftStore();
        Assert.Equal(1, draft.MergeImported([repeated], writer.IsBatchReceiptCommitted).Duplicates);
        Assert.Empty(draft.Load());
        using var connection = database.OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT quantity FROM components WHERE sku='C12345'";
        Assert.Equal(10L, command.ExecuteScalar());
        command.CommandText = "SELECT COUNT(*) FROM stock_movements";
        Assert.Equal(1L, command.ExecuteScalar());
    }

    [Fact]
    public void FormatValidation_RejectsNonWorkbookAndHonorsCancellation()
    {
        using var text = new MemoryStream(Encoding.UTF8.GetBytes("not an XLS workbook"));
        Assert.Throws<InvalidDataException>(() => JlcOrderSpreadsheetParser.Parse(text));
        using var cancelled = new CancellationTokenSource();
        cancelled.Cancel();
        using var workbook = LegacyXlsFixture.Create(StatementHeaders, Product(3d));
        Assert.ThrowsAny<OperationCanceledException>(() => JlcOrderSpreadsheetParser.Parse(workbook, cancelled.Token));
    }

    [Fact]
    public void Limits_RejectOversizedFileAndMoreThan500OrderProducts()
    {
        using var oversized = new MemoryStream(new byte[JlcOrderSpreadsheetParser.MaxFileBytes + 1]);
        var sizeError = Assert.Throws<InvalidDataException>(() => JlcOrderSpreadsheetParser.Parse(oversized));
        Assert.Contains("10 MiB", sizeError.Message);
        var rows = new[] { StatementHeaders }.Concat(Enumerable.Range(1, 501).Select(i => new object?[] { "SO" + i, "C1", null, null, null, 1d })).ToArray();
        var rowError = Assert.Throws<InvalidDataException>(() => Parse(rows));
        Assert.Contains("500", rowError.Message);
    }

    [Fact]
    public void PrivateSamples_WhenConfiguredReadBothFormatsWithoutCopyingFiles()
    {
        var statementPath = Environment.GetEnvironmentVariable("LCSC_ORDER_STATEMENT_SAMPLE");
        var detailPath = Environment.GetEnvironmentVariable("LCSC_ORDER_DETAIL_SAMPLE");
        if (string.IsNullOrEmpty(statementPath) || string.IsNullOrEmpty(detailPath))
        {
            output.WriteLine("Private sample smoke omitted: set LCSC_ORDER_STATEMENT_SAMPLE and LCSC_ORDER_DETAIL_SAMPLE.");
            return;
        }
        var statement = JlcOrderSpreadsheetParser.ParseFile(statementPath);
        var detail = JlcOrderSpreadsheetParser.ParseFile(detailPath);
        Assert.NotEmpty(statement.Rows);
        Assert.NotEmpty(detail.Rows);
        foreach (var result in new[] { statement, detail })
        {
            Assert.Equal(0, result.InvalidRows);
            Assert.All(result.Rows, row => { Assert.Empty(row.Error); Assert.True(int.Parse(row.QuantityText, CultureInfo.InvariantCulture) > 0); });
        }
        var byReceipt = statement.Rows.ToDictionary(row => row.ReceiptId);
        Assert.All(detail.Rows, row =>
        {
            Assert.True(byReceipt.TryGetValue(row.ReceiptId, out var matching), "Detail product must have matching order/SKU in statement.");
            Assert.Equal(row.Raw, matching!.Raw);
            Assert.Equal(row.QuantityText, matching.QuantityText);
        });
        output.WriteLine($"Statement: {statement.Rows.Count} order products, {statement.Rows.Select(row => row.Sku).Distinct().Count()} SKUs, {statement.SkippedFees} fees; detail: {detail.Rows.Count} products; all detail receipts matched.");
    }

    private BatchInboundDraftStore DraftStore() => new(Path.Combine(_root, "draft.json"));
    private static object?[] Product(object quantity, string order = Order, string status = "已完成") => [order, "c12345", "Example", "M-01", "0603", quantity, "Maker", "电阻器", status];
    private static JlcOrderSpreadsheetImport Parse(params object?[][] rows)
    {
        using var workbook = LegacyXlsFixture.Create(rows);
        return JlcOrderSpreadsheetParser.Parse(workbook);
    }
    private static JlcOrderSpreadsheetImport Detail(object quantity, object? shipped = null)
    {
        var rows = new object?[19][];
        for (var i = 0; i < rows.Length; i++) rows[i] = [];
        rows[2] = ["订单编号：", Order];
        rows[17] = DetailHeaders;
        rows[18] = [1d, "C12345", "Maker", "M-01", "0603", "Example", quantity, shipped ?? 0d];
        return Parse(rows);
    }
    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        if (Directory.Exists(_root)) Directory.Delete(_root, true);
    }
}
