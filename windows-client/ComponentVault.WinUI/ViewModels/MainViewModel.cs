using System.Collections.ObjectModel;
using System.Linq;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services;
using ComponentVault.WinUI.Services.Bom;
using ComponentVault.WinUI.Services.BatchInbound;
using ComponentVault.WinUI.Services.Migration;
using Microsoft.UI.Xaml.Controls;

namespace ComponentVault.WinUI.ViewModels;

// Stable binding facade for the shell. Feature state belongs to its own view model/coordinator.
public sealed class MainViewModel : ObservableObject, IDisposable
{
    private readonly InventoryStore _store;
    private readonly InventoryEditingService _inventoryEditing;
    private readonly BomPlanningService _bomPlanningService = new();
    private readonly DashboardViewModel _overview;
    private readonly MovementsViewModel _movements;
    private readonly SyncCoordinator _sync;
    private InventoryRestorePreview? _approvedInventoryRestorePreview;

    public MainViewModel(InventoryStore store, InventorySyncService syncService)
    {
        _store = store;
        _inventoryEditing = new InventoryEditingService(store);
        Inventory = new InventoryViewModel(store);
        _overview = new DashboardViewModel(store);
        _movements = new MovementsViewModel(store);
        _store.Initialize();
        _sync = new SyncCoordinator(store, syncService, Refresh);
        StorageLocations = [];

        Inventory.PropertyChanged += (_, args) =>
        {
            if (args.PropertyName is null) return;
            OnPropertyChanged(args.PropertyName);
            if (args.PropertyName == nameof(SelectedComponent))
            {
                _movements.SetSelectedComponent(SelectedComponent?.Id);
                OnPropertyChanged(nameof(SelectedComponentStatusSeverity));
            }
            if (args.PropertyName is nameof(Components) or nameof(AvailableComponents))
                OnPropertyChanged(nameof(CanRecordMovement));
        };
        _overview.PropertyChanged += (_, args) =>
        {
            if (args.PropertyName is not null) OnPropertyChanged(args.PropertyName);
        };
        _movements.PropertyChanged += (_, args) =>
        {
            if (args.PropertyName is null) return;
            OnPropertyChanged(args.PropertyName);
            if (args.PropertyName == nameof(SelectedMovement))
                OnPropertyChanged(nameof(SelectedMovementStatusSeverity));
            if (args.PropertyName == nameof(SelectedComponentRecentMovements))
                Inventory.SetRecentMovementCount(_movements.SelectedComponentRecentMovements.Count);
        };
        _sync.PropertyChanged += (_, args) =>
        {
            if (args.PropertyName is null) return;
            OnPropertyChanged(args.PropertyName);
            if (args.PropertyName == nameof(IsBusy)) OnPropertyChanged(nameof(CanRecordMovement));
            if (args.PropertyName == nameof(SyncConfiguration))
                OnPropertyChanged(nameof(SyncRiskSeverity));
        };

        Refresh();
        _sync.ScheduleAutoSync();
    }

    public InventoryViewModel Inventory { get; }
    public DashboardSnapshot Dashboard => _overview.Dashboard;
    public SyncConfiguration SyncConfiguration => _sync.SyncConfiguration;
    public bool IsBusy => _sync.IsBusy;
    public string StatusMessage => _sync.StatusMessage;
    public ObservableCollection<ComponentRecord> Components => Inventory.Components;
    public ObservableCollection<ComponentRecord> LowStockComponents => _overview.LowStockComponents;
    public ObservableCollection<StockMovementRecord> Movements => _movements.Movements;
    public ObservableCollection<ComponentRecord> OverviewLowStockComponents => _overview.OverviewLowStockComponents;
    public ObservableCollection<StockMovementRecord> OverviewRecentMovements => _overview.OverviewRecentMovements;
    public ObservableCollection<StockMovementRecord> SelectedComponentRecentMovements => _movements.SelectedComponentRecentMovements;
    public ObservableCollection<StorageLocationRecord> StorageLocations { get; }
    public ObservableCollection<string> InventoryCategoryOptions => Inventory.InventoryCategoryOptions;
    public ObservableCollection<string> InventoryLocationOptions => Inventory.InventoryLocationOptions;
    public ObservableCollection<string> InventorySortOptions => Inventory.InventorySortOptions;
    public string ComponentSearchText { get => Inventory.ComponentSearchText; set => Inventory.ComponentSearchText = value; }
    public bool ShowLowStockOnly { get => Inventory.ShowLowStockOnly; set => Inventory.ShowLowStockOnly = value; }
    public string SelectedComponentCategoryFilter { get => Inventory.SelectedComponentCategoryFilter; set => Inventory.SelectedComponentCategoryFilter = value; }
    public string SelectedComponentLocationFilter { get => Inventory.SelectedComponentLocationFilter; set => Inventory.SelectedComponentLocationFilter = value; }
    public string SelectedComponentSortOption { get => Inventory.SelectedComponentSortOption; set => Inventory.SelectedComponentSortOption = value; }
    public ComponentRecord? SelectedComponent { get => Inventory.SelectedComponent; set => Inventory.SelectedComponent = value; }
    public IReadOnlyList<ComponentRecord> AvailableComponents => Inventory.AvailableComponents;
    public StockMovementRecord? SelectedMovement { get => _movements.SelectedMovement; set => _movements.SelectedMovement = value; }

