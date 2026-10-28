namespace ComponentVault.WinUI.Services.BatchInbound;

public static class BatchInboundReviewState
{
    public static bool IsOrder(string raw) => raw.StartsWith("lcsc-order:", StringComparison.Ordinal);

    // Legacy drafts stored both source warnings and lookup failures in Error. Keep that
    // evidence until the user confirms it; never discard existing draft metadata.
    public static BatchScanItem Restore(BatchScanItem item)
    {
        if (!IsOrder(item.Raw)) return item;
        var warning = item.SourceWarning ?? item.Error;
        var metadataSku = string.IsNullOrWhiteSpace(item.MetadataSku) ? item.Sku : item.MetadataSku;
        var changed = SkuChanged(metadataSku, item.Sku);
        return item with
        {
            MetadataSku = item.Sku,
            SourceWarning = warning,
            SourceWarningConfirmed = !changed && item.SourceWarningConfirmed,
            Name = changed ? "" : item.Name,
            Category = changed ? "" : item.Category,
            PackageName = changed ? "" : item.PackageName,
            Description = changed ? "" : item.Description,
        };
    }

    public static bool SkuChanged(string previous, string current) =>
        !previous.Trim().Equals(current.Trim(), StringComparison.OrdinalIgnoreCase);

    public static string UnconfirmedWarning(string warning, bool confirmed) => confirmed ? "" : warning;
}
