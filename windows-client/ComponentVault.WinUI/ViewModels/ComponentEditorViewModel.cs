using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services;

namespace ComponentVault.WinUI.ViewModels;

public sealed class ComponentEditorViewModel
{
    private readonly ComponentRecord? _existing;
    private readonly IReadOnlyList<StorageLocationRecord> _locations;
    private string? _catalogSku;

    public ComponentEditorViewModel(
        ComponentRecord? existing,
        IReadOnlyList<StorageLocationRecord> locations,
        string newLocationId)
    {
        _existing = existing;
        _locations = locations;
        NewLocationId = newLocationId;
        Sku = existing?.Sku ?? "";
        Name = existing?.Name ?? "";
        Category = existing?.Category ?? "";
        PackageName = existing?.PackageName ?? "";
        Description = existing?.Description ?? "";
        Quantity = existing?.Quantity ?? 0;
        MinStock = existing?.MinStock ?? 0;
        SelectedLocationId = existing?.Location;
        UseNewLocation = locations.Count == 0;
    }

    public string Sku { get; set; }
    public string Name { get; set; }
    public string Category { get; set; }
    public string PackageName { get; set; }
    public string Description { get; set; }
    public double Quantity { get; set; }
    public double MinStock { get; set; }
    public string? SelectedLocationId { get; set; }
    public bool UseNewLocation { get; set; }
    public string NewLocationId { get; }
    public string NewLocationName { get; set; } = "";

    public ComponentEditSource Source =>
        _catalogSku is not null
            && string.Equals(Sku.Trim(), _catalogSku, StringComparison.OrdinalIgnoreCase)
            ? ComponentEditSource.Catalog : ComponentEditSource.Manual;

    public void RecordCatalogFields(string catalogSku)
    {
        _catalogSku = catalogSku.Trim();
    }

    public string? PurchaseAmount { get; set; }

    public bool TryCreateRequest(out ComponentEditRequest? request, out string error)
    {
        request = null;
        error = "";
        if (!double.IsFinite(Quantity) || !double.IsFinite(MinStock)
            || Quantity > int.MaxValue || MinStock > int.MaxValue)
        {
            error = "数量和最低库存必须是有效数字。";
            return false;
        }
        if (Quantity < 0 || MinStock < 0)
        {
            error = "数量和最低库存不能为负数。";
            return false;
        }
        var locationId = UseNewLocation ? NewLocationId : SelectedLocationId;
        if (string.IsNullOrWhiteSpace(locationId))
        {
            error = "请选择已有库位，或填写新库位名称。";
            return false;
        }
        if (UseNewLocation)
        {
            if (string.IsNullOrWhiteSpace(NewLocationName))
            {
                error = "请填写新库位名称。";
                return false;
            }
            if (_locations.Any(item => item.Name.Equals(NewLocationName.Trim(), StringComparison.OrdinalIgnoreCase)))
            {
                error = "库位名称已存在，请选择已有库位。";
                return false;
            }
        }
        if (string.IsNullOrWhiteSpace(Sku) || string.IsNullOrWhiteSpace(Name)
            || string.IsNullOrWhiteSpace(Category) || string.IsNullOrWhiteSpace(PackageName))
        {
            error = "请填写 SKU、名称、分类和封装。";
            return false;
        }
        request = new ComponentEditRequest(new ComponentDraft
        {
            PurchaseAmount = PurchaseAmount,
            Id = _existing?.Id,
            Sku = Sku,
            Name = Name,
            Category = Category,
            PackageName = PackageName,
            Location = locationId,
            Description = Description,
            Quantity = (int)Math.Round(Quantity),
            MinStock = (int)Math.Round(MinStock),
        }, Source, UseNewLocation ? NewLocationName.Trim() : null);
        return true;
    }

}
