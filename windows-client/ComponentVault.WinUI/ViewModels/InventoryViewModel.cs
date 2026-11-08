using System.Collections.ObjectModel;
using ComponentVault.WinUI.Localization;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services;

namespace ComponentVault.WinUI.ViewModels;

/// <summary>Owns the inventory list, its filters, and selection across store refreshes.</summary>
public sealed class InventoryViewModel : ObservableObject
{
    private const string AllCategoriesOption = "全部分类";
    private const string AllLocationsOption = "全部仓位";
    private const string SortUpdatedDescOption = "最近更新";
    private const string SortNameAscOption = "名称 A-Z";
    private const string SortLowStockFirstOption = "库存紧张优先";
    private const string SortQuantityDescOption = "库存数量";

    private readonly InventoryStore _store;
    private bool _updatingOptions;
    private IReadOnlyList<ComponentRecord> _allComponents = [];
    private Dictionary<string, string> _locationIdsByLabel = new(StringComparer.Ordinal);
    private string _componentSearchText = string.Empty;
    private bool _showLowStockOnly;
    private string _selectedComponentCategoryFilter = AllCategoriesOption;
    private string _selectedComponentLocationFilter = AllLocationsOption;
    private string _selectedComponentSortOption = SortUpdatedDescOption;
    private ComponentRecord? _selectedComponent;
    private DashboardSnapshot _dashboard = new() { ComponentCount = 0, TotalUnits = 0, LowStockCount = 0, MovementCount = 0 };
    private int _recentMovementCount;

    public InventoryViewModel(InventoryStore store)
    {
        _store = store;
        Components = [];
        InventoryCategoryOptions = [];
        InventoryLocationOptions = [];
        InventorySortOptions = new ObservableCollection<string>(
            [SortUpdatedDescOption, SortLowStockFirstOption, SortNameAscOption, SortQuantityDescOption]);
    }

    public ObservableCollection<ComponentRecord> Components { get; }
    public ObservableCollection<string> InventoryCategoryOptions { get; }
    public ObservableCollection<string> InventoryLocationOptions { get; }
    public ObservableCollection<string> InventorySortOptions { get; }
    public IReadOnlyList<ComponentRecord> AvailableComponents => _allComponents;

