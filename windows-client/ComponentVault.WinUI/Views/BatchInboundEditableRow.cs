using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services.Catalog;
using ComponentVault.WinUI.ViewModels;
using Microsoft.UI.Xaml;

namespace ComponentVault.WinUI.Views;

public sealed class BatchInboundEditableRow : ObservableObject
{
    private bool _selected = true;
    private bool _pendingSelected;
    private double _reviewWidth = 540;
    private double _pendingWidth = 810;

    public required string ReceiptId { get; init; }
    public required string Raw { get; init; }
    public bool Selected
    {
        get => _selected;
        set => SetProperty(ref _selected, value);
    }
    // Transient Retry/Remove selection; excluded from persisted inbound approval.
    public bool PendingSelected
    {
        get => _pendingSelected;
        set => SetProperty(ref _pendingSelected, value);
    }
    public double ReviewWidth
    {
        get => _reviewWidth;
        set => SetProperty(ref _reviewWidth, value);
    }
    public double PendingWidth
    {
        get => _pendingWidth;
        set => SetProperty(ref _pendingWidth, value);
    }

    public string Sku { get; set; } = "";
    public double QuantityInput { get; set; } = double.NaN;
    public string LocationId { get; set; } = "";
    public string Name { get; set; } = "";
    public string Category { get; set; } = "";
    public string PackageName { get; set; } = "";
    public string Description { get; set; } = "";
    public string Reason { get; set; } = "";
    public IReadOnlyList<StorageLocationRecord> LocationOptions { get; set; } = [];

    public string Summary => Raw.StartsWith("lcsc-order:", StringComparison.Ordinal)
        ? $"{Sku} · {Name} · 订单条目"
        : $"{Sku} · 包装 {ReceiptId[..8]}";

    public string Specifications => OfficialSpecifications.FromDescription(Description, Category);
    public Visibility SpecificationsVisibility => string.IsNullOrWhiteSpace(Specifications)
        ? Visibility.Collapsed
        : Visibility.Visible;

    public string ManualMetadata
    {
        get => $"{Name} | {Category} | {PackageName}";
        set
        {
            var parts = value.Split('|');
            Name = parts.ElementAtOrDefault(0)?.Trim() ?? "";
            Category = parts.ElementAtOrDefault(1)?.Trim() ?? "";
            PackageName = parts.ElementAtOrDefault(2)?.Trim() ?? "";
        }
    }

    public int? Quantity => double.IsFinite(QuantityInput)
        && QuantityInput == Math.Truncate(QuantityInput)
        && QuantityInput is > 0 and <= int.MaxValue
            ? (int)QuantityInput
            : null;
}
