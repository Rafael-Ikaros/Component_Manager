using ComponentVault.WinUI.Localization;
using ComponentVault.WinUI.Services.Catalog;
using ComponentVault.WinUI.Services;
using System.Globalization;

namespace ComponentVault.WinUI.Models;

public sealed class ComponentRecord
{
    public string? InventoryUnitCost { get; init; }
    public string InventoryCostLabel => InventoryUnitCost is null ? "库存成本未知" : $"库存均价 ¥{InventoryUnitCost} / 件 · 总成本 ¥{InventoryCost.Multiply(InventoryUnitCost,Quantity)}";
    public required string Id { get; init; }
    public required string Sku { get; init; }
    public required string Name { get; init; }
    public required string Category { get; init; }
    public required string PackageName { get; init; }
    public required string Location { get; init; }
    // Presentation only; Location remains the persisted location ID.
    public string? LocationName { get; init; }
    public string DisplayLocation => LocationName ?? Location;
    public required string Description { get; init; }
    public required int Quantity { get; init; }
    public required int MinStock { get; init; }
    public required string UpdatedAt { get; init; }
    public required bool Deleted { get; init; }
    public long CumulativeOutboundQuantity { get; init; }
    public IReadOnlyList<ComponentAllocationRecord> Allocations { get; init; } = [];
    public IReadOnlyList<ComponentAllocationRecord> DisplayAllocations
    {
        get
        {
            var stocked = Allocations.Where(allocation => allocation.Quantity > 0).ToArray();
            return stocked.Length > 0
                ? stocked
                : [new ComponentAllocationRecord(Id, Location, 0) { LocationName = DisplayLocation }];
        }
    }
    public string? BaseUpdatedAt { get; init; }
    public bool IsBatchSelected { get; set; }

    public bool IsLowStock => !Deleted && Quantity <= MinStock;

    public long StatisticalTotal => checked((long)Quantity + CumulativeOutboundQuantity);

    public double OutboundRatio => StatisticalTotal <= 0
        ? 0
        : Math.Clamp((double)CumulativeOutboundQuantity / StatisticalTotal, 0, 1);

    public string Status => IsLowStock ? "低库存" : "库存正常";

    public string? ProductImageUrl
    {
        get
        {
            var local = PrivateProductImageStore.Resolve(
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ComponentVault", "images"), Description);
            if (local is not null) return local;
            if (PrivateProductImageStore.KeyFromDescription(Description) is not null) return null;
            var value = Description.Split(['；', ';', '\r', '\n'], StringSplitOptions.RemoveEmptyEntries)
                .Select(note => note.Trim()).FirstOrDefault(note => note.StartsWith("商品图片：", StringComparison.Ordinal))?
                .Substring("商品图片：".Length).Trim();
            return value is not null ? LcscPublicCatalog.TrustedImageUrl(value) ?? LcscPublicCatalog.CachedImageUrl(Sku) : LcscPublicCatalog.CachedImageUrl(Sku);
        }
    }

    public string PhotoStatusHint => PrivateProductImageStore.KeyFromDescription(Description) is not null && PrivateProductImageStore.Resolve(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ComponentVault", "images"), Description) is null ? "照片待下载，请同步" : "";

    public string DisplayCategory => CategoryDisplay.Localize(Category);

    public string DisplaySpecifications => OfficialSpecifications.FromDescription(Description, Category);

    public IReadOnlyList<SpecificationField> DisplaySpecificationFields => OfficialSpecifications.Parse(Description, Category);

    public string DisplayUpdatedAt => FormatLocalDateTime(UpdatedAt);

    private static string FormatLocalDateTime(string value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return "—";
        }

        return DateTimeOffset.TryParse(
            value,
            CultureInfo.InvariantCulture,
            DateTimeStyles.AllowWhiteSpaces | DateTimeStyles.AssumeUniversal,
            out var timestamp
        )
            ? timestamp.ToLocalTime().ToString("g", CultureInfo.CurrentCulture)
            : value;
    }
}