    public string ComponentInventorySummary =>
        $"当前显示 {Components.Count} 项，共跟踪 {_dashboard.ComponentCount} 项元器件。";
    public string InventoryFilterSummary
    {
        get
        {
            var summaries = new List<string> { $"结果 {Components.Count}/{_dashboard.ComponentCount}" };
            if (ShowLowStockOnly) summaries.Add("低库存");
            if (!string.IsNullOrWhiteSpace(ComponentSearchText)) summaries.Add($"搜索：{ComponentSearchText.Trim()}");
            if (SelectedComponentCategoryFilter != AllCategoriesOption)
                summaries.Add($"分类：{CategoryDisplay.Localize(SelectedComponentCategoryFilter)}");
            if (SelectedComponentLocationFilter != AllLocationsOption)
                summaries.Add($"仓位：{SelectedComponentLocationFilter}");
            summaries.Add($"排序：{SelectedComponentSortOption}");
            return string.Join(" · ", summaries);
        }
    }
    public string InventoryWorkspaceSummary =>
        $"分类 {Math.Max(InventoryCategoryOptions.Count - 1, 0)} 个 · 仓位 {Math.Max(InventoryLocationOptions.Count - 1, 0)} 个 · 低库存 {_dashboard.LowStockCount} 项";
    public string InventoryEmptyMessage => _dashboard.ComponentCount == 0
        ? "还没有元器件。先新增一个元器件开始建库。"
        : "当前筛选条件下没有结果，尝试清空搜索或切换筛选。";
    public bool CanEditSelectedComponent => SelectedComponent is not null;
    public bool CanDeleteSelectedComponent => SelectedComponent is not null;
    public string SelectedComponentReferencePrice => ComponentVault.WinUI.Services.Catalog.ReferencePriceSnapshot.Read(SelectedComponent?.Description)?.Display ?? "尚无商城参考价";
    public string SelectedComponentName => SelectedComponent?.Name ?? "尚未选择元器件";
    public string SelectedComponentSubtitle => SelectedComponent is null
        ? "请先从左侧列表中选择一项，以查看封装、仓位和补货风险。"
        : $"{SelectedComponent.Sku} | {SelectedComponent.DisplayCategory} | {SelectedComponent.PackageName}";
    public string SelectedComponentQuantityText => (SelectedComponent?.Quantity ?? 0).ToString();
    public string SelectedComponentOutboundQuantityText => (SelectedComponent?.CumulativeOutboundQuantity ?? 0).ToString();
    public string SelectedComponentStatisticalTotalText => (SelectedComponent?.StatisticalTotal ?? 0).ToString();
    public string SelectedComponentMinStockText => (SelectedComponent?.MinStock ?? 0).ToString();
    public string SelectedComponentStockDeltaText => SelectedComponent is null ? "0"
        : (SelectedComponent.Quantity - SelectedComponent.MinStock).ToString("+#,0;-#,0;0");
    public string SelectedComponentLocation => SelectedComponent?.DisplayLocation ?? "尚未选择仓位。";
    public string SelectedComponentUpdatedAt => SelectedComponent?.DisplayUpdatedAt ?? "尚未选择";
    public string SelectedComponentDescription => string.IsNullOrWhiteSpace(SelectedComponent?.Description)
        ? "当前元器件没有填写备注或描述。" : SelectedComponent.Description;
    public string SelectedComponentStatusTitle => SelectedComponent?.Status ?? "等待选择";
    public string SelectedComponentStatusMessage => SelectedComponent is null
        ? "选择元器件后，这里会显示库存风险、仓位和最近变动摘要。"
        : SelectedComponent.IsLowStock
            ? "当前库存已低于或等于最低库存，建议尽快补货。"
            : "当前库存高于最低库存阈值，可继续支持日常领用。";
    public bool IsSelectedComponentLowStock => SelectedComponent?.IsLowStock == true;
    public string SelectedComponentMovementSummary => SelectedComponent is null
        ? "选择元器件后，在这里查看最近的出入库摘要。"
        : _recentMovementCount == 0 ? "该元器件还没有关联的出入库记录。"
        : $"最近 {_recentMovementCount} 条记录与当前元器件相关。";
    public string SelectedComponentActionHint => SelectedComponent is null
        ? "选择元器件后，才能执行编辑和软删除。"
        : "编辑会保留本地优先与同步语义；删除采用软删除，不会直接清除历史记录。";

    public void SetDashboard(DashboardSnapshot dashboard)
    {
        _dashboard = dashboard;
        NotifySummary();
    }

    public void SetRecentMovementCount(int count)
    {
        _recentMovementCount = count;
        OnPropertyChanged(nameof(SelectedComponentMovementSummary));
    }

    public string ComponentSearchText
    {
        get => _componentSearchText;
        set { if (SetProperty(ref _componentSearchText, value)) ApplyFilter(); }
    }

    public bool ShowLowStockOnly
    {
        get => _showLowStockOnly;
        set { if (SetProperty(ref _showLowStockOnly, value)) ApplyFilter(); }
    }

    public string SelectedComponentCategoryFilter
    {
        get => _selectedComponentCategoryFilter;
        set { if (!_updatingOptions && SetProperty(ref _selectedComponentCategoryFilter, value ?? AllCategoriesOption)) ApplyFilter(); }
    }

    public string SelectedComponentLocationFilter
    {
        get => _selectedComponentLocationFilter;
        set { if (!_updatingOptions && SetProperty(ref _selectedComponentLocationFilter, value ?? AllLocationsOption)) ApplyFilter(); }
    }

    public string SelectedComponentSortOption
    {
        get => _selectedComponentSortOption;
        set { if (!_updatingOptions && SetProperty(ref _selectedComponentSortOption, value ?? SortUpdatedDescOption)) ApplyFilter(); }
    }

    public ComponentRecord? SelectedComponent
    {
        get => _selectedComponent;
        set { if (SetProperty(ref _selectedComponent, value)) NotifySelection(); }
    }