    public string ShellSyncSummary => _sync.ShellSyncSummary;
    public string ComponentInventorySummary => Inventory.ComponentInventorySummary;
    public string InventoryFilterSummary => Inventory.InventoryFilterSummary;
    public string InventoryWorkspaceSummary => Inventory.InventoryWorkspaceSummary;
    public string InventoryEmptyMessage => Inventory.InventoryEmptyMessage;
    public string MovementFeedSummary => _movements.MovementFeedSummary;
    public string MovementWorkspaceSummary => _movements.MovementWorkspaceSummary;
    public string InboundMovementCount => _movements.InboundMovementCount;
    public string OutboundMovementCount => _movements.OutboundMovementCount;
    public string AdjustmentMovementCount => _movements.AdjustmentMovementCount;
    public string MovementNetQuantityText => _movements.MovementNetQuantityText;
    public string OverviewLowStockSummary => _overview.OverviewLowStockSummary;
    public string OverviewRecentMovementSummary => _overview.OverviewRecentMovementSummary;
    public string OverviewSyncSummary => _sync.OverviewSyncSummary;
    public bool CanRunSyncActions => _sync.CanRunSyncActions;
    public string SyncButtonLabel => _sync.SyncButtonLabel;
    public bool CanRecordMovement => AvailableComponents.Count > 0 && !IsBusy;
    public bool CanEditSelectedComponent => Inventory.CanEditSelectedComponent;
    public bool CanDeleteSelectedComponent => Inventory.CanDeleteSelectedComponent;
    public string SelectedComponentName => Inventory.SelectedComponentName;
    public string SelectedComponentSubtitle => Inventory.SelectedComponentSubtitle;
    public string SelectedComponentQuantityText => Inventory.SelectedComponentQuantityText;
    public string SelectedComponentOutboundQuantityText => Inventory.SelectedComponentOutboundQuantityText;
    public string SelectedComponentStatisticalTotalText => Inventory.SelectedComponentStatisticalTotalText;
    public string SelectedComponentMinStockText => Inventory.SelectedComponentMinStockText;
    public string SelectedComponentStockDeltaText => Inventory.SelectedComponentStockDeltaText;
    public string SelectedComponentLocation => Inventory.SelectedComponentLocation;
    public string SelectedComponentUpdatedAt => Inventory.SelectedComponentUpdatedAt;
    public string SelectedComponentReferencePrice => Inventory.SelectedComponentReferencePrice;
    public string SelectedComponentDescription => Inventory.SelectedComponentDescription;
    public string SelectedComponentStatusTitle => Inventory.SelectedComponentStatusTitle;
    public string SelectedComponentStatusMessage => Inventory.SelectedComponentStatusMessage;
    public InfoBarSeverity SelectedComponentStatusSeverity => SelectedComponent is null
        ? InfoBarSeverity.Informational : Inventory.IsSelectedComponentLowStock
            ? InfoBarSeverity.Warning : InfoBarSeverity.Success;
    public string SelectedComponentMovementSummary => Inventory.SelectedComponentMovementSummary;
    public string SelectedComponentActionHint => Inventory.SelectedComponentActionHint;
    public string SelectedMovementTitle => _movements.SelectedMovementTitle;
    public string SelectedMovementSubtitle => _movements.SelectedMovementSubtitle;
    public string SelectedMovementQuantityText => _movements.SelectedMovementQuantityText;
    public string SelectedMovementTypeText => _movements.SelectedMovementTypeText;
    public string SelectedMovementReason => _movements.SelectedMovementReason;
    public string SelectedMovementNote => _movements.SelectedMovementNote;
    public string SelectedMovementUpdatedAt => _movements.SelectedMovementUpdatedAt;
    public string SelectedMovementTimeline => _movements.SelectedMovementTimeline;
    public string SelectedMovementStatusTitle => _movements.SelectedMovementStatusTitle;
    public string SelectedMovementStatusMessage => _movements.SelectedMovementStatusMessage;
    public InfoBarSeverity SelectedMovementStatusSeverity => SelectedMovement?.MovementType switch
    {
        "inbound" => InfoBarSeverity.Success,
        "outbound" => InfoBarSeverity.Warning,
        _ => InfoBarSeverity.Informational,
    };
    public string SelectedMovementActionHint => AvailableComponents.Count == 0
        ? "请先新增元器件，再记录库存变动。" : _movements.SelectedMovementActionHint;
    public string SyncEndpointDisplay => _sync.SyncEndpointDisplay;
    public string SyncTokenDisplay => _sync.SyncTokenDisplay;
    public string SyncAutoSyncDisplay => _sync.SyncAutoSyncDisplay;
    public string SyncRiskTitle => _sync.SyncRiskTitle;
    public string SyncRiskMessage => _sync.SyncRiskMessage;
    public InfoBarSeverity SyncRiskSeverity => _sync.HasSyncRisk
        ? InfoBarSeverity.Warning : InfoBarSeverity.Success;
    public string SyncConfigurationSummary => _sync.SyncConfigurationSummary;
    public string SyncStatusSummary => _sync.SyncStatusSummary;
    public string InventoryLastSuccess => _sync.InventoryLastSuccess;
    public string SyncScopeSummary => _sync.SyncScopeSummary;
    public bool CanCancelSync => _sync.CanCancelSync;
    public void CancelSync() => _sync.CancelSync();
    public string PhotoSyncSummary => _sync.PhotoSyncSummary;
    public string ClearPhotoCache() => _sync.ClearPhotoCache();
    public string SyncActionHint => _sync.SyncActionHint;

