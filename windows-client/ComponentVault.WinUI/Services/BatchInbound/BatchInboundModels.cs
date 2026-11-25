namespace ComponentVault.WinUI.Services.BatchInbound;

public sealed record BatchScanItem(string ReceiptId, string Raw)
{
    public string? PurchaseAmount { get; init; }
    public string MetadataSku { get; init; } = "";
    public string? SourceWarning { get; init; }
    public bool SourceWarningConfirmed { get; init; }
    public string Status { get; init; } = "captured"; public string Sku { get; init; } = ""; public string QuantityText { get; init; } = ""; public string LocationId { get; init; } = ""; public string Name { get; init; } = ""; public string Category { get; init; } = ""; public string PackageName { get; init; } = ""; public string Description { get; init; } = ""; public string Error { get; init; } = ""; public bool Selected { get; init; } = true;
}
public sealed record JlcParsedLabel(string Sku, int? Quantity, string? PartName, string? Model, string? Manufacturer, string? CustomerCode, string? OrderNumber, string? PackageDetail, string? ParseError);
public sealed record BatchInboundLine(string ReceiptId, string Raw, string Sku, int Quantity, string LocationId, string Name, string Category, string PackageName, string Description, string? PurchaseAmount = null);
internal static class BatchOrderSource
{
    internal static string? FromOrder(string receiptId, string raw)
    {
        const string prefix = "lcsc-order:order-";
        if (!raw.StartsWith(prefix, StringComparison.Ordinal) || raw.Length != prefix.Length + 64)
            return null;
        var digest = raw[prefix.Length..];
        if (!digest.All(c => c is (>= '0' and <= '9') or (>= 'a' and <= 'f')))
            return null;
        // Scans receive random receipt IDs; only the parser's identified order rows match this digest.
        return Guid.TryParse(receiptId, out var receipt) && receipt == new Guid(Convert.FromHexString(digest[..32]))
            ? "lcsc-order:v1:" + digest : null;
    }
}

public sealed record BatchInboundSummary(string Sku, string LocationId, int Quantity, int Packages);
public sealed record BatchInboundCommitRequest(IReadOnlyList<BatchInboundLine> Lines);