    public void Refresh()
    {
        var components = _store.GetComponents();
        var outboundTotals = _store.GetCumulativeOutboundQuantities();
        var display = new StorageLocationDisplay(_store.GetStorageLocations(includeDeleted: true));
        _allComponents = components.Select(component => display.Decorate(new ComponentRecord
        {
            Id = component.Id,
            Sku = component.Sku,
            Name = component.Name,
            Category = component.Category,
            PackageName = component.PackageName,
            Location = component.Location,
            Description = component.Description,
            Quantity = component.Quantity,
            MinStock = component.MinStock,
            UpdatedAt = component.UpdatedAt,
            Deleted = component.Deleted,
            CumulativeOutboundQuantity = outboundTotals.GetValueOrDefault(component.Id),
            Allocations = component.Allocations, InventoryUnitCost = component.InventoryUnitCost,
            BaseUpdatedAt = component.BaseUpdatedAt,
        })).ToArray();
        UpdateFilterOptions();
        ApplyFilter();
        OnPropertyChanged(nameof(AvailableComponents));
    }

    private void ApplyFilter()
    {
        IEnumerable<ComponentRecord> filtered = _allComponents;
        if (ShowLowStockOnly)
            filtered = filtered.Where(component => component.IsLowStock);
        if (SelectedComponentCategoryFilter != AllCategoriesOption)
            filtered = filtered.Where(component => CategoryFilterSemantics.SameCategory(component.Category, SelectedComponentCategoryFilter));
        if (SelectedComponentLocationFilter != AllLocationsOption)
        {
            var locationId = _locationIdsByLabel.GetValueOrDefault(SelectedComponentLocationFilter,
                SelectedComponentLocationFilter);
            filtered = filtered.Where(component => component.Location == locationId);
        }
        if (!string.IsNullOrWhiteSpace(ComponentSearchText))
        {
            var query = ComponentSearchText.Trim();
            filtered = filtered.Where(component => InventorySearch.Matches(component, query)
                || CategoryFilterSemantics.MatchesSearch(component.Category, query)
                || component.Allocations.Any(allocation => allocation.LocationName is not null
                    && allocation.LocationName.Contains(query, StringComparison.OrdinalIgnoreCase)));
        }
        filtered = SelectedComponentSortOption switch
        {
            SortLowStockFirstOption => filtered.OrderBy(component => component.IsLowStock ? 0 : 1)
                .ThenBy(component => component.Quantity - component.MinStock).ThenBy(component => component.Name),
            SortNameAscOption => filtered.OrderBy(component => component.Name),
            SortQuantityDescOption => filtered.OrderByDescending(component => component.Quantity).ThenBy(component => component.Name),
            _ => filtered.OrderByDescending(component => component.UpdatedAt),
        };
        var selectedId = SelectedComponent?.Id;
        Components.Clear();
        foreach (var component in filtered) Components.Add(component);

        SelectedComponent = Components.Count == 0 ? null
            : Components.FirstOrDefault(component => component.Id == selectedId) ?? Components[0];
        OnPropertyChanged(nameof(Components));
        NotifySummary();
    }

