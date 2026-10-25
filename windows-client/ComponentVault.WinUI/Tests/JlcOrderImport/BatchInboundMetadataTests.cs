using ComponentVault.WinUI.Services.BatchInbound;
using Xunit;

namespace ComponentVault.WinUI.OrderImportTests;

public sealed class BatchInboundMetadataTests
{
    [Theory]
    [InlineData("")]
    [InlineData(" ")]
    [InlineData("\t\r\n")]
    [InlineData("\u3000")]
    public void RequiredFields_RejectEachBlankRequiredFieldButAllowBlankDescription(string blank)
    {
        var complete = new BatchInboundMetadata("Table name", "电阻器", "0603", blank);

        Assert.True(complete.HasRequiredFields);
        Assert.False((complete with { Name = blank }).HasRequiredFields);
        Assert.False((complete with { Category = blank }).HasRequiredFields);
        Assert.False((complete with { PackageName = blank }).HasRequiredFields);
    }

    [Fact]
    public void FillMissingFrom_PreservesEveryExistingTableOrManualValue()
    {
        var edited = new BatchInboundMetadata(" Manual name ", "Manual category", "Manual package", "Manual description");
        var fallback = new BatchInboundMetadata("Remote name", "Remote category", "Remote package", "Remote description");

        Assert.Equal(edited, edited.FillMissingFrom(fallback));
    }

    [Theory]
    [InlineData("")]
    [InlineData(" ")]
    [InlineData("\t\r\n")]
    [InlineData("\u3000")]
    public void FillMissingFrom_FillsEachBlankFieldAndPreservesTheSource(string blank)
    {
        var fallback = new BatchInboundMetadata("Fallback name", "Fallback category", "Fallback package", "Fallback description");
        var source = new BatchInboundMetadata("Table name", "Table category", "Table package", "Table description");

        Assert.Equal(source with { Name = fallback.Name }, (source with { Name = blank }).FillMissingFrom(fallback));
        Assert.Equal(source with { Category = fallback.Category }, (source with { Category = blank }).FillMissingFrom(fallback));
        Assert.Equal(source with { PackageName = fallback.PackageName }, (source with { PackageName = blank }).FillMissingFrom(fallback));
        Assert.Equal(source with { Description = fallback.Description }, (source with { Description = blank }).FillMissingFrom(fallback));
        Assert.Equal(fallback, new BatchInboundMetadata(blank, blank, blank, blank).FillMissingFrom(fallback));
        Assert.Equal("Table name", source.Name);
        Assert.Equal("Table description", source.Description);
    }

    [Fact]
    public void LocalThenRemoteFallback_OnlyFillsFieldsStillMissingAfterEachStep()
    {
        var table = new BatchInboundMetadata("Table name", " ", "", "Manual description");
        var local = new BatchInboundMetadata("Local name", "\t", "Local package", "Local description");
        var remote = new BatchInboundMetadata("Remote name", "Remote category", "Remote package", "Remote description");

        var afterLocal = table.FillMissingFrom(local);
        Assert.False(afterLocal.HasRequiredFields);
        Assert.Equal("Table name", afterLocal.Name);
        Assert.Equal("Local package", afterLocal.PackageName);
        Assert.Equal("Manual description", afterLocal.Description);

        var afterRemote = afterLocal.FillMissingFrom(remote);
        Assert.True(afterRemote.HasRequiredFields);
        Assert.Equal(new BatchInboundMetadata("Table name", "Remote category", "Local package", "Manual description"), afterRemote);
    }

    [Fact]
    public void UnavailableRemoteMetadata_PreservesLocalProgressAndExistingDraftFields()
    {
        var table = new BatchInboundMetadata("Manual name", "", "", "Table description");
        var afterLocal = table.FillMissingFrom(new("Local name", "Local category", "", "Local description"));

        Assert.Equal(new BatchInboundMetadata("Manual name", "Local category", "", "Table description"), afterLocal);
        Assert.False(afterLocal.HasRequiredFields);
        Assert.Equal(afterLocal, afterLocal.FillMissingFrom(new("", "", "", "")));
    }

    [Fact]
    public void StatementWorkbook_ContainsRequiredMetadataEvenWithoutLocation()
    {
        using var workbook = LegacyXlsFixture.Create(
            ["订单编号", "商品编号", "商品名称", "商品型号", "封装格式", "订购数量", "品牌", "商品类型", "订单状态"],
            ["SO26101226525", "C12345", "Table name", "M-01", "0603", 10d, "Maker", "电阻器", "已完成"]);
        var row = Assert.Single(JlcOrderSpreadsheetParser.Parse(workbook).Rows);
        var metadata = Metadata(row);

        Assert.Empty(row.Error);
        Assert.True(string.IsNullOrWhiteSpace(row.LocationId));
        Assert.True(metadata.HasRequiredFields);
        Assert.Equal("Maker M-01", metadata.Name);
        Assert.Contains("Table name", metadata.Description);
        Assert.Contains("Maker", metadata.Description);
        // Automatic product lookup still preserves complete spreadsheet fields; location is reviewed separately.
        Assert.Equal(metadata, metadata.FillMissingFrom(new("Remote name", "Remote category", "Remote package", "Remote description")));
    }

    [Fact]
    public void DetailWorkbook_LocalCategoryCompletesMetadataAndPreservesSpreadsheetFields()
    {
        var row = DetailRow();
        var table = Metadata(row);

        Assert.False(table.HasRequiredFields);
        Assert.Empty(table.Category);
        var completed = table.FillMissingFrom(new("Local name", "电阻器", "Local package", "Local description"));

        Assert.True(completed.HasRequiredFields);
        Assert.Equal("电阻器", completed.Category);
        Assert.Equal(table.Name, completed.Name);
        Assert.Equal(table.PackageName, completed.PackageName);
        Assert.Equal(table.Description, completed.Description);
    }

    [Fact]
    public void DetailWorkbook_IncompleteLocalMetadataAllowsRemoteCategoryWithoutOverwritingDraft()
    {
        var table = Metadata(DetailRow()) with { Name = "Manually corrected name", Description = "Manually corrected description" };
        var afterLocal = table.FillMissingFrom(new("Local name", "", "Local package", "Local description"));

        Assert.False(afterLocal.HasRequiredFields);
        var completed = afterLocal.FillMissingFrom(new("Remote name", "电阻器", "Remote package", "Remote description"));

        Assert.True(completed.HasRequiredFields);
        Assert.Equal(table with { Category = "电阻器" }, completed);
    }

    private static BatchInboundMetadata Metadata(BatchScanItem row) => new(row.Name, row.Category, row.PackageName, row.Description);

    private static BatchScanItem DetailRow()
    {
        var rows = new object?[19][];
        for (var index = 0; index < rows.Length; index++) rows[index] = [];
        rows[2] = ["订单编号：", "SO26101226525"];
        rows[17] = ["序号", "商品编号", "品牌", "厂家型号", "封装 ", "商品名称", "订购数量（修改后）", "发货数量"];
        rows[18] = [1d, "C12345", "Maker", "M-01", "0603", "Table name", "10个", 10d];
        using var workbook = LegacyXlsFixture.Create(rows);
        var row = Assert.Single(JlcOrderSpreadsheetParser.Parse(workbook).Rows);
        Assert.Empty(row.Error);
        return row;
    }
}
