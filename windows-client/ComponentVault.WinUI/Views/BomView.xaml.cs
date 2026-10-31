using System.Security.Cryptography;
using System.Text.Json;
using ComponentVault.WinUI.Services.Bom;
using ComponentVault.WinUI.ViewModels;
using ComponentVault.WinUI.Models;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Storage.Pickers;
using Windows.Storage;
using WinRT.Interop;

namespace ComponentVault.WinUI.Views;

public sealed partial class BomView : Page
{
    private readonly BomFileReader _reader = new();
    private readonly BomPlanningService _planner = new();
    private readonly BomPresetStore _presetStore = new();
    private readonly Dictionary<int, string> _selections = [];
    private readonly HashSet<int> _skippedRows = [];
    private BomDocument? _document;
    private BomPreset? _loadedPreset;
    private BomPreview? _preview;
    private BomTableInspection? _inspection;
    private BomColumnMapping? _mapping;
    private string? _filePath;
    private string _releaseId = Guid.NewGuid().ToString("N");
    private string _batchId = NewBatchId();
    private bool _confirmed;
    private MainViewModel ViewModel => ((App)Application.Current).MainViewModel!;

    public BomView()
    {
        InitializeComponent();
        NavigationCacheMode = Microsoft.UI.Xaml.Navigation.NavigationCacheMode.Required;
        Loaded += async (_, _) =>
        {
            try { RefreshPresets(); }
            catch (Exception error) { await ShowMessageAsync("预设读取失败", error.Message); }
        };
    }

    private void RefreshPresets(string? selected = null)
    {
        var names = _presetStore.LoadAll().ToArray();
        PresetBox.ItemsSource = names;
        PresetBox.SelectedItem = names.FirstOrDefault(name => string.Equals(name.Id, selected, StringComparison.Ordinal));
        UpdatePresetButtons();
    }

    private void OnPresetSelectionChanged(object sender, SelectionChangedEventArgs e) => UpdatePresetButtons();

    private void UpdatePresetButtons()
    {
        var selected = PresetBox.SelectedItem is BomPreset;
        LoadPresetButton.IsEnabled = selected;
        DeletePresetButton.IsEnabled = selected;
    }

    private async void OnLoadPresetClicked(object sender, RoutedEventArgs e)
    {
        if (PresetBox.SelectedItem is not BomPreset selectedPreset) return;
        if (!await ConfirmReplaceDraftAsync()) return;
        try
        {
            var preset = _presetStore.LoadAll().First(item => item.Id == selectedPreset.Id);
            _document = null;
            ProjectNameBox.Text = preset.ProjectName;
            BatchQuantityBox.Value = preset.BatchQuantity;
            _document = preset.Document;
            _loadedPreset = preset;
            _filePath = null;
            _inspection = null;
            _mapping = null;
            MappingExpander.IsEnabled = false;
            MappingExpander.IsExpanded = false;
            _selections.Clear();
            foreach (var pair in preset.SelectedComponents) _selections[pair.Key] = pair.Value;
            _skippedRows.Clear();
            foreach (var row in preset.SkippedRows) _skippedRows.Add(row);
            ResetBatchIdentity();
            RefreshPreview();
        }
        catch (Exception error) { await ShowMessageAsync("预设加载失败", error.Message); }
    }

    private async void OnDeletePresetClicked(object sender, RoutedEventArgs e)
    {
        if (PresetBox.SelectedItem is not BomPreset selectedPreset) return;
        var confirm = new ContentDialog { Title = "删除 BOM 预设", Content = $"删除“{selectedPreset.Name}”？已加载的本次预览仍可继续使用。", PrimaryButtonText = "删除", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close, XamlRoot = XamlRoot };
        if (await confirm.ShowAsync() != ContentDialogResult.Primary) return;
        try { _presetStore.DeleteById(selectedPreset.Id); if (_loadedPreset?.Id == selectedPreset.Id) _loadedPreset = null; RefreshPresets(); }
        catch (Exception error) { await ShowMessageAsync("预设删除失败", error.Message); }
    }

