using ComponentVault.WinUI.Design;
using ComponentVault.WinUI.Localization;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.ViewModels;
using ComponentVault.WinUI.Services.Updates;
using ComponentVault.WinUI.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using System.Runtime.InteropServices;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace ComponentVault.WinUI.Views;

public sealed partial class SettingsView : Page
{
    private readonly GitHubReleaseClient _releaseClient = new();
    private CancellationTokenSource? _updateCancellation;
    private GitHubReleaseInfo? _latestRelease;
    private UpdateChannel _updateChannel = UpdateChannel.Stable;
    private IReadOnlyList<StorageLocationRecord> _labelStorageLocations = [];
    private MainViewModel? RuntimeViewModel => ViewModelResolver.GetRuntimeViewModel(DataContext);

    private SyncConfiguration? CurrentSyncConfiguration =>
        DataContext switch
        {
            MainViewModel runtime => runtime.SyncConfiguration,
            DesignMainViewModel design => design.SyncConfiguration,
            _ => null,
        };

    public SettingsView()
    {
        InitializeComponent();
        DataContext = ViewModelResolver.ResolveMainViewModel();
        CurrentVersionText.Text = AppVersion.Current;
        _updateChannel = UpdatePreferences.LoadChannel();
        UpdateChannelComboBox.SelectedIndex = _updateChannel == UpdateChannel.Dev ? 1 : 0;
        UpdateChannelHint.Text = ChannelHint();
        LoadValuesFromCurrentContext();
    }

    private void LoadValuesFromCurrentContext()
    {
        var sync = CurrentSyncConfiguration;
        if (sync is null)
        {
            return;
        }

        ServerUrlTextBox.Text = sync.ServerBaseUrl;
        FallbackServerUrlTextBox.Text = sync.FallbackServerBaseUrl;
        ApiTokenBox.Password = sync.ApiToken;
        ApiTokenBox.PasswordRevealMode = PasswordRevealMode.Hidden;
        ToggleApiTokenButton.Content = "显示令牌";
        ApiTokenCopyStatusText.Text = string.Empty;
        DeviceIdTextBox.Text = sync.DeviceId;

        AutoSyncToggle.IsOn = sync.AutoSyncEnabled;
    }

    private void OnCancelSyncClicked(object sender, RoutedEventArgs e) => RuntimeViewModel?.CancelSync();

    private async void OnClearPhotoCacheClicked(object sender, RoutedEventArgs e)
    {
        if (RuntimeViewModel is not { } viewModel || viewModel.IsBusy) return;
        await ShowMessageAsync("照片缓存", viewModel.ClearPhotoCache());
    }

    private async void OnSaveSettingsClicked(object sender, RoutedEventArgs e)
    {
        var viewModel = RuntimeViewModel;
        if (viewModel is null || viewModel.IsBusy)
        {
            return;
        }

        var result = viewModel.SaveSyncConfiguration(
            ServerUrlTextBox.Text,
            FallbackServerUrlTextBox.Text,
            ApiTokenBox.Password,
            AutoSyncToggle.IsOn
        );
        LoadValuesFromCurrentContext();
        await ShowMessageAsync(
            result.IsSuccess
                ? AppStrings.Get("Settings_Dialog_SaveSuccessTitle")
                : AppStrings.Get("Settings_Dialog_SaveFailureTitle"),
            result.Message
        );
    }

    private async void OnTestConnectionClicked(object sender, RoutedEventArgs e)
    {
        var viewModel = RuntimeViewModel;
        if (viewModel is null || viewModel.IsBusy)
        {
            return;
        }

        var result = await viewModel.TestConnectionAsync(
            ServerUrlTextBox.Text,
            FallbackServerUrlTextBox.Text,
            ApiTokenBox.Password,
            AutoSyncToggle.IsOn
        );
        await ShowMessageAsync(
            result.IsSuccess
                ? AppStrings.Get("Settings_Dialog_TestSuccessTitle")
                : AppStrings.Get("Settings_Dialog_TestFailureTitle"),
            result.Message
        );
    }

