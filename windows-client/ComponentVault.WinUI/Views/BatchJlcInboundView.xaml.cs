using System.Collections.ObjectModel;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services;
using ComponentVault.WinUI.Services.BatchInbound;
using ComponentVault.WinUI.Services.Catalog;
using ComponentVault.WinUI.ViewModels;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Windows.System;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace ComponentVault.WinUI.Views;

public sealed partial class BatchJlcInboundView:Page
{
    private readonly BatchInboundDraftStore _drafts=new();private readonly LcscPublicLookup _lookup=new();private readonly ObservableCollection<BatchInboundEditableRow> _ready=[];private readonly ObservableCollection<BatchInboundEditableRow> _pending=[];private readonly HashSet<string> _removed=[];private CancellationTokenSource? _lookupCancellation;private int _generation;private bool _draftLoadFailed;private bool _busy;
    private MainViewModel ViewModel=>((App)Application.Current).MainViewModel??throw new InvalidOperationException("MainViewModel unavailable");
    public BatchJlcInboundView()
    {
        InitializeComponent();
        ReadyList.ItemsSource = _ready;
        PendingList.ItemsSource = _pending;
        DefaultLocationBox.ItemsSource = ViewModel.StorageLocations;
        InitializeReadySelection();
        InitializePendingSelection();
        InitializeTableContextMenus();
        InitializeReadyLayout();
        InitializePendingLayout();
        ReadyList.SizeChanged += (_, _) => UpdateReadyRowWidths();
        _ready.CollectionChanged += (_, args) =>
        {
            if (args.NewItems is not null)
                foreach (BatchInboundEditableRow row in args.NewItems) row.ReviewWidth = ReadyRowWidth();
        };
        UpdateReadyRowWidths();
        PendingList.SizeChanged += (_, _) => UpdatePendingRowWidths();
        _pending.CollectionChanged += (_, args) =>
        {
            if (args.NewItems is not null)
                foreach (BatchInboundEditableRow row in args.NewItems) row.PendingWidth = PendingRowWidth();
        };
        UpdatePendingRowWidths();
        _ready.CollectionChanged += (_, _) => UpdateTabHeaders();
        _pending.CollectionChanged += (_, _) => UpdateTabHeaders();
        UpdateTabHeaders();
        try
        {
            foreach (var item in _drafts.Load())
            {
                if (ViewModel.IsBatchReceiptCommitted(item.ReceiptId) || item.Status == "captured") continue;
                var row = FromDraft(item, ViewModel.StorageLocations.ToArray());
                (item.Status == "ready" ? _ready : _pending).Add(row);
            }
            RefreshCount();
        }
        catch (Exception ex) { _draftLoadFailed = true; CountText.Text = ex.Message; }
    }
    private void UpdateTabHeaders()
    {
        PendingTab.Header = $"待处理（{_pending.Count}）";
        ReviewTab.Header = $"待核对（{_ready.Count}）";
    }
    private double ReadyRowWidth() => Math.Max(540, ReadyList.ActualWidth - 32);
    private void UpdateReadyRowWidths()
    {
        var width = ReadyRowWidth();
        ReadyColumnsHeader.Width = width;
        foreach (var row in _ready) row.ReviewWidth = width;
    }
    private double PendingRowWidth() => Math.Max(810, PendingList.ActualWidth - 32);
    private void UpdatePendingRowWidths()
    {
        var width = PendingRowWidth();
        PendingColumnsHeader.Width = width;
        foreach (var row in _pending) row.PendingWidth = width;
    }
    private void OnBack(object sender,RoutedEventArgs e){if(_busy)return;if(((App)Application.Current).Window is MainWindow window)window.GoBack();}
    public bool TryPrepareToLeave(){if(_busy)return false;PersistEdits();return true;}
    private void OnCaptureKeyDown(object sender,KeyRoutedEventArgs e){if(!_busy&&e.Key==VirtualKey.Enter&&CaptureBox.Text.IndexOfAny(['\r','\n'])<0){Collect();e.Handled=true;}}
    private void OnCollect(object sender,RoutedEventArgs e){if(!_busy)Collect();}
    private void Collect(){try{var merged=_drafts.Merge(CaptureBox.Text.Split(['\r','\n'],StringSplitOptions.RemoveEmptyEntries));CaptureBox.Text="";CountText.Text=$"已收集 {merged.Items.Count}/500 个包装；本次忽略重复 {merged.Duplicates} 条。";}catch(Exception ex){CountText.Text=ex.Message;_=ShowBatchErrorAsync("草稿保存失败",ex.Message);}}
    private async void OnImportOrders(object sender, RoutedEventArgs e)
    {
        if (_busy) return;
        SetBusy(true);
        try
        {
            SaveRemaining();
            var picker = new FileOpenPicker();
            picker.FileTypeFilter.Add(".xls");
            InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(((App)Application.Current).Window));
            var file = await picker.PickSingleFileAsync();
            if (file is null) return;

            CountText.Text = "正在读取立创商城表格…";
            var import = await Task.Run(() => JlcOrderSpreadsheetParser.ParseFile(file.Path));
            var merged = _drafts.MergeImported(import.Rows, ViewModel.IsBatchReceiptCommitted);
            // Explicitly reimporting a removed, uncommitted order should restore it to the draft.
            _removed.ExceptWith(merged.Items.Select(item => item.ReceiptId));
            var shown = _ready.Concat(_pending).Select(row => row.ReceiptId).ToHashSet(StringComparer.Ordinal);
            await ParseAsync(merged.Items.Where(item => !shown.Contains(item.ReceiptId)
                && !ViewModel.IsBatchReceiptCommitted(item.ReceiptId)));
            CountText.Text += $"；表格新增 {merged.Added} 条，重复或已入库 {merged.Duplicates} 条，忽略运费 {import.SkippedFees} 行、汇总 {import.SkippedSummaries} 行。";
        }
        catch (Exception exception) { await ShowBatchErrorAsync("表格导入失败", exception.Message); }
        finally { SetBusy(false); }
    }
    private async void OnParse(object sender,RoutedEventArgs e){if(_busy)return;SetBusy(true);try{Collect();var shown=_ready.Concat(_pending).Select(x=>x.ReceiptId).ToHashSet();await ParseAsync(_drafts.Load().Where(x=>!shown.Contains(x.ReceiptId)&&!ViewModel.IsBatchReceiptCommitted(x.ReceiptId)));}catch(Exception ex){await ShowBatchErrorAsync("批量解析失败",ex.Message);}finally{SetBusy(false);}}
    private async Task ParseAsync(IEnumerable<BatchScanItem> scans)
    {
        _lookupCancellation?.Cancel();
        _lookupCancellation = new();
        var generation = ++_generation;
        var token = _lookupCancellation.Token;
        var cache = new Dictionary<string, LcscCatalogLookupResult>(StringComparer.OrdinalIgnoreCase);
        var locations = ViewModel.StorageLocations.ToArray();
        var defaultLocation = (DefaultLocationBox.SelectedItem as StorageLocationRecord)?.Id ?? locations.FirstOrDefault()?.Id ?? "";
        var source = scans.Select(scan => (Scan: scan, OrderImport: IsOrderImport(scan.Raw), Row: CreateRow(scan))).ToArray();
        // Show all spreadsheet data before awaiting the first catalog request.
        foreach (var entry in source.Where(entry => entry.OrderImport))
        {
            Remove(entry.Row.ReceiptId);
            AddResult(entry.Row, OrderReviewReason(entry.Scan, entry.Row, "资料不完整，请补齐名称、分类和封装。"));
        }
        if (source.Any(entry => entry.OrderImport)) SaveRemaining();
        var processed = 0;
        var lookupFailures = 0;
        foreach (var entry in source)
        {
            if (token.IsCancellationRequested || generation != _generation) break;
            var (scan, orderImport, row) = entry;
            if (!orderImport) Remove(scan.ReceiptId);
            processed++;
            CountText.Text = $"正在查询 {processed}/{source.Length}：{row.Sku} · 待核对 {_ready.Count} · 待处理 {_pending.Count}";
            if (LcscPublicCatalog.NormalizeSku(row.Sku) is null)
            {
                AddResult(row, "料号无效，请手动填写合法的 C 编号。");
                continue;
            }
            if (!orderImport && row.Quantity is null)
            {
                AddResult(row, string.IsNullOrWhiteSpace(row.Reason) ? "数量无效，请手动确认。" : row.Reason);
                continue;
            }
            var local = ViewModel.FindActiveComponentBySku(row.Sku);
            if (local is not null)
            {
                if (orderImport)
                {
                    ApplyMetadata(CurrentMetadata().FillMissingFrom(new(local.Name, local.Category, local.PackageName, local.Description)));
                    AddResult(row, OrderReviewReason(scan, row, "资料不完整，请补齐名称、分类和封装。"));
                }
                else
                {
                    row.Name = local.Name; row.Category = local.Category;
                    row.PackageName = local.PackageName; row.Description = local.Description;
                    AddResult(row, ReadyForReview(row) ? "" : "请补齐数量和资料。");
                    continue;
                }
            }
            try
            {
                if (!cache.TryGetValue(row.Sku, out var lookup))
                {
                    lookup = await LookupCatalogAsync(row.Sku, token);
                    cache[row.Sku] = lookup;
                }
                if (generation != _generation) break;
                if (lookup.Metadata is not { } metadata)
                {
                    AddLookupFailure(string.IsNullOrWhiteSpace(lookup.UserMessage) ? "商城无精确匹配，请手动补齐资料。" : lookup.UserMessage);
                    continue;
                }
                if (orderImport)
                {
                    ApplyMetadata(CurrentMetadata().FillMissingFrom(new(
                        OfficialSpecifications.AutoName(metadata), metadata.Category, metadata.PackageName ?? "", CatalogNotes(metadata))));
                }
                else
                {
                    if (string.IsNullOrWhiteSpace(row.Name)) row.Name = OfficialSpecifications.AutoName(metadata);
                    if (!string.IsNullOrWhiteSpace(metadata.Category)) row.Category = metadata.Category;
                    if (!string.IsNullOrWhiteSpace(metadata.PackageName)) row.PackageName = metadata.PackageName;
                    row.Description = CatalogNotes(metadata);
                }
                AddResult(row, orderImport
                    ? OrderReviewReason(scan, row, "商城资料不完整，请手动补齐名称、分类和封装。")
                    : ReadyForReview(row) ? "" : "商城资料不完整，请手动补齐名称、分类和封装。");
            }
            catch (OperationCanceledException)
            {
                if (generation == _generation) AddResult(row, orderImport
                    ? OrderReviewReason(scan, row, "查询已取消，可重试或手动确认资料。")
                    : "查询已取消，可重试或手动确认资料。");
                break;
            }
            catch (Exception exception) { AddLookupFailure($"查询失败（{exception.GetType().Name}），可重试或手填。"); }

            void AddLookupFailure(string reason)
            {
                lookupFailures++;
                AddResult(row, orderImport ? OrderReviewReason(scan, row, reason) : reason);
            }
            BatchInboundMetadata CurrentMetadata() => new(row.Name, row.Category, row.PackageName, row.Description);
            void ApplyMetadata(BatchInboundMetadata metadata)
            {
                row.Name = metadata.Name;
                row.Category = metadata.Category;
                row.PackageName = metadata.PackageName;
                row.Description = metadata.Description;
            }
        }
        if (generation != _generation) return;
        if (token.IsCancellationRequested)
        {
            foreach (var entry in source.Skip(processed).Where(entry => entry.OrderImport))
                AddResult(entry.Row, OrderReviewReason(entry.Scan, entry.Row, "查询已取消，可重试或手动确认资料。"));
        }
        SaveRemaining();
        RefreshCount();
        if (lookupFailures > 0) CountText.Text += $"；{lookupFailures} 条未取得商城资料，已保留表格和草稿数据。";
        if (token.IsCancellationRequested) CountText.Text += "；查询已取消，表格数据已保留；缺项条目可在待处理页重试。";
        if (_pending.Count > 0) await ShowBatchErrorAsync("部分条目需要处理", $"有 {_pending.Count} 条未就绪，已保留在“待处理”页。可重试、手填或移除；就绪项可单独提交。");

        BatchInboundEditableRow CreateRow(BatchScanItem scan)
        {
            var orderImport = IsOrderImport(scan.Raw);
            var parsed = orderImport
                ? new JlcParsedLabel(scan.Sku, null, null, null, null, null, null, null, null)
                : JlcQrParser.Parse(scan.Raw);
            var editedSku = LcscPublicCatalog.NormalizeSku(scan.Sku);
            var skuChanged = !orderImport && editedSku is not null && !editedSku.Equals(parsed.Sku, StringComparison.OrdinalIgnoreCase);
            var editedQuantity = int.TryParse(scan.QuantityText, out var quantity) && quantity > 0 ? quantity : (int?)null;
            return new()
            {
                ReceiptId = scan.ReceiptId, Raw = scan.Raw, Selected = scan.Selected,
                Sku = editedSku ?? parsed.Sku, QuantityInput = editedQuantity ?? parsed.Quantity ?? double.NaN,
                LocationId = string.IsNullOrWhiteSpace(scan.LocationId) ? defaultLocation : scan.LocationId,
                Name = skuChanged ? "" : scan.Name.Length > 0 ? scan.Name : parsed.PartName ?? parsed.Model ?? "",
                Category = skuChanged ? "" : scan.Category,
                PackageName = skuChanged ? "" : scan.PackageName.Length > 0 ? scan.PackageName : parsed.PackageDetail ?? "",
                Description = skuChanged ? "" : scan.Description, Reason = parsed.ParseError ?? scan.Error, LocationOptions = locations,
            };
        }
        static string OrderReviewReason(BatchScanItem scan, BatchInboundEditableRow row, string missingMetadataReason)
        {
            // A successful lookup must not approve quantities, refunds or ambiguous source rows.
            if (scan.Status == "captured" && !string.IsNullOrWhiteSpace(scan.Error)) return scan.Error;
            if (row.Quantity is null) return "订购数量无效，请手动确认正整数数量。";
            return ReadyForReview(row) ? "" : missingMetadataReason;
        }
        void AddResult(BatchInboundEditableRow row, string reason)
        {
            row.Reason = reason;
            var target = reason.Length == 0 ? _ready : _pending;
            var other = reason.Length == 0 ? _pending : _ready;
            other.Remove(row);
            var index = target.IndexOf(row);
            if (index < 0) target.Add(row);
            else
            {
                // Recreate one-time XAML bindings after filling fields or changing the status.
                void RefreshRow()
                {
                    target.RemoveAt(index);
                    target.Insert(index, row);
                }
                if (ReferenceEquals(target, _ready)) RefreshReadySelection(RefreshRow);
                else RefreshPendingSelection(RefreshRow);
            }
        }
    }
    private static bool IsOrderImport(string raw) => raw.StartsWith("lcsc-order:", StringComparison.Ordinal);
    private async Task<LcscCatalogLookupResult> LookupCatalogAsync(string sku, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        CancelLookupButton.IsEnabled = true;
        try
        {
            return await _lookup.LookupDetailedAsync(sku, cancellationToken: cancellationToken);
        }
        finally
        {
            CancelLookupButton.IsEnabled = false;
        }
    }
    private static bool ReadyForReview(BatchInboundEditableRow x)=>x.Quantity is not null&&LcscPublicCatalog.NormalizeSku(x.Sku) is not null&&new BatchInboundMetadata(x.Name,x.Category,x.PackageName,x.Description).HasRequiredFields;
    private static bool Complete(BatchInboundEditableRow x)=>ReadyForReview(x)&&!string.IsNullOrWhiteSpace(x.LocationId);
    private static string CatalogNotes(LcscProductMetadata m){var lines=new List<string>();void Add(string? x){if(!string.IsNullOrWhiteSpace(x))lines.Add(x);}Add(m.LookupNotice);Add(m.Model is null?null:$"型号：{m.Model}");Add(m.Description is null?null:$"官方描述：{m.Description}");Add(m.Brand is null?null:$"品牌：{m.Brand}");Add($"官方商品页：{m.OfficialUrl}");Add(m.CategoryPath is null?null:$"官方分类路径：{m.CategoryPath}");Add(m.ImageUrl is null?null:$"商品图片：{m.ImageUrl}");Add(m.DatasheetUrl is null?null:$"数据手册：{m.DatasheetUrl}");if(m.Parameters is not null)foreach(var p in m.Parameters)Add($"参数·{p.Key}：{p.Value}");return string.Join("\n",lines);}
    private void Remove(string receipt){var ready=_ready.FirstOrDefault(x=>x.ReceiptId==receipt);if(ready is not null)_ready.Remove(ready);var pending=_pending.FirstOrDefault(x=>x.ReceiptId==receipt);if(pending is not null)_pending.Remove(pending);}
    private void OnCancelLookup(object sender,RoutedEventArgs e)
    {
        if (!CancelLookupButton.IsEnabled || _lookupCancellation is not { IsCancellationRequested: false }) return;
        CancelLookupButton.IsEnabled = false;
        _lookupCancellation.Cancel();
        CountText.Text = "查询已取消；当前草稿和结果已保留。";
    }
    private async void OnCreateLocation(object sender, RoutedEventArgs e)
    {
        try { await CreateInboundLocationAsync(false); }
        catch (Exception exception) { await ShowBatchErrorAsync("新建库位失败", exception.Message); }
    }
    private async Task<StorageLocationRecord?> CreateInboundLocationAsync(bool assignToSelection = false)
    {
        if (_busy) return null;
        var newLocationId = "LOC-" + Guid.NewGuid().ToString("N")[..16].ToUpperInvariant();
        var nameBox = new TextBox { Header = "新库位名称", PlaceholderText = "例如 电阻抽屉" };
        var error = new TextBlock { TextWrapping = TextWrapping.Wrap };
        var form = new StackPanel { Spacing = 8 };
        form.Children.Add(nameBox);
        form.Children.Add(error);
        StorageLocationRecord? created = null;
        var dialog = new ContentDialog
        {
            Title = "新建入库库位", Content = form, PrimaryButtonText = assignToSelection ? "新建并指派" : "新建并选择",
            CloseButtonText = "取消", XamlRoot = XamlRoot,
        };
        dialog.PrimaryButtonClick += (_, args) =>
        {
            if (ViewModel.StorageLocations.Any(item => item.Name.Equals(nameBox.Text.Trim(), StringComparison.OrdinalIgnoreCase)))
            {
                error.Text = "库位名称已存在，请选择已有库位。";
                args.Cancel = true;
                return;
            }
            var result = ViewModel.CreateStorageLocation(newLocationId, nameBox.Text);
            if (!result.IsSuccess) { error.Text = result.Message; args.Cancel = true; return; }
            created = ViewModel.StorageLocations.FirstOrDefault(item => item.Id == newLocationId);
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary || created is null) return null;
        var options = ViewModel.StorageLocations.ToArray();
        foreach (var row in _ready.Concat(_pending)) row.LocationOptions = options;
        DefaultLocationBox.ItemsSource = ViewModel.StorageLocations;
        DefaultLocationBox.SelectedItem = created;
        RefreshReadySelection(() =>
        {
            ReadyList.ItemsSource = null;
            ReadyList.ItemsSource = _ready;
        });
        RefreshPendingSelection(() =>
        {
            PendingList.ItemsSource = null;
            PendingList.ItemsSource = _pending;
        });
        return created;
    }
    private void OnApplyDefaultLocation(object sender,RoutedEventArgs e)
    {
        if (_busy) return;
        if (DefaultLocationBox.SelectedItem is not StorageLocationRecord location) return;
        EndReadyPointerSelection();
        var selectedCount = _ready.Count(row => row.Selected);
        var onlySelected = selectedCount > 0 && selectedCount < _ready.Count;
        var targets = onlySelected ? _ready.Where(row => row.Selected) : _ready.Concat(_pending);
        foreach (var row in targets) row.LocationId = location.Id;
        SaveRemaining();
        RefreshReadySelection(() =>
        {
            ReadyList.ItemsSource = null;
            ReadyList.ItemsSource = _ready;
        });
        if (!onlySelected)
        {
            RefreshPendingSelection(() =>
            {
                PendingList.ItemsSource = null;
                PendingList.ItemsSource = _pending;
            });
        }
    }
    private async void OnRetryPending(object sender, RoutedEventArgs e)
    {
        if (_busy) return;
        SetBusy(true);
        try
        {
            var selected = PendingList.SelectedItems.Cast<BatchInboundEditableRow>().ToArray();
            var manualReady = selected.Where(row => ReadyForReview(row) &&
                (IsOrderImport(row.Raw) || row.Sku.Equals(JlcQrParser.Parse(row.Raw).Sku, StringComparison.OrdinalIgnoreCase))).ToArray();
            foreach (var row in manualReady)
            {
                row.Reason = "";
                _pending.Remove(row);
                _ready.Add(row);
            }
            SaveRemaining();
            await ParseAsync(selected.Except(manualReady).Select(ToDraft));
        }
        catch (Exception exception) { await ShowBatchErrorAsync("待处理重试失败", exception.Message); }
        finally { SetBusy(false); }
    }
    private void OnRemovePending(object sender,RoutedEventArgs e){if(_busy)return;foreach(var row in PendingList.SelectedItems.Cast<BatchInboundEditableRow>().ToArray()){_pending.Remove(row);_removed.Add(row.ReceiptId);}SaveRemaining();RefreshCount();}
    private void OnDraftFieldChanged(object sender,RoutedEventArgs e)=>PersistEdits();
    private void OnDraftFieldLostFocus(object sender,RoutedEventArgs e)=>PersistEdits();
    private void PersistEdits(){if(_busy||_draftLoadFailed)return;try{SaveRemaining();}catch(Exception ex){CountText.Text=ex.Message;_=ShowBatchErrorAsync("草稿保存失败",ex.Message);}}
    private async void OnSubmit(object sender,RoutedEventArgs e)
    {if(_busy)return;SetBusy(true);try{foreach(var invalid in _ready.Where(x=>x.Selected&&!Complete(x)).ToArray()){invalid.Reason="数量、库位或资料已变为无效，请修正后重试。";_ready.Remove(invalid);_pending.Add(invalid);}var lines=_ready.Where(x=>x.Selected&&Complete(x)).Select(x=>new BatchInboundLine(x.ReceiptId,x.Raw,x.Sku,x.Quantity!.Value,x.LocationId,x.Name,x.Category,x.PackageName,x.Description)).ToArray();if(lines.Length==0){CountText.Text="没有可提交的 就绪包装。";await ShowBatchErrorAsync("无法提交",CountText.Text);SetBusy(false);return;}IReadOnlyList<BatchInboundSummary> summary;try{summary=InventoryStore.SummarizeBatchInbound(lines);}catch(OverflowException){CountText.Text="汇总数量超过整数范围。";await ShowBatchErrorAsync("无法提交",CountText.Text);SetBusy(false);return;}var dialog=new ContentDialog{Title="确认批量入库",Content=new TextBlock{Text=string.Join("\n",summary.Select(x=>$"{x.Sku} · {ViewModel.StorageLocations.FirstOrDefault(location => location.Id == x.LocationId)?.Name ?? x.LocationId} · +{x.Quantity} · {x.Packages} 包")),TextWrapping=TextWrapping.Wrap},PrimaryButtonText="确认一次提交",CloseButtonText="取消",DefaultButton=ContentDialogButton.Close,XamlRoot=XamlRoot};if(await dialog.ShowAsync()!=ContentDialogResult.Primary){SetBusy(false);return;}var result=ViewModel.CommitBatchInbound(new(lines));if(result.IsSuccess){foreach(var row in _ready.Where(x=>lines.Any(y=>y.ReceiptId==x.ReceiptId)).ToArray())_ready.Remove(row);SaveRemaining();}else await ShowBatchErrorAsync("批量入库失败",result.Message);CountText.Text=result.Message;}catch(Exception ex){await ShowBatchErrorAsync("批量入库失败",ex.Message);}finally{SetBusy(false);}}
    private async void OnClearDraft(object sender,RoutedEventArgs e)
    {
        if (_busy) return;
        var dialog = new ContentDialog { Title="清空批量入库草稿？", Content="所有尚未提交的原始包装和人工修正将被删除。", PrimaryButtonText="确认清空", CloseButtonText="取消", DefaultButton=ContentDialogButton.Close, XamlRoot=XamlRoot };
        if (await dialog.ShowAsync()!=ContentDialogResult.Primary) return;
        _generation++;_lookupCancellation?.Cancel();_drafts.Clear();_draftLoadFailed=false;_removed.Clear();_ready.Clear();_pending.Clear();RefreshCount();
    }
    private void SaveRemaining(){var shown=_ready.Concat(_pending).Select(x=>x.ReceiptId).ToHashSet();var captured=_drafts.Load().Where(x=>!shown.Contains(x.ReceiptId)&&!_removed.Contains(x.ReceiptId)&&!ViewModel.IsBatchReceiptCommitted(x.ReceiptId));_drafts.Save(captured.Concat(_ready.Concat(_pending).Select(ToDraft)).ToArray());}
    private void RefreshCount()=>CountText.Text=$"草稿 {_drafts.Load().Count}/500 条 · 待核对 {_ready.Count} · 待处理 {_pending.Count}";
    private void OnUnloaded(object sender,RoutedEventArgs e){EndReadyPointerSelection(false);EndPendingPointerSelection();CancelLookupButton.IsEnabled=false;_generation++;_lookupCancellation?.Cancel();if(_draftLoadFailed)return;try{SaveRemaining();}catch(Exception ex){CountText.Text=ex.Message;}}
    private static BatchScanItem ToDraft(BatchInboundEditableRow x)=>new(x.ReceiptId,x.Raw){Status=x.Reason.Length==0&&ReadyForReview(x)?"ready":"pending",Selected=x.Selected,Sku=x.Sku,QuantityText=x.Quantity?.ToString()??"",LocationId=x.LocationId,Name=x.Name,Category=x.Category,PackageName=x.PackageName,Description=x.Description,Error=x.Reason};
    private static BatchInboundEditableRow FromDraft(BatchScanItem x,IReadOnlyList<StorageLocationRecord> locations)=>new(){ReceiptId=x.ReceiptId,Raw=x.Raw,Selected=x.Selected,Sku=x.Sku,QuantityInput=int.TryParse(x.QuantityText,out var q)?q:double.NaN,LocationId=x.LocationId,Name=x.Name,Category=x.Category,PackageName=x.PackageName,Description=x.Description,Reason=x.Error,LocationOptions=locations};
    private void SetBusy(bool value){if(value){EndReadyPointerSelection();EndPendingPointerSelection();}_busy=value;ImportOrdersButton.IsEnabled=!value;HeaderBackButton.IsEnabled=!value;CollectButton.IsEnabled=!value;ParseButton.IsEnabled=!value;ClearButton.IsEnabled=!value;ApplyLocationButton.IsEnabled=!value;SubmitButton.IsEnabled=!value;RetryButton.IsEnabled=!value;RemoveButton.IsEnabled=!value;CaptureBox.IsEnabled=!value;ReadyList.IsEnabled=!value;PendingList.IsEnabled=!value;DefaultLocationBox.IsEnabled=!value;CreateLocationButton.IsEnabled=!value;UpdateReadySelectionState();UpdatePendingSelectionState();}
    private async Task ShowBatchErrorAsync(string title,string message)=>await new ContentDialog{Title=title,Content=new TextBlock{Text=message,TextWrapping=TextWrapping.Wrap},CloseButtonText="关闭",XamlRoot=XamlRoot}.ShowAsync();
}