    private async void OnSavePresetClicked(object sender, RoutedEventArgs e)
    {
        if (_document is null || _confirmed) return;
        var quantity = BatchQuantityBox.Value;
        if (string.IsNullOrWhiteSpace(ProjectNameBox.Text) || !double.IsFinite(quantity) || quantity < 1 || quantity > int.MaxValue || quantity != Math.Truncate(quantity))
        {
            await ShowMessageAsync("无法保存预设", "请先填写项目名称和有效的生产批数。");
            return;
        }
        var nameBox = new TextBox { Header = "预设名称", Text = _loadedPreset?.Name ?? ProjectNameBox.Text.Trim(), MinWidth = 360 };
        var dialog = new ContentDialog { Title = "保存 BOM 预设", Content = nameBox, PrimaryButtonText = "保存", CloseButtonText = "取消", XamlRoot = XamlRoot };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        var name = nameBox.Text.Trim();
        if (name.Length == 0) { await ShowMessageAsync("无法保存预设", "请输入预设名称。"); return; }
        try
        {
            var all = _presetStore.LoadAll();
            var targetId = _loadedPreset?.Id;
            if (targetId is null)
            {
                var matches = all.Where(item => string.Equals(item.Name, name, StringComparison.OrdinalIgnoreCase)).ToArray();
                if (matches.Length > 1)
                {
                    await ShowMessageAsync("请选择保存目标", "存在多个同名预设，无法确定覆盖目标。请使用不同名称保存，或先加载需要更新的具体预设。");
                    return;
                }
                targetId = matches.SingleOrDefault()?.Id;
            }
            if (targetId is not null)
            {
                var target = all.FirstOrDefault(item => item.Id == targetId);
                var overwrite = new ContentDialog { Title = "更新已有预设", Content = $"将当前 BOM 行、匹配与跳过状态保存到“{target?.Name ?? _loadedPreset?.Name ?? name}”，名称设为“{name}”？", PrimaryButtonText = "保存更新", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close, XamlRoot = XamlRoot };
                if (await overwrite.ShowAsync() != ContentDialogResult.Primary) return;
            }
            targetId ??= Guid.NewGuid().ToString("N");
            _presetStore.Save(new(name, _document, ProjectNameBox.Text.Trim(), (int)quantity,
                new Dictionary<int, string>(_selections), _skippedRows.Order().ToList(), targetId));
            _loadedPreset = _presetStore.LoadAll().Single(item => item.Id == targetId);
            RefreshPresets(targetId);
            await ShowMessageAsync("预设已保存", $"“{name}”已保存。下次加载会重新核对当前库存。");
        }
        catch (Exception error) { await ShowMessageAsync("预设保存失败", error.Message); }
    }