    private void UpdateFilterOptions()
    {
        _updatingOptions = true;
        try
        {
            var categories = CategoryFilterSemantics.Options(_allComponents.Select(component => component.Category));
            ReplaceOptions(InventoryCategoryOptions, new[] { AllCategoriesOption }.Concat(categories));
            if (_selectedComponentCategoryFilter != AllCategoriesOption)
            {
                var equivalent = categories.FirstOrDefault(option =>
                    CategoryFilterSemantics.SameCategory(option, _selectedComponentCategoryFilter));
                if (equivalent is not null && !_selectedComponentCategoryFilter.Equals(equivalent, StringComparison.Ordinal))
                {
                    _selectedComponentCategoryFilter = equivalent;
                    OnPropertyChanged(nameof(SelectedComponentCategoryFilter));
                }
            }
            if (!InventoryCategoryOptions.Contains(_selectedComponentCategoryFilter, StringComparer.OrdinalIgnoreCase))
            {
                _selectedComponentCategoryFilter = AllCategoriesOption;
                OnPropertyChanged(nameof(SelectedComponentCategoryFilter));
            }

            var oldLocationId = _locationIdsByLabel.GetValueOrDefault(
                _selectedComponentLocationFilter, _selectedComponentLocationFilter);
            var locations = _allComponents.Select(component => component.Location)
                .Where(location => !string.IsNullOrWhiteSpace(location))
                .Distinct(StringComparer.Ordinal).ToArray();
            var displayNames = _allComponents.GroupBy(component => component.Location,
                StringComparer.Ordinal).ToDictionary(group => group.Key,
                group => group.First().DisplayLocation, StringComparer.Ordinal);
            var duplicateNames = displayNames.Values.GroupBy(name => name,
                StringComparer.OrdinalIgnoreCase).Where(group => group.Count() > 1)
                .Select(group => group.Key).ToHashSet(StringComparer.OrdinalIgnoreCase);
            _locationIdsByLabel = locations.ToDictionary(id =>
                duplicateNames.Contains(displayNames[id]) || displayNames[id] == AllLocationsOption
                    ? $"{displayNames[id]} ({id})" : displayNames[id],
                id => id, StringComparer.Ordinal);
            ReplaceOptions(InventoryLocationOptions, new[] { AllLocationsOption }
                .Concat(_locationIdsByLabel.Keys.OrderBy(label => label, StringComparer.OrdinalIgnoreCase)));
            var matchingLabel = _locationIdsByLabel.FirstOrDefault(pair => pair.Value == oldLocationId).Key;
            if (matchingLabel is not null && _selectedComponentLocationFilter != AllLocationsOption)
                _selectedComponentLocationFilter = matchingLabel;
            if (!InventoryLocationOptions.Contains(_selectedComponentLocationFilter))
            {
                _selectedComponentLocationFilter = AllLocationsOption;
                OnPropertyChanged(nameof(SelectedComponentLocationFilter));
            }
            if (!InventorySortOptions.Contains(_selectedComponentSortOption))
            {
                _selectedComponentSortOption = SortUpdatedDescOption;
                OnPropertyChanged(nameof(SelectedComponentSortOption));
            }
        }
        finally
        {
            _updatingOptions = false;
        }
        // The ComboBoxes may clear SelectedItem while their option lists are rebuilt.
        OnPropertyChanged(nameof(SelectedComponentCategoryFilter));
        OnPropertyChanged(nameof(SelectedComponentLocationFilter));
        OnPropertyChanged(nameof(SelectedComponentSortOption));
    }

    private static void ReplaceOptions(ObservableCollection<string> target, IEnumerable<string> options)
    {
        target.Clear();
        foreach (var option in options) target.Add(option);
    }

    private void NotifySummary()
    {
        OnPropertyChanged(nameof(ComponentInventorySummary));
        OnPropertyChanged(nameof(InventoryFilterSummary));
        OnPropertyChanged(nameof(InventoryWorkspaceSummary));
        OnPropertyChanged(nameof(InventoryEmptyMessage));
    }

    private void NotifySelection()
    {
        OnPropertyChanged(nameof(CanEditSelectedComponent));
        OnPropertyChanged(nameof(CanDeleteSelectedComponent));
        OnPropertyChanged(nameof(SelectedComponentName));
        OnPropertyChanged(nameof(SelectedComponentSubtitle));
        OnPropertyChanged(nameof(SelectedComponentQuantityText));
        OnPropertyChanged(nameof(SelectedComponentOutboundQuantityText));
        OnPropertyChanged(nameof(SelectedComponentStatisticalTotalText));
        OnPropertyChanged(nameof(SelectedComponentMinStockText));
        OnPropertyChanged(nameof(SelectedComponentStockDeltaText));
        OnPropertyChanged(nameof(SelectedComponentLocation));
        OnPropertyChanged(nameof(SelectedComponentUpdatedAt));
        OnPropertyChanged(nameof(SelectedComponentReferencePrice));
        OnPropertyChanged(nameof(SelectedComponentDescription));
        OnPropertyChanged(nameof(SelectedComponentStatusTitle));
        OnPropertyChanged(nameof(SelectedComponentStatusMessage));
        OnPropertyChanged(nameof(IsSelectedComponentLowStock));
        OnPropertyChanged(nameof(SelectedComponentMovementSummary));
        OnPropertyChanged(nameof(SelectedComponentActionHint));
    }
}