    public void Refresh()
    {
        _movements.Refresh(SelectedComponent?.Id);
        _overview.Refresh(Movements);
        Inventory.SetDashboard(Dashboard);
        Inventory.Refresh();
        ReplaceCollection(StorageLocations, _store.GetStorageLocations());
        _sync.Refresh();
        OnPropertyChanged(nameof(CanRecordMovement));
        OnPropertyChanged(nameof(SelectedMovementActionHint));
    }

    public ComponentRecord? FindActiveComponentBySku(string sku) => _inventoryEditing.FindActiveSku(sku);
    public ComponentEditPlan PrepareComponentEdit(ComponentEditRequest request) => _inventoryEditing.Prepare(request);
    public OperationResult CommitComponentEdit(ComponentEditPlan plan)
    {
        var result = _inventoryEditing.Commit(plan);
        return InventoryEditWriteCoordinator.AfterCommit(result,
            () =>
            {
                Refresh();
                var savedId = plan.AppendTarget?.Id ?? plan.Request.Draft.Id;
                SelectedComponent = AvailableComponents.FirstOrDefault(component =>
                    savedId is not null ? component.Id == savedId
                        : component.Sku.Equals(plan.Request.Draft.Sku.Trim(), StringComparison.OrdinalIgnoreCase));
            }, _sync.ScheduleAutoSync);
    }

    public IReadOnlyList<StorageLocationRecord> GetAllStorageLocations() => _store.GetStorageLocations(includeDeleted: true);
    public OperationResult CreateStorageLocation(string id, string name)
    {
        var result = _store.CreateStorageLocation(id, name);
        if (result.IsSuccess) { Refresh(); _sync.ScheduleAutoSync(); }
        return result;
    }
    public OperationResult UpdateStorageLocation(string id, string name)
    {
        var result = _store.UpdateStorageLocation(id, name);
        if (result.IsSuccess) { Refresh(); _sync.ScheduleAutoSync(); }
        return result;
    }
    public OperationResult DeleteStorageLocation(string id)
    {
        var result = _store.DeleteStorageLocation(id);
        if (result.IsSuccess) { Refresh(); _sync.ScheduleAutoSync(); }
        return result;
    }
    public OperationResult TransferBatch(InventoryStore.BatchTransferRequest request)
    {
        var result = _store.TransferBatch(request);
        if (result.IsSuccess) { Refresh(); _sync.ScheduleAutoSync(); }
        return result;
    }
    public bool IsBatchReceiptCommitted(string receiptId) => _store.IsBatchReceiptCommitted(receiptId);
    public OperationResult CommitBatchInbound(BatchInboundCommitRequest request)
    {
        var result = _store.CommitBatchInbound(request);
        if (result.IsSuccess) { Refresh(); _sync.ScheduleAutoSync(); }
        return result;
    }

