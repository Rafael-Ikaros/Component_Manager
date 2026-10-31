using ComponentVault.WinUI.Views;
using ComponentVault.WinUI.Localization;
using ComponentVault.WinUI.Services;
using ComponentVault.WinUI.Services.Updates;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace ComponentVault.WinUI;

public sealed partial class MainWindow : Window
{
    private readonly ViewModels.MainViewModel _viewModel;
    private bool _updatingSelection;
    private bool _syncDialogOpen;
    private readonly StartupUpdateCheck _startupUpdateCheck = new();
    private readonly CancellationTokenSource _windowClosed = new();

    public MainWindow()
    {
        InitializeComponent();
        Title = AppStrings.Get("Windows_App_WindowTitle");
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "ComponentVault.ico"));
        _viewModel =
            ((App)Application.Current).MainViewModel
            ?? throw new InvalidOperationException(AppStrings.Get("Windows_App_MainViewModelMissing"));
        AppNavigationView.DataContext = _viewModel;
        AppNavigationView.SelectionChanged += OnSelectionChanged;
        AppNavigationView.BackRequested += OnBackRequested;
        NavigateTo("Overview");
        AppNavigationView.Loaded += OnWindowLoaded;
        Closed += (_, _) => _windowClosed.Cancel();
    }

    private async void OnWindowLoaded(object sender, RoutedEventArgs e)
    {
        AppNavigationView.Loaded -= OnWindowLoaded;
        var channel = UpdatePreferences.LoadChannel();
        var token = _windowClosed.Token;
        var result = await _startupUpdateCheck.CheckOnceAsync(
            AppVersion.Current, channel, new GitHubReleaseClient().CheckAsync, token);
        if (result is null || token.IsCancellationRequested) return;

        try
        {
            // Never interrupt an editor or another popup. No await between this check and ShowAsync.
            while (!token.IsCancellationRequested)
            {
                if (UpdatePreferences.LoadChannel() != channel) return;
                var root = AppNavigationView.XamlRoot;
                if (root is not null && !_syncDialogOpen && !_viewModel.IsBusy
                    && !VisualTreeHelper.GetOpenPopupsForXamlRoot(root).Any(popup => popup.IsOpen))
                {
                    var dialog = new ContentDialog
                    {
                        Title = AppStrings.Format("MainWindow_UpdateAvailableTitle", result.Release!.Tag),
                        Content = AppStrings.Get("MainWindow_UpdateAvailableMessage"),
                        PrimaryButtonText = AppStrings.Get("MainWindow_ViewUpdate"),
                        CloseButtonText = AppStrings.Get("MainWindow_UpdateLater"),
                        DefaultButton = ContentDialogButton.Close,
                        XamlRoot = root,
                    };
                    using var registration = token.Register(() => DispatcherQueue.TryEnqueue(() => dialog.Hide()));
                    if (await dialog.ShowAsync() == ContentDialogResult.Primary && !token.IsCancellationRequested)
                    {
                        NavigateTo("Settings");
                        if (RootFrame.Content is SettingsView settings)
                            settings.ShowStartupUpdate(result);
                    }
                    return;
                }
                await Task.Delay(500, token);
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception) { /* Optional startup notification must not interrupt offline work. */ }
    }

    public void NavigateTo(string tag)
    {
        if (tag != "BatchInbound"
            && RootFrame.Content is BatchJlcInboundView batch
            && !batch.TryPrepareToLeave())
        {
            _updatingSelection = true;
            AppNavigationView.SelectedItem = InventoryItem;
            _updatingSelection = false;
            return;
        }

        var targetPage = tag switch
        {
            "Inventory" => typeof(ComponentsView),
            "BatchInbound" => typeof(BatchJlcInboundView),
            "Movements" => typeof(MovementsView),
            "Bom" => typeof(BomView),
            "Overview" => typeof(DashboardView),
            "Settings" => typeof(SettingsView),
            _ => null,
        };

        if (targetPage is null)
        {
            return;
        }

        var targetItem = tag switch
        {
            "Inventory" => InventoryItem,
            "BatchInbound" => InventoryItem,
            "Movements" => MovementsItem,
            "Bom" => BomItem,
            "Overview" => OverviewItem,
            "Settings" => SettingsItem,
            _ => null,
        };

        if (RootFrame.CurrentSourcePageType == targetPage)
        {
            UpdateBackState();
            return;
        }

        if (!ReferenceEquals(AppNavigationView.SelectedItem, targetItem))
        {
            _updatingSelection = true;
            AppNavigationView.SelectedItem = targetItem;
            _updatingSelection = false;
        }

        RootFrame.Navigate(targetPage);
        if (tag != "BatchInbound")
        {
            RootFrame.BackStack.Clear();
        }
        UpdateBackState();
    }

    private void OnSelectionChanged(
        NavigationView sender,
        NavigationViewSelectionChangedEventArgs args
    )
    {
        if (_updatingSelection)
        {
            return;
        }

        if (args.SelectedItemContainer?.Tag is not string tag)
        {
            return;
        }

        NavigateTo(tag);
    }

    private void OnBackRequested(NavigationView sender, NavigationViewBackRequestedEventArgs args)
    {
        GoBack();
    }

    public void GoBack()
    {
        if (!RootFrame.CanGoBack
            || RootFrame.Content is BatchJlcInboundView batch && !batch.TryPrepareToLeave())
        {
            return;
        }

        RootFrame.GoBack();
        AppNavigationView.SelectedItem = InventoryItem;
        UpdateBackState();
    }

    private void UpdateBackState()
    {
        AppNavigationView.IsBackEnabled = RootFrame.CanGoBack;
    }

    private async void OnSyncNowClicked(object sender, RoutedEventArgs e)
    {
        if (_viewModel.IsBusy || _syncDialogOpen)
        {
            return;
        }

        _syncDialogOpen = true;
        try
        {
            var result = await _viewModel.RunSyncAsync();
            await ShowMessageAsync(
                result.IsSuccess
                    ? AppStrings.Get("MainWindow_SyncSuccessTitle")
                    : AppStrings.Get("MainWindow_SyncFailureTitle"),
                result.IsSuccess ? _viewModel.SyncConfiguration.LastSyncMessage : result.Message
            );
        }
        finally
        {
            _syncDialogOpen = false;
        }
    }

    private async Task ShowMessageAsync(string title, string message)
    {
        var dialog = new ContentDialog
        {
            Title = title,
            Content = message,
            CloseButtonText = AppStrings.Get("Common_Close"),
            XamlRoot = AppNavigationView.XamlRoot,
        };
        await dialog.ShowAsync();
    }
}
