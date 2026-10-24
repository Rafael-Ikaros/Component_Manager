using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services.BatchInbound;
using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.Foundation;
using Windows.System;
using Windows.UI.Core;

namespace ComponentVault.WinUI.Views;

public sealed partial class BatchJlcInboundView
{
    private bool _tableContextMenusInitialized;

    private void InitializeTableContextMenus()
    {
        if (_tableContextMenusInitialized) return;
        _tableContextMenusInitialized = true;
        // Observe right taps even when a child has handled the routed event,
        // while leaving editing controls and their native menus alone.
        PendingList.AddHandler(RightTappedEvent, new RightTappedEventHandler(OnPendingTableRightTapped), true);
        ReadyList.AddHandler(RightTappedEvent, new RightTappedEventHandler(OnReadyTableRightTapped), true);
        // Keyboard requests use ContextRequested; mouse requests use only RightTapped.
        PendingList.ContextRequested += OnPendingTableContextRequested;
        ReadyList.ContextRequested += OnReadyTableContextRequested;
    }

    private void OnPendingTableRightTapped(object sender, RightTappedRoutedEventArgs e) =>
        ShowTableRightTappedMenu(PendingList, true, e);

    private void OnReadyTableRightTapped(object sender, RightTappedRoutedEventArgs e) =>
        ShowTableRightTappedMenu(ReadyList, false, e);