    public void ExportInventoryWorkbook(string path) => new InventoryWorkbookBackup(_store).Export(path);
    public InventoryRestorePreview PreviewInventoryWorkbook(string path) =>
        _approvedInventoryRestorePreview = new InventoryWorkbookBackup(_store).Preview(path);
    public OperationResult ImportInventoryWorkbook(string path)
    {
        var result = new InventoryWorkbookBackup(_store).ImportNewOnly(path, _approvedInventoryRestorePreview);
        _approvedInventoryRestorePreview = null;
        if (result.IsSuccess) { Refresh(); _sync.ScheduleAutoSync(); }
        return result;
    }

    public OperationResult DeleteSelectedComponent()
    {
        if (SelectedComponent is null) return OperationResult.Failure("请先选择一个元器件。");
        var result = _store.SoftDeleteComponent(SelectedComponent.Id);
        Refresh();
        if (result.IsSuccess) _sync.ScheduleAutoSync();
        return result;
    }

    public OperationResult SetInventoryCost(string id, string version, string amount)
    {
        var result = _store.SetInventoryCost(id, version, amount);
        if(result.IsSuccess) { Refresh(); _sync.ScheduleAutoSync(); }
        return result;
    }
    public OperationResult RecordMovement(MovementEntryDraft draft)
    {
        var result = _movements.Record(draft);
        if (!result.IsSuccess)
        {
            // Validation failures made no write; keep the existing refresh behavior.
            try { Refresh(); }
            catch (Exception exception) { return OperationResult.Failure(exception.Message); }
            return result;
        }
        return InventoryEditWriteCoordinator.AfterCommit(result,
            () =>
            {
                Refresh();
                SelectedComponent = Components.FirstOrDefault(component => component.Id == draft.ComponentId);
                _movements.SelectLatestForComponent(draft.ComponentId);
            }, _sync.ScheduleAutoSync);
    }

    public OperationResult SaveSyncConfiguration(string serverBaseUrl, string fallbackServerBaseUrl,
        string apiToken, bool autoSyncEnabled) =>
        _sync.SaveConfiguration(serverBaseUrl, fallbackServerBaseUrl, apiToken, autoSyncEnabled);

    public BomPreview CreateBomPreview(BomDocument document, string projectName, int batchQuantity,
        IReadOnlyDictionary<int, string>? selectedComponentIds = null, IReadOnlySet<int>? skippedRows = null) =>
        _bomPlanningService.CreatePreview(document, new BomImportOptions(projectName, batchQuantity),
            _store.GetComponents(), selectedComponentIds, skippedRows);

    public BomConfirmResult ConfirmBomConsumption(BomConfirmRequest request)
    {
        var result = _store.ConfirmBomConsumption(request);
        Refresh();
        if (result.IsSuccess && !result.AlreadyApplied) _sync.ScheduleAutoSync();
        return result;
    }
    public ComponentHubPreview PreviewComponentHubMigration(string filePath,
        DuplicateSkuPolicy duplicatePolicy) =>
        new ComponentHubMigrationReader().Read(filePath, _store.GetComponents(), duplicatePolicy);
    public OperationResult ImportComponentHub(ComponentHubImportRequest request)
    {
        var result = _store.ImportComponentHub(request);
        Refresh();
        if (result.IsSuccess) _sync.ScheduleAutoSync();
        return result;
    }

    public Task<OperationResult> TestConnectionAsync(string serverBaseUrl, string fallbackServerBaseUrl,
        string apiToken, bool autoSyncEnabled) =>
        _sync.TestConnectionAsync(serverBaseUrl, fallbackServerBaseUrl, apiToken, autoSyncEnabled);
    public Task<SyncRunResult> RunSyncAsync() => _sync.RunSyncAsync();

    private static void ReplaceCollection<T>(ObservableCollection<T> collection, IEnumerable<T> items)
    {
        collection.Clear();
        foreach (var item in items) collection.Add(item);
    }

    public void Dispose() => _sync.Dispose();
}