    private async void OnSyncNowClicked(object sender, RoutedEventArgs e)
    {
        var viewModel = RuntimeViewModel;
        if (viewModel is null || viewModel.IsBusy)
        {
            return;
        }

        if (!viewModel.SyncConfiguration.MatchesConnectionDraft(
                ServerUrlTextBox.Text,
                FallbackServerUrlTextBox.Text,
                ApiTokenBox.Password,
                AutoSyncToggle.IsOn))
        {
            await ShowMessageAsync("请先保存设置", "当前连接设置有未保存的修改。保存后再同步，避免把草稿误用于正式同步。");
            return;
        }
        var result = await viewModel.RunSyncAsync();
        await ShowMessageAsync(
            result.IsPartial ? "同步部分完成" : result.IsSuccess
                ? AppStrings.Get("Settings_Dialog_SyncSuccessTitle")
                : AppStrings.Get("Settings_Dialog_SyncFailureTitle"),
            result.IsSuccess ? viewModel.SyncConfiguration.LastSyncMessage : result.Message
        );
    }

    private async void OnExportInventoryClicked(object sender, RoutedEventArgs e)
    {
        if (RuntimeViewModel is not { } viewModel) return;
        var picker = new FileSavePicker { SuggestedFileName = $"component-vault-{DateTime.Now:yyyyMMdd-HHmmss}" };
        picker.FileTypeChoices.Add("Excel 工作簿", new List<string> { ".xlsx" });
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(((App)Application.Current).Window));
        var file = await picker.PickSaveFileAsync();
        if (file is null) return;
        try { viewModel.ExportInventoryWorkbook(file.Path); await ShowMessageAsync("备份完成", "库存、独立库位、分配和流水已导出；未包含同步凭据，也没有执行联网补全。"); }
        catch (Exception exception) { await ShowMessageAsync("备份失败", exception.Message); }
    }

    private async void OnImportInventoryClicked(object sender, RoutedEventArgs e)
    {
        if (RuntimeViewModel is not { } viewModel) return;
        var picker = new FileOpenPicker(); picker.FileTypeFilter.Add(".xlsx");
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(((App)Application.Current).Window));
        var file = await picker.PickSingleFileAsync(); if (file is null) return;
        var preview = viewModel.PreviewInventoryWorkbook(file.Path);
        var dialog = new ContentDialog
        {
            Title = $"恢复预览 · {preview.SourceFormat}",
            Content = new TextBlock { Text = $"元器件 {preview.Components}（可新增 {preview.NewComponents}，冲突跳过 {preview.ConflictingComponents}） · 库位 {preview.Locations} · 分配 {preview.Allocations} · 流水 {preview.Movements}\n" + (preview.Issues.Count == 0 ? "默认仅新增，不覆盖已有 ID 或 SKU。确认时会复核文件和当前库存版本。" : string.Join("\n", preview.Issues)) + (preview.Warnings is { Count: > 0 } ? "\n\n注意：\n" + string.Join("\n", preview.Warnings) : string.Empty), TextWrapping = TextWrapping.Wrap },
            PrimaryButtonText = "确认仅新增迁入", CloseButtonText = AppStrings.Get("Common_Cancel"),
            IsPrimaryButtonEnabled = preview.CanImport, DefaultButton = ContentDialogButton.Close, XamlRoot = XamlRoot,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        var result = viewModel.ImportInventoryWorkbook(file.Path);
        await ShowMessageAsync(result.IsSuccess ? "迁入完成" : "迁入失败", result.Message);
    }

    private void OnToggleApiTokenClicked(object sender, RoutedEventArgs e)
    {
        var showToken = ApiTokenBox.PasswordRevealMode != PasswordRevealMode.Visible;
        ApiTokenBox.PasswordRevealMode = showToken ? PasswordRevealMode.Visible : PasswordRevealMode.Hidden;
        ToggleApiTokenButton.Content = showToken ? "隐藏令牌" : "显示令牌";
    }

    private void OnApiTokenPasswordChanged(object sender, RoutedEventArgs e)
    {
        if (CopyApiTokenButton is null || ApiTokenCopyStatusText is null) return;
        CopyApiTokenButton.IsEnabled = !string.IsNullOrWhiteSpace(ApiTokenBox.Password);
        ApiTokenCopyStatusText.Text = string.Empty;
    }

    private void OnCopyApiTokenClicked(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(ApiTokenBox.Password)) return;
        try
        {
            var package = new DataPackage();
            package.SetText(ApiTokenBox.Password);
            Clipboard.SetContent(package);
            ApiTokenCopyStatusText.Text = "令牌已复制。";
        }
        catch (Exception)
        {
            ApiTokenCopyStatusText.Text = "剪贴板暂不可用，请显示令牌后手动复制。";
        }
    }

    private async void OnExportLabelWorkbookClicked(object sender, RoutedEventArgs e)
    {
        if (RuntimeViewModel is not { } viewModel) return;
        var columns = new HashSet<LabelWorkbookColumn>();
        Add(LabelNameColumn, LabelWorkbookColumn.Name); Add(LabelSkuColumn, LabelWorkbookColumn.Sku);
        Add(LabelModelColumn, LabelWorkbookColumn.Model); Add(LabelPackageColumn, LabelWorkbookColumn.PackageName);
        Add(LabelCategoryColumn, LabelWorkbookColumn.Category); Add(LabelLocationColumn, LabelWorkbookColumn.Location);
        Add(LabelQuantityColumn, LabelWorkbookColumn.Quantity); Add(LabelLongQrColumn, LabelWorkbookColumn.LongQrText);
        Add(LabelShortQrColumn, LabelWorkbookColumn.ShortQrText);
        if (columns.Count == 0) { await ShowMessageAsync("无法导出", "请至少选择一个导出字段。"); return; }
        var selectedLocations = SelectedLabelLocationIds();
        var rows = LabelWorkbookExporter.CreateRows(viewModel.AvailableComponents, _labelStorageLocations, selectedLocations);
        if (rows.Count == 0) { UpdateLabelExportPreview(); return; }

        var picker = new FileSavePicker { SuggestedFileName = $"component-vault-labels-{DateTime.Now:yyyyMMdd-HHmmss}" };
        picker.FileTypeChoices.Add("Excel 工作簿", new List<string> { ".xlsx" });
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(((App)Application.Current).Window));
        var file = await picker.PickSaveFileAsync();
        if (file is null) return;
        try
        {
            LabelWorkbookExporter.ExportRows(file.Path, rows, columns);
            await ShowMessageAsync("导出完成", $"标签打印数据已导出，共 {rows.Count} 行。");
        }
        catch (Exception exception) { await ShowMessageAsync("导出失败", exception.Message); }

        void Add(CheckBox checkBox, LabelWorkbookColumn column) { if (checkBox.IsChecked == true) columns.Add(column); }
    }

    private IReadOnlySet<string>? SelectedLabelLocationIds() => LabelLocationMode.SelectedIndex == 1
        ? LabelLocationsList.SelectedItems.OfType<StorageLocationRecord>()
            .Select(location => location.Id).ToHashSet(StringComparer.Ordinal)
        : null;

    private void OnPageLoaded(object sender, RoutedEventArgs e)
    {
        RefreshLabelLocationOptions();
        UpdateLabelExportPreview();
    }

    private void RefreshLabelLocationOptions()
    {
        if (RuntimeViewModel is not { } viewModel) return;
        _labelStorageLocations = viewModel.GetAllStorageLocations();
        var deleted = _labelStorageLocations.Where(location => location.Deleted)
            .Select(location => location.Id).ToHashSet(StringComparer.Ordinal);
        var options = _labelStorageLocations.Where(location => !location.Deleted)
            .ToDictionary(location => location.Id, StringComparer.Ordinal);
        foreach (var component in viewModel.AvailableComponents.Where(component => !component.Deleted))
        {
            IEnumerable<string> ids = component.Allocations.Count == 0
                ? [component.Location]
                : component.Allocations.Where(allocation => allocation.Quantity >= 0)
                    .Select(allocation => allocation.LocationId);
            foreach (var id in ids)
            {
                if (!deleted.Contains(id) && !options.ContainsKey(id))
                    options[id] = new StorageLocationRecord(id, id.Length == 0 ? "未指定库位" : id, string.Empty, false);
            }
        }
        LabelLocationsList.ItemsSource = options.Values.OrderBy(location => location.Id, StringComparer.Ordinal).ToArray();
    }

    private void OnLabelLocationSelectionChanged(object sender, SelectionChangedEventArgs e) => UpdateLabelExportPreview();

    private void OnLabelColumnsChanged(object sender, RoutedEventArgs e) => UpdateLabelExportPreview();

    private void UpdateLabelExportPreview()
    {
        if (LabelLocationMode is null || LabelLocationsList is null || LabelExportPreview is null || LabelExportButton is null)
            return;
        if (RuntimeViewModel is not { } viewModel) return;

        var hasLocations = LabelLocationsList.Items.Count > 0;
        if (LabelLocationMode.Items.Count > 1 && LabelLocationMode.Items[1] is ComboBoxItem specificMode)
            specificMode.IsEnabled = hasLocations;
        if (!hasLocations && LabelLocationMode.SelectedIndex == 1)
            LabelLocationMode.SelectedIndex = 0;

        var specific = LabelLocationMode.SelectedIndex == 1;
        LabelLocationsList.Visibility = specific ? Visibility.Visible : Visibility.Collapsed;
        var selected = SelectedLabelLocationIds();
        var count = LabelWorkbookExporter.CreateRows(viewModel.AvailableComponents, _labelStorageLocations, selected).Count;
        var hasColumns = new[] { LabelNameColumn, LabelSkuColumn, LabelModelColumn, LabelPackageColumn,
            LabelCategoryColumn, LabelLocationColumn, LabelQuantityColumn, LabelLongQrColumn, LabelShortQrColumn }
            .Any(checkBox => checkBox.IsChecked == true);
        LabelExportButton.IsEnabled = count > 0 && hasColumns;
        LabelExportPreview.Text = specific && selected?.Count == 0
            ? "请选择至少一个库位。预计导出 0 行。"
            : count == 0 ? "所选库位没有可导出的分配。预计导出 0 行。" : $"预计导出 {count} 行。";
    }

    private void OnFeedbackDiagnosticsToggled(object sender, RoutedEventArgs e)
    {
        FeedbackDiagnosticsPreviewBox.IsEnabled=FeedbackDiagnosticsToggle.IsOn;
        if(FeedbackDiagnosticsToggle.IsOn&&string.IsNullOrWhiteSpace(FeedbackDiagnosticsPreviewBox.Text))FeedbackDiagnosticsPreviewBox.Text=
            $"version={CurrentVersionText.Text}\nos={RuntimeInformation.OSDescription}\narchitecture={RuntimeInformation.OSArchitecture}\n{AppDiagnostics.Snapshot()}".TrimEnd();
    }

    private FeedbackReport CurrentFeedbackReport()=>FeedbackReportBuilder.Build(new FeedbackInput(
        FeedbackTitleBox.Text,FeedbackReproductionBox.Text,FeedbackExpectedBox.Text,FeedbackActualBox.Text,
        FeedbackDiagnosticsToggle.IsOn,FeedbackDiagnosticsPreviewBox.Text,CurrentVersionText.Text,
        RuntimeInformation.OSDescription,RuntimeInformation.OSArchitecture.ToString()));

    private void OnCopyFeedbackClicked(object sender,RoutedEventArgs e)
    {
        try{var report=CurrentFeedbackReport();var package=new DataPackage();package.SetText(report.FullText);Clipboard.SetContent(package);FeedbackStatusText.Text="完整报告已复制。";}
        catch(Exception exception){FeedbackStatusText.Text=$"复制失败（{exception.GetType().Name}）。";}
    }

    private async void OnExportFeedbackClicked(object sender,RoutedEventArgs e)
    {
        try{var report=CurrentFeedbackReport();var picker=new FileSavePicker{SuggestedFileName=$"component-vault-feedback-{DateTime.Now:yyyyMMdd-HHmmss}"};picker.FileTypeChoices.Add("文本报告",new List<string>{".txt"});
        InitializeWithWindow.Initialize(picker,WindowNative.GetWindowHandle(((App)Application.Current).Window));var file=await picker.PickSaveFileAsync();if(file is null)return;
        await Windows.Storage.FileIO.WriteTextAsync(file,report.FullText);FeedbackStatusText.Text="完整报告已导出。";}
        catch(Exception exception){FeedbackStatusText.Text=$"导出失败（{exception.GetType().Name}）。";}
    }

    private async void OnOpenFeedbackClicked(object sender,RoutedEventArgs e)
    {
        try{var report=CurrentFeedbackReport();FeedbackStatusText.Text=report.Notice;if(!await Windows.System.Launcher.LaunchUriAsync(report.GitHubUri))FeedbackStatusText.Text="无法打开默认浏览器。";}
        catch(Exception exception){FeedbackStatusText.Text=$"打开 GitHub 失败（{exception.GetType().Name}）。";}
    }

    private void OnClearDiagnosticsClicked(object sender,RoutedEventArgs e)
    {
        AppDiagnostics.Clear();FeedbackDiagnosticsPreviewBox.Text=string.Empty;FeedbackStatusText.Text="本进程应用诊断已清除。";
    }

    private async Task ShowMessageAsync(string title, string message)
    {
        var dialog = new ContentDialog
        {
            Title = title,
            Content = message,
            CloseButtonText = AppStrings.Get("Common_Close"),
            XamlRoot = XamlRoot,
        };
        await dialog.ShowAsync();
    }

    private async void OnCheckUpdateClicked(object sender, RoutedEventArgs e)
    {
        if (_updateCancellation is not null) return;
        _updateCancellation = new CancellationTokenSource();
        CheckUpdateButton.IsEnabled = false;
        UpdateChannelComboBox.IsEnabled = false;
        UpdateProgressRing.IsActive = true;
        UpdateInfoBar.Severity = InfoBarSeverity.Informational;
        UpdateInfoBar.Title = "正在检查更新";
        UpdateInfoBar.Message = "正在读取公开 GitHub Release…";
        try
        {
            var result = await _releaseClient.CheckAsync(CurrentVersionText.Text, _updateChannel, _updateCancellation.Token);
            DisplayUpdateResult(result);
        }
        catch (OperationCanceledException) { }
        finally
        {
            _updateCancellation?.Dispose();
            _updateCancellation = null;
            CheckUpdateButton.IsEnabled = true;
            UpdateChannelComboBox.IsEnabled = true;
            UpdateProgressRing.IsActive = false;
        }
    }

    public void DisplayUpdateResult(UpdateCheckResult result)
    {
        _latestRelease = result.Release;
        UpdateInfoBar.Title = result.IsSuccess ? "更新检查完成" : "无法完成更新检查";
        UpdateInfoBar.Message = result.Message;
        UpdateInfoBar.Severity = result.Comparison == UpdateComparison.UpdateAvailable
            ? InfoBarSeverity.Success
            : result.IsSuccess ? InfoBarSeverity.Informational : InfoBarSeverity.Warning;
        LatestVersionText.Text = result.Release?.Tag ?? "不可用";
        PublishedAtText.Text = result.Release?.PublishedAt?.ToLocalTime().ToString("yyyy-MM-dd HH:mm") ?? "—";
        ReleaseNotesText.Text = string.IsNullOrWhiteSpace(result.Release?.Notes) ? "该 Release 没有正文更新日志。" : result.Release.Notes;
        var canDownloadUpdate = result.Comparison == UpdateComparison.UpdateAvailable;
        DownloadPortableButton.IsEnabled = canDownloadUpdate && result.Release?.PortableDownload is not null;
        UpdateAssetHint.Text = result.Release is not null && result.Release.PortableDownload is null
            ? "此 Release 缺少 Windows 便携版，请打开发布页核对资产。"
            : "应用不会自动下载或替换当前程序。下载后请解压便携版并运行其中的 ComponentVault.WinUI.exe。";
    }

    public void ShowStartupUpdate(UpdateCheckResult result)
    {
        DisplayUpdateResult(result);
        if (IsLoaded) UpdateInfoBar.StartBringIntoView();
        else Loaded += OnUpdateDetailsLoaded;
    }

    private void OnUpdateDetailsLoaded(object sender, RoutedEventArgs e)
    {
        Loaded -= OnUpdateDetailsLoaded;
        UpdateInfoBar.StartBringIntoView();
    }

    private async void OnDownloadPortableClicked(object sender, RoutedEventArgs e)
    {
        if (_latestRelease?.PortableDownload is { } uri
            && GitHubReleaseParser.ValidateAssetUrl(uri.AbsoluteUri, _latestRelease.Tag, GitHubReleaseParser.PortableAssetName) is { } trusted)
            await LaunchTrustedUriAsync(trusted, "便携版下载");
    }

    private async void OnOpenReleasesClicked(object sender, RoutedEventArgs e)
    {
        var uri = _latestRelease?.ReleasePage
            ?? new Uri("https://github.com/Rafael-Ikaros/Component_Manager/releases");
        var trusted = _latestRelease is null
            ? GitHubReleaseParser.ValidateReleasePage(uri.AbsoluteUri)
            : GitHubReleaseParser.ValidateReleasePage(uri.AbsoluteUri, _latestRelease.Tag);
        if (trusted is not null) await LaunchTrustedUriAsync(trusted, "发布页");
    }

    private async Task LaunchTrustedUriAsync(Uri uri, string destination)
    {
        try
        {
            if (await Windows.System.Launcher.LaunchUriAsync(uri)) return;
            UpdateInfoBar.Severity = InfoBarSeverity.Warning;
            UpdateInfoBar.Title = "无法打开浏览器";
            UpdateInfoBar.Message = $"系统没有打开{destination}，请检查默认浏览器设置。";
        }
        catch (Exception exception)
        {
            UpdateInfoBar.Severity = InfoBarSeverity.Warning;
            UpdateInfoBar.Title = "无法打开浏览器";
            UpdateInfoBar.Message = $"打开{destination}失败：{exception.Message}";
        }
    }

    private void OnPageUnloaded(object sender, RoutedEventArgs e) => _updateCancellation?.Cancel();

    private void OnUpdateChannelChanged(object sender, SelectionChangedEventArgs e)
    {
        if (UpdateChannelComboBox.SelectedItem is not ComboBoxItem item) return;
        _updateChannel = item.Tag?.ToString() == "dev" ? UpdateChannel.Dev : UpdateChannel.Stable;
        UpdatePreferences.SaveChannel(_updateChannel);
        UpdateChannelHint.Text = ChannelHint();
        _latestRelease = null;
        LatestVersionText.Text = "尚未检查";
        DownloadPortableButton.IsEnabled = false;
    }

    private string ChannelHint() => _updateChannel == UpdateChannel.Dev
        ? "开发版会检查预发布版和更高的正式版，测试覆盖可能较少。"
        : "正式版仅检查稳定 Release；切回后不会自动降级已安装的开发版。";

}