    private void ShowTableRightTappedMenu(ListView list, bool pending, RightTappedRoutedEventArgs e)
    {
        // Do not reject e.Handled here: observing a tap consumed by the item is
        // precisely why this handler is registered with handledEventsToo.
        var control = InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Control).HasFlag(CoreVirtualKeyStates.Down);
        var shift = InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Shift).HasFlag(CoreVirtualKeyStates.Down);
        if (ShowTableContextMenu(list, pending, e.OriginalSource as DependencyObject, e.GetPosition(list), control, shift))
            e.Handled = true;
    }

    private void OnPendingTableContextRequested(UIElement sender, ContextRequestedEventArgs e) =>
        ShowTableKeyboardContextMenu(PendingList, true, e);

    private void OnReadyTableContextRequested(UIElement sender, ContextRequestedEventArgs e) =>
        ShowTableKeyboardContextMenu(ReadyList, false, e);

    private void ShowTableKeyboardContextMenu(ListView list, bool pending, ContextRequestedEventArgs e)
    {
        if (_busy || e.Handled || e.TryGetPosition(list, out _)) return;
        var source = FocusManager.GetFocusedElement(XamlRoot) as DependencyObject ?? e.OriginalSource as DependencyObject;
        // Shift+F10 opens the keyboard menu without extending the row selection.
        if (ShowTableContextMenu(list, pending, source, null, false, false)) e.Handled = true;
    }

    private bool ShowTableContextMenu(ListView list, bool pending, DependencyObject? source, Point? position,
        bool control, bool shift)
    {
        if (_busy || !IsTableContextSource(source, list)) return false;

        EndReadyPointerSelection();
        EndPendingPointerSelection();
        var container = ReadySelectionContainer(source);
        if (container?.Content is BatchInboundEditableRow row)
            SelectTableContextRow(list, pending, row, control, shift);
        var menu = pending ? CreatePendingTableContextMenu() : CreateReadyTableContextMenu();
        if (position is { } pointerPosition) menu.ShowAt(list, pointerPosition);
        else menu.ShowAt(container ?? (FrameworkElement)list);
        return true;
    }

    private void SelectTableContextRow(ListView list, bool pending, BatchInboundEditableRow row, bool control, bool shift)
    {
        var rows = pending ? _pending : _ready;
        var current = rows.IndexOf(row);
        if (current < 0) return;
        var original = pending ? PendingSelectionSnapshot() : _ready.Select(item => item.Selected).ToArray();
        var anchorRow = (pending ? _pendingSelectionAnchor : _readySelectionAnchor)
            ?? list.SelectedItem as BatchInboundEditableRow;
        var anchor = anchorRow is null ? -1 : rows.IndexOf(anchorRow);
        if (anchor < 0) anchor = current;
        var selected = BatchInboundRangeSelection.ContextClick(original, anchor, current, control, shift);
        // Shift keeps the range origin. A Ctrl toggle establishes a new origin,
        // including when it clears that row, just like the other selection paths.
        if (shift || control || !original[current])
        {
            var nextAnchor = shift ? rows[anchor] : row;
            if (pending) _pendingSelectionAnchor = nextAnchor;
            else _readySelectionAnchor = nextAnchor;
        }
        if (pending) ApplyPendingSelection(selected);
        else ApplyReadySelection(selected, true);
    }

    private static bool IsTableContextSource(DependencyObject? source, ListView list)
    {
        while (source is not null)
        {
            if (ReferenceEquals(source, list)) return true;
            // Leave native editing menus and other interactive child controls alone,
            // including NumberBox's nested input and ComboBox's dropdown content.
            if (source is TextBox or PasswordBox or RichEditBox or NumberBox or ComboBox or
                ButtonBase or ScrollBar or Slider or ToggleSwitch) return false;
            if (source is TextBlock { IsTextSelectionEnabled: true } or RichTextBlock { IsTextSelectionEnabled: true })
                return false;
            // WinUI gives display text a default text flyout even when selection
            // is disabled and that flyout will not open. Let row labels use the
            // table menu rather than treating the inactive text flyout as an owner.
            if (source is not (TextBlock or RichTextBlock) && source is UIElement { ContextFlyout: not null })
                return false;
            source = VisualTreeHelper.GetParent(source);
        }
        return false;
    }

    private MenuFlyout CreatePendingTableContextMenu()
    {
        var menu = new MenuFlyout();
        menu.Items.Add(CreateTableContextCommand(TogglePendingSelectionButton, OnTogglePendingSelection));
        menu.Items.Add(CreateTableContextCommand(InvertPendingSelectionButton, OnInvertPendingSelection));
        menu.Items.Add(new MenuFlyoutSeparator());
        menu.Items.Add(CreateTableContextCommand(RetryButton, OnRetryPending));
        menu.Items.Add(CreateTableContextCommand(RemoveButton, OnRemovePending));
        return menu;
    }

    private MenuFlyout CreateReadyTableContextMenu()
    {
        var selected = _ready.Where(row => row.Selected).ToArray();
        var menu = new MenuFlyout();
        menu.Items.Add(CreateTableContextCommand(ToggleReadySelectionButton, OnToggleReadySelection));
        menu.Items.Add(CreateTableContextCommand(InvertReadySelectionButton, OnInvertReadySelection));
        menu.Items.Add(new MenuFlyoutSeparator());
        var locations = new MenuFlyoutSubItem
        {
            Text = "指派到目标库位",
            IsEnabled = !_busy && selected.Length > 0,
        };
        foreach (var location in ViewModel.StorageLocations.Where(item => !item.Deleted).ToArray())
        {
            var item = new ToggleMenuFlyoutItem
            {
                Text = location.Name,
                IsChecked = selected.Length > 0 && selected.All(row => row.LocationId == location.Id),
                IsEnabled = !_busy && selected.Length > 0,
            };
            item.Click += async (_, _) => await AssignReadyContextLocationAsync(location.Id);
            locations.Items.Add(item);
        }
        if (locations.Items.Count == 0)
            locations.Items.Add(new MenuFlyoutItem { Text = "暂无可用库位", IsEnabled = false });
        menu.Items.Add(locations);
        menu.Items.Add(CreateTableContextCommand(CreateLocationButton, OnCreateAndAssignContextLocation,
            () => _ready.Any(row => row.Selected), "新建库位并指派到选中项"));
        var applyLocation = new MenuFlyoutItem
        {
            Text = "应用默认库位到选中项",
            IsEnabled = !_busy && ApplyLocationButton.IsEnabled && selected.Length > 0 &&
                DefaultLocationBox.SelectedItem is StorageLocationRecord { Deleted: false },
        };
        applyLocation.Click += async (_, _) =>
        {
            if (!_busy && ApplyLocationButton.IsEnabled && DefaultLocationBox.SelectedItem is StorageLocationRecord location)
                await AssignReadyContextLocationAsync(location.Id);
        };
        menu.Items.Add(applyLocation);
        menu.Items.Add(new MenuFlyoutSeparator());
        menu.Items.Add(CreateTableContextCommand(SubmitButton, OnSubmit, () => _ready.Any(row => row.Selected)));
        return menu;
    }

    private async void OnCreateAndAssignContextLocation(object sender, RoutedEventArgs e)
    {
        if (_busy || !_ready.Any(row => row.Selected)) return;
        try
        {
            if (await CreateInboundLocationAsync(true) is { } location)
                await AssignReadyContextLocationAsync(location.Id);
        }
        catch (Exception exception) { await ShowBatchErrorAsync("新建并指派库位失败", exception.Message); }
    }

    private async Task AssignReadyContextLocationAsync(string locationId)
    {
        if (_busy || !ViewModel.StorageLocations.Any(location => location.Id == locationId && !location.Deleted)) return;
        EndReadyPointerSelection();
        var selected = _ready.Where(row => row.Selected).ToArray();
        if (selected.Length == 0) return;
        foreach (var row in selected) row.LocationId = locationId;
        string? error = null;
        try { SaveRemaining(); }
        catch (Exception exception) { error = exception.Message; }
        RefreshReadySelection(() =>
        {
            ReadyList.ItemsSource = null;
            ReadyList.ItemsSource = _ready;
        });
        if (error is not null)
        {
            CountText.Text = error;
            await ShowBatchErrorAsync("库位指派保存失败", error);
        }
    }

    private MenuFlyoutItem CreateTableContextCommand(Button button, RoutedEventHandler handler,
        Func<bool>? canExecute = null, string? text = null)
    {
        var item = new MenuFlyoutItem
        {
            Text = text ?? button.Content?.ToString() ?? "",
            IsEnabled = !_busy && button.IsEnabled && (canExecute?.Invoke() ?? true),
        };
        item.Click += (_, _) =>
        {
            if (!_busy && button.IsEnabled && (canExecute?.Invoke() ?? true)) handler(button, new RoutedEventArgs());
        };
        return item;
    }
}