    private async void OnChooseFileClicked(object sender, RoutedEventArgs e)
    {
        var picker = new FileOpenPicker();
        picker.FileTypeFilter.Add(".csv");
        picker.FileTypeFilter.Add(".xlsx");
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(((App)Application.Current).Window));
        var file = await picker.PickSingleFileAsync();
        if (file is null) return;
        if (!await ConfirmReplaceDraftAsync()) return;
        try
        {
            _filePath = file.Path;
            string? sheet = null;
            if (file.FileType.Equals(".xlsx", StringComparison.OrdinalIgnoreCase))
            {
                var names = _reader.GetWorksheetNames(file.Path);
                if (names.Count > 1) sheet = await ChooseSheetAsync(names);
                if (names.Count > 1 && sheet is null) return;
            }
            _inspection = _reader.Inspect(file.Path, sheet);
            _loadedPreset = null;
            _mapping = _inspection.AutomaticMapping;
            MappingExpander.IsEnabled = true;
            UpdateMappingSummary();
            if (_mapping.Quantity is null || _mapping.Sku is null && _mapping.Model is null && _mapping.Name is null)
            {
                _document = null;
                _selections.Clear();
                _skippedRows.Clear();
                ResetBatchIdentity();
                PreviewList.ItemsSource = null;
                MappingExpander.IsExpanded = true;
                ExportButton.IsEnabled = false;
                ConfirmButton.IsEnabled = false;
                SummaryText.Text = $"{_inspection.SourceName} · 未识别必需列，请展开列识别并调整。";
                await ShowMessageAsync("需要列映射", "请选择需求数量列，并至少选择 SKU、型号或名称列。原文件已保留，可直接继续映射。");
                return;
            }
            _document = _reader.Read(file.Path, sheet, _mapping);
            _selections.Clear();
            _skippedRows.Clear();
            ResetBatchIdentity();
            RefreshPreview();
        }
        catch (Exception exception) { await ShowMessageAsync("BOM 读取失败", exception.Message); }
    }

    private void RefreshPreview()
    {
        if (_document is null) return;
        var value = BatchQuantityBox.Value;
        var batches = double.IsFinite(value) && value >= 1 && value <= int.MaxValue && value == Math.Truncate(value)
            ? (int)value : 0;
        var selectedRow = (PreviewList.SelectedItem as BomRowItem)?.RowNumber;
        _preview = ViewModel.CreateBomPreview(_document, ProjectNameBox.Text, batches, _selections, _skippedRows);
        var items = _document.Rows.Select(row => new BomRowItem(row.RowNumber, FormatRow(row, _preview))).ToArray();
        PreviewList.ItemsSource = items;
        PreviewList.SelectedItem = items.FirstOrDefault(item => item.RowNumber == selectedRow);
        var pending = _preview.Issues.Count(issue => issue.RowNumber is not null && issue.Code is "unmatched" or "ambiguous" or "missing_identity");
        SummaryText.Text = $"{_document.SourceName}{(_document.WorksheetName is null ? "" : $" / {_document.WorksheetName}")} · 共 {_document.Rows.Count} 行 · 待匹配 {pending} 行 · 跳过 {_skippedRows.Count} 行 · 出库 {_preview.Lines.Count} 项";
        MappingButton.IsEnabled = _inspection is not null && !_confirmed;
        ExportButton.IsEnabled = _preview is not null;
        ConfirmButton.IsEnabled = _preview.CanConfirm && !_confirmed;
        SavePresetButton.IsEnabled = !_confirmed;
        AddMaterialButton.IsEnabled = !_confirmed;
        ProjectNameBox.IsEnabled = BatchQuantityBox.IsEnabled = !_confirmed;
        PlanText.Text = string.Join("\n", _preview.Lines.Select(line => $"{PreviewIdentity(line)} · 每套 {line.UnitQuantity} × {batches} 套 = {line.RequiredQuantity} · 库存 {line.AvailableQuantity}\n库位：{AllocationPlan(line)}"));
        if (_preview.Issues.Count > 0) PlanText.Text += "\n" + string.Join("\n", _preview.Issues.Select(issue => issue.Message));
        UpdateSelectionButtons();
    }

    private string FormatRow(BomSourceRow row, BomPreview preview)
    {
        var identity = string.Join(" · ", new[] { row.Sku, row.SupplierPartNumber, row.Name, row.PackageName, row.Reference }
            .Where(value => !string.IsNullOrWhiteSpace(value)).Distinct());
        if (_skippedRows.Contains(row.RowNumber)) return $"跳过 · 第 {row.RowNumber} 行 · {identity} · 单批 {row.Quantity?.ToString() ?? "?"}";
        var issue = preview.Issues.FirstOrDefault(item => item.RowNumber == row.RowNumber);
        if (issue is not null) return $"待处理 · 第 {row.RowNumber} 行 · {identity} · {issue.Message}";
        var line = preview.Lines.FirstOrDefault(item => item.SourceRows.Contains(row.RowNumber));
        if (line is null) return $"待处理 · 第 {row.RowNumber} 行 · {identity}";
        return $"已匹配 · 第 {row.RowNumber} 行 · {identity} → {line.ComponentSku} · 单批 {row.Quantity} · 总需 {line.RequiredQuantity} · 库存 {line.AvailableQuantity}";
    }

    private void UpdateMappingSummary()
    {
        if (_inspection is null || _mapping is null) return;
        string Label(int? index) => index is { } value && value < _inspection.Headers.Count
            ? $"{_inspection.Headers[value]}（第 {value + 1} 列）" : "未识别";
        MappingSummaryText.Text = $"数量：{Label(_mapping.Quantity)} · 库存编码：{Label(_mapping.Sku)} · 型号：{Label(_mapping.Model)}\n封装：{Label(_mapping.Package)} · 名称：{Label(_mapping.Name)} · 位号：{Label(_mapping.Reference)}";
    }

    private void OnPreviewSelectionChanged(object sender, SelectionChangedEventArgs e) => UpdateSelectionButtons();

    private void UpdateSelectionButtons()
    {
        var row = (PreviewList.SelectedItem as BomRowItem)?.RowNumber;
        ResolveButton.IsEnabled = row is not null && !_confirmed && !_skippedRows.Contains(row.Value);
        SkipButton.IsEnabled = row is not null && !_confirmed && !_skippedRows.Contains(row.Value);
        RestoreButton.IsEnabled = row is not null && !_confirmed && _skippedRows.Contains(row.Value);
        EditQuantityButton.IsEnabled = row is not null && !_confirmed;
        SourceRowButton.IsEnabled = row is not null;
        SkipButton.Visibility = row is not null && _skippedRows.Contains(row.Value) ? Visibility.Collapsed : Visibility.Visible;
        RestoreButton.Visibility = row is not null && _skippedRows.Contains(row.Value) ? Visibility.Visible : Visibility.Collapsed;
    }

    private void OnSkipClicked(object sender, RoutedEventArgs e)
    {
        if ((PreviewList.SelectedItem as BomRowItem)?.RowNumber is not { } row || _confirmed) return;
        _skippedRows.Add(row);
        RefreshPreview();
    }

    private void OnRestoreClicked(object sender, RoutedEventArgs e)
    {
        if ((PreviewList.SelectedItem as BomRowItem)?.RowNumber is not { } row || _confirmed) return;
        _skippedRows.Remove(row);
        RefreshPreview();
    }

    private string PreviewIdentity(BomConsumptionLine line)
    {
        var source = _document?.Rows.FirstOrDefault(row => line.SourceRows.Contains(row.RowNumber));
        return string.Join(" · ", new[] { line.ComponentSku, source?.SupplierPartNumber, source?.PackageName }.Where(value => !string.IsNullOrWhiteSpace(value)).Distinct());
    }

    private string AllocationPlan(BomConsumptionLine line)
    {
        var component = ViewModel.AvailableComponents.FirstOrDefault(item => item.Id == line.ComponentId);
        if (component is null) return "不可用";
        var remaining = line.RequiredQuantity;
        var parts = new List<string>();
        foreach (var allocation in component.Allocations.Where(item => item.Quantity > 0).OrderBy(item => item.LocationId, StringComparer.Ordinal))
        {
            if (remaining == 0) break;
            var take = Math.Min(remaining, allocation.Quantity);
            parts.Add($"{allocation.LocationName ?? allocation.LocationId} × {take}");
            remaining -= take;
        }
        return remaining == 0 ? string.Join("，", parts) : "分配不足";
    }

    private async void OnResolveClicked(object sender, RoutedEventArgs e)
    {
        if (_preview is null || _document is null || (PreviewList.SelectedItem as BomRowItem)?.RowNumber is not { } rowNumber) return;
        var row = _document.Rows.First(item => item.RowNumber == rowNumber);
        var group = _preview.SelectionGroups?.Values.FirstOrDefault(rows => rows.Contains(rowNumber)) ?? [rowNumber];
        var panel = new StackPanel { Spacing = 12, Width = 520 };
        panel.Children.Add(new TextBlock
        {
            Text = $"第 {string.Join("、", group)} 行 · {string.Join(" · ", new[] { row.Sku, row.SupplierPartNumber, row.Name, row.PackageName, row.Reference }.Where(value => !string.IsNullOrWhiteSpace(value)))}",
            TextWrapping = TextWrapping.Wrap,
        });
        panel.Children.Add(new TextBlock { Text = "输入 SKU、型号、封装或名称，选择一个库存项。" });
        var box = new AutoSuggestBox { PlaceholderText = "搜索库存", HorizontalAlignment = HorizontalAlignment.Stretch };
        BomMatchCandidate? choice = null;
        box.TextChanged += (_, args) =>
        {
            if (args.Reason == AutoSuggestionBoxTextChangeReason.UserInput)
            {
                choice = null;
                box.ItemsSource = _planner.SearchInventory(ViewModel.AvailableComponents, box.Text);
            }
        };
        box.SuggestionChosen += (_, args) => choice = args.SelectedItem as BomMatchCandidate;
        var initial = row.Sku ?? row.SupplierPartNumber ?? row.Name;
        if (!string.IsNullOrWhiteSpace(initial))
        {
            box.Text = initial;
            box.ItemsSource = _planner.SearchInventory(ViewModel.AvailableComponents, initial);
        }
        panel.Children.Add(box);
        var dialog = new ContentDialog { Title = "选择库存匹配", Content = panel, PrimaryButtonText = "应用", CloseButtonText = "取消", XamlRoot = XamlRoot };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        if (choice is null)
        {
            await ShowMessageAsync("尚未选择库存", "请从搜索建议中选择一个库存项，然后应用。");
            return;
        }
        foreach (var sourceRow in group) _selections[sourceRow] = choice.ComponentId;
        RefreshPreview();
    }

    private async void OnConfirmClicked(object sender, RoutedEventArgs e)
    {
        RefreshPreview();
        if (_confirmed || _preview is not { CanConfirm: true } preview || _document is null) return;
        var dialog = new ContentDialog { Title = "最终确认出库", Content = new ScrollViewer { MaxHeight = 420, Content = new TextBlock { Text = $"项目：{preview.ProjectName}\n套数：{preview.BatchQuantity}\n出库库存项：{preview.Lines.Count}\n明确跳过：{_skippedRows.Count} 行\n{PlanText.Text}\n本次会一次性扣减全部匹配项。", TextWrapping = TextWrapping.Wrap } }, PrimaryButtonText = "确认出库", CloseButtonText = "返回检查", DefaultButton = ContentDialogButton.Close, XamlRoot = XamlRoot };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        var hash = Convert.ToHexString(SHA256.HashData(JsonSerializer.SerializeToUtf8Bytes(_document)));
        var result = ViewModel.ConfirmBomConsumption(new(_releaseId, _batchId, preview.ProjectName, preview.BatchQuantity, hash, preview.Lines));
        if (result.IsSuccess) _confirmed = true;
        await ShowMessageAsync(result.IsSuccess ? "BOM 扣减完成" : "BOM 扣减失败", result.Message);
        RefreshPreview();
    }

    private void OnInputsChanged(object sender, TextChangedEventArgs e) => RefreshPreview();

    private async Task<bool> ConfirmReplaceDraftAsync()
    {
        if (_document is null || _document.Rows.Count == 0 || _confirmed) return true;
        return await new ContentDialog { Title = "替换当前用料清单？", Content = "当前尚未确认出库，继续将替换本次选料、匹配与跳过状态。库存尚未扣减。", PrimaryButtonText = "替换清单", CloseButtonText = "保留当前清单", DefaultButton = ContentDialogButton.Close, XamlRoot = XamlRoot }.ShowAsync() == ContentDialogResult.Primary;
    }

    private async void OnManualStartClicked(object sender, RoutedEventArgs e)
    {
        if (!await ConfirmReplaceDraftAsync()) return;
        _document = null;
        _loadedPreset = null;
        _inspection = null;
        _mapping = null;
        _filePath = null;
        _selections.Clear();
        _skippedRows.Clear();
        ProjectNameBox.Text = "项目用料";
        BatchQuantityBox.Value = 1;
        _document = new("手动选料", null, [], []);
        MappingExpander.IsEnabled = MappingExpander.IsExpanded = false;
        ResetBatchIdentity();
        RefreshPreview();
        OnAddMaterialClicked(sender, e);
    }

    private static bool PositiveInt(double value) => double.IsFinite(value) && value >= 1 && value <= int.MaxValue && value == Math.Truncate(value);

    private async void OnAddMaterialClicked(object sender, RoutedEventArgs e)
    {
        if (_document is null || _confirmed) return;
        BomMatchCandidate? choice = null;
        var search = new AutoSuggestBox { Header = "库存项", PlaceholderText = "搜索 SKU、型号、封装或名称", MinWidth = 400 };
        search.TextChanged += (_, args) => { if (args.Reason == AutoSuggestionBoxTextChangeReason.UserInput) { choice = null; search.ItemsSource = _planner.SearchInventory(ViewModel.AvailableComponents, search.Text); } };
        search.SuggestionChosen += (_, args) => choice = args.SelectedItem as BomMatchCandidate;
        var quantity = new NumberBox { Header = "每套用量", Value = 1, Minimum = 1, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Inline };
        var panel = new StackPanel { Spacing = 12 }; panel.Children.Add(search); panel.Children.Add(quantity);
        panel.Children.Add(new TextBlock { Text = "重复选择同一库存项会保留为独立用料行，出库时合计用量。", TextWrapping = TextWrapping.Wrap });
        var dialog = new ContentDialog { Title = "添加用料", Content = panel, PrimaryButtonText = "添加", CloseButtonText = "取消", XamlRoot = XamlRoot };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        if (choice is null || !PositiveInt(quantity.Value)) { await ShowMessageAsync("无法添加", "请选择库存项并填写正整数用量。"); return; }
        var number = _document.Rows.Count == 0 ? 1 : _document.Rows.Max(row => row.RowNumber) + 1;
        _document = _document with { Rows = _document.Rows.Append(new BomSourceRow(number, choice.Sku, choice.Model, choice.PackageName, (int)quantity.Value, choice.Name, PresetLineId: Guid.NewGuid().ToString("N"))).ToArray() };
        _selections[number] = choice.ComponentId;
        RefreshPreview();
    }

    private async void OnEditQuantityClicked(object sender, RoutedEventArgs e)
    {
        if (_document is null || _confirmed || (PreviewList.SelectedItem as BomRowItem)?.RowNumber is not { } number) return;
        var source = _document.Rows.First(row => row.RowNumber == number);
        var quantity = new NumberBox { Header = "每套用量", Value = source.Quantity ?? double.NaN, Minimum = 1, MinWidth = 360 };
        var dialog = new ContentDialog { Title = $"第 {number} 行用量", Content = quantity, PrimaryButtonText = "应用", CloseButtonText = "取消", XamlRoot = XamlRoot };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        if (!PositiveInt(quantity.Value)) { await ShowMessageAsync("无效用量", "请填写正整数用量。"); return; }
        _document = _document with { Rows = _document.Rows.Select(row => row.RowNumber == number ? row with { Quantity = (int)quantity.Value } : row).ToArray() };
        RefreshPreview();
    }

    private async void OnSourceRowClicked(object sender, RoutedEventArgs e)
    {
        if (_document is null || (PreviewList.SelectedItem as BomRowItem)?.RowNumber is not { } number) return;
        var index = _document.Rows.ToList().FindIndex(row => row.RowNumber == number);
        var row = _document.Rows[index];
        var original = _document.PresetContent?.Lines.ElementAtOrDefault(index)?.SourceRows;
        var text = $"来源：{_document.SourceName}\n工作表：{_document.WorksheetName}\n清单行：{number}\nSKU：{row.Sku}\n型号：{row.SupplierPartNumber}\n名称：{row.Name}\n封装：{row.PackageName}\n位号：{row.Reference}";
        if (original is not null) text += "\n\n" + string.Join("\n\n", original.Select(source => $"{source.SheetName} · 原始第 {source.RowNumber} 行 · 数量 {source.QuantityPerSet}\n" + string.Join("\n", source.Fields.Select(field => $"{field.Key}：{field.Value}"))));
        await new ContentDialog { Title = "用料来源", Content = new ScrollViewer { MaxHeight = 420, Content = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true } }, CloseButtonText = "关闭", XamlRoot = XamlRoot }.ShowAsync();
    }

    private void OnBatchQuantityChanged(NumberBox sender, NumberBoxValueChangedEventArgs args) => RefreshPreview();

    private void OnNewBatchClicked(object sender, RoutedEventArgs e)
    {
        ResetBatchIdentity();
        RefreshPreview();
    }

    private async void OnMappingClicked(object sender, RoutedEventArgs e)
    {
        if (_inspection is null || _filePath is null) return;
        var panel = new StackPanel { Spacing = 8, Width = 520 };
        var choices = _inspection.Headers.Select((header, index) =>
        {
            var sample = _inspection.Samples?.ElementAtOrDefault(index);
            if (sample?.Length > 28) sample = sample[..28] + "…";
            return new ColumnChoice(index, $"{index + 1}. {(string.IsNullOrWhiteSpace(header) ? "未命名" : header)}{(string.IsNullOrWhiteSpace(sample) ? "" : $" · 例：{sample}")}");
        }).ToArray();
        ComboBox Add(string label, int? selected, bool optional)
        {
            panel.Children.Add(new TextBlock { Text = label });
            var items = optional ? new[] { new ColumnChoice(null, "不使用") }.Concat(choices).ToArray() : choices;
            var box = new ComboBox { ItemsSource = items, DisplayMemberPath = nameof(ColumnChoice.Label), SelectedItem = items.FirstOrDefault(item => item.Index == selected), HorizontalAlignment = HorizontalAlignment.Stretch };
            panel.Children.Add(box); return box;
        }
        var sku = Add("SKU", _mapping?.Sku, true); var model = Add("型号", _mapping?.Model, true);
        var quantity = Add("需求数量（必选）", _mapping?.Quantity, false); var package = Add("封装", _mapping?.Package, true);
        var name = Add("名称", _mapping?.Name, true); var reference = Add("位号", _mapping?.Reference, true);
        var dialog = new ContentDialog { Title = "调整 BOM 列映射", Content = new ScrollViewer { Content = panel, MaxHeight = 460, VerticalScrollBarVisibility = ScrollBarVisibility.Auto }, PrimaryButtonText = "重新预览", CloseButtonText = "取消", XamlRoot = XamlRoot };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        try
        {
            var mapping = new BomColumnMapping(((ColumnChoice?)sku.SelectedItem)?.Index, ((ColumnChoice?)model.SelectedItem)?.Index, ((ColumnChoice?)package.SelectedItem)?.Index, ((ColumnChoice?)quantity.SelectedItem)?.Index, ((ColumnChoice?)name.SelectedItem)?.Index, ((ColumnChoice?)reference.SelectedItem)?.Index);
            mapping.Validate(_inspection.Headers.Count, _inspection.Headers);
            var confirm = new ContentDialog { Title = "重新读取并替换完整清单？", Content = "重新映射会从原文件重建清单，替换后来手动添加的行、用量修改、库存匹配和跳过状态。取消可保留当前完整清单。", PrimaryButtonText = "重新读取并替换", CloseButtonText = "保留当前清单", DefaultButton = ContentDialogButton.Close, XamlRoot = XamlRoot };
            if (await confirm.ShowAsync() != ContentDialogResult.Primary) return;
            var document = _reader.Read(_filePath, _inspection.WorksheetName, mapping);
            _mapping = mapping;
            _document = document;
            _selections.Clear(); _skippedRows.Clear(); ResetBatchIdentity(); UpdateMappingSummary(); RefreshPreview();
        }
        catch (Exception exception) { await ShowMessageAsync("列映射无效", exception.Message); }
    }

    private async void OnExportClicked(object sender, RoutedEventArgs e)
    {
        if (_document is null || _preview is null) return;
        var picker = new FileSavePicker { SuggestedFileName = $"{Path.GetFileNameWithoutExtension(_document.SourceName)}-缺料" };
        picker.FileTypeChoices.Add("CSV", new List<string> { ".csv" });
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(((App)Application.Current).Window));
        var file = await picker.PickSaveFileAsync(); if (file is null) return;
        await FileIO.WriteBytesAsync(file, BomShortageCsvExporter.Export(_document, _preview));
        var count = BomShortageCsvExporter.ShortageCount(_document, _preview);
        await ShowMessageAsync("CSV 已导出", count == 0 ? "当前预览没有缺料。" : $"已导出 {count} 项缺料或未匹配记录。");
    }

    private void ResetBatchIdentity()
    {
        _releaseId = Guid.NewGuid().ToString("N");
        _batchId = NewBatchId();
        _confirmed = false;
    }

    private static string NewBatchId() => $"bom-{DateTimeOffset.UtcNow:yyyyMMddHHmmss}-{Guid.NewGuid():N}";

    private async Task<string?> ChooseSheetAsync(IReadOnlyList<string> names)
    {
        var box = new ComboBox { ItemsSource = names, SelectedIndex = 0, MinWidth = 360 };
        var dialog = new ContentDialog { Title = "选择工作表", Content = box, PrimaryButtonText = "读取", CloseButtonText = "取消", XamlRoot = XamlRoot };
        return await dialog.ShowAsync() == ContentDialogResult.Primary ? box.SelectedItem as string : null;
    }

    private async Task ShowMessageAsync(string title, string message) => await new ContentDialog { Title = title, Content = message, CloseButtonText = "关闭", XamlRoot = XamlRoot }.ShowAsync();

    private sealed record ColumnChoice(int? Index, string Label);

    private sealed record BomRowItem(int RowNumber, string Label)
    {
        public override string ToString() => Label;
    }
}
