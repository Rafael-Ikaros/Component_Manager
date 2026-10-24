using System.Collections.Specialized;
using System.ComponentModel;
using ComponentVault.WinUI.Services.BatchInbound;
using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Windows.Foundation;
using Windows.System;
using Windows.UI.Core;

namespace ComponentVault.WinUI.Views;

public sealed partial class BatchJlcInboundView
{
    // This set is only the Retry/Remove command selection. It is never an inbound approval.
    private readonly HashSet<BatchInboundEditableRow> _pendingSelection = [];
    private readonly HashSet<BatchInboundEditableRow> _observedPendingSelectionRows = [];
    private bool _synchronizingPendingSelection;
    private Pointer? _pendingSelectionPointer;
    private ListViewItem? _pendingSelectionStartContainer;
    private bool _pendingSelectionCaptured;
    private bool _pendingSelectionDragging;
    private Point _pendingSelectionStart;
    private Point _pendingSelectionPosition;
    private bool[] _pendingSelectionOriginal = [];
    private int _pendingSelectionDragAnchor;
    private bool _pendingSelectionPreserveExisting;
    private BatchInboundEditableRow? _pendingSelectionAnchor;
    private readonly DispatcherTimer _pendingSelectionScrollTimer = new() { Interval = TimeSpan.FromMilliseconds(40) };

    private void InitializePendingSelection()
    {
        PendingList.SelectionChanged += OnPendingSelectionChanged;
        PendingList.PreviewKeyDown += OnPendingSelectionPreviewKeyDown;
        PendingList.AddHandler(KeyDownEvent, new KeyEventHandler(OnPendingSelectionNativeKeyDown), true);
        AddHandler(KeyDownEvent, new KeyEventHandler(OnPendingSelectionEscapeKeyDown), true);
        _pending.CollectionChanged += OnPendingRowsChanged;
        PendingList.AddHandler(PointerPressedEvent, new PointerEventHandler(OnPendingPointerPressed), true);
        PendingList.AddHandler(PointerMovedEvent, new PointerEventHandler(OnPendingPointerMoved), true);
        PendingList.AddHandler(PointerExitedEvent, new PointerEventHandler(OnPendingPointerExited), true);
        PendingList.AddHandler(PointerReleasedEvent, new PointerEventHandler(OnPendingPointerEnded), true);
        PendingList.AddHandler(PointerCanceledEvent, new PointerEventHandler(OnPendingPointerEnded), true);
        PendingList.AddHandler(PointerCaptureLostEvent, new PointerEventHandler(OnPendingPointerCaptureLost), true);
        PendingList.Unloaded += (_, _) => EndPendingPointerSelection();
        _pendingSelectionScrollTimer.Tick += OnPendingSelectionScrollTick;
        OnPendingRowsChanged(null, new NotifyCollectionChangedEventArgs(NotifyCollectionChangedAction.Reset));
    }

    private void OnPendingRowsChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        EndPendingPointerSelection();
        // Defer pruning during guarded Remove/Insert template refreshes.
        if (_synchronizingPendingSelection) return;
        PrunePendingSelection();
        SynchronizePendingSelection();
    }

    private void PrunePendingSelection()
    {
        _pendingSelection.IntersectWith(_pending);
        var currentRows = _pending.ToHashSet();
        foreach (var row in _observedPendingSelectionRows.Where(row => !currentRows.Contains(row)).ToArray())
        {
            row.PropertyChanged -= OnPendingRowSelectionPropertyChanged;
            _observedPendingSelectionRows.Remove(row);
            row.PendingSelected = false;
        }
        foreach (var row in _pending)
            if (_observedPendingSelectionRows.Add(row)) row.PropertyChanged += OnPendingRowSelectionPropertyChanged;
        if (_pendingSelectionAnchor is not null && !_pending.Contains(_pendingSelectionAnchor))
            _pendingSelectionAnchor = null;
    }

    private void OnPendingRowSelectionPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (_synchronizingPendingSelection || e.PropertyName != nameof(BatchInboundEditableRow.PendingSelected) ||
            sender is not BatchInboundEditableRow row || !_pending.Contains(row)) return;
        if (_busy) { SynchronizePendingSelection(); return; }
        EndPendingPointerSelection();
        if (row.PendingSelected) _pendingSelection.Add(row);
        else _pendingSelection.Remove(row);
        _pendingSelectionAnchor = row;
        SynchronizePendingSelection();
    }

    private void RefreshPendingSelection(Action refresh)
    {
        EndPendingPointerSelection();
        var wasSynchronizing = _synchronizingPendingSelection;
        _synchronizingPendingSelection = true;
        try { refresh(); }
        finally { _synchronizingPendingSelection = wasSynchronizing; }
        if (wasSynchronizing) return;
        PrunePendingSelection();
        SynchronizePendingSelection();
    }

    private void SynchronizePendingSelection()
    {
        if (_synchronizingPendingSelection) return;
        _synchronizingPendingSelection = true;
        try
        {
            foreach (var row in _pending) row.PendingSelected = _pendingSelection.Contains(row);
            if (ReferenceEquals(PendingList.ItemsSource, _pending))
            {
                foreach (var row in PendingList.SelectedItems.OfType<BatchInboundEditableRow>().ToArray())
                    if (!_pendingSelection.Contains(row)) PendingList.SelectedItems.Remove(row);
                foreach (var row in _pending)
                    if (_pendingSelection.Contains(row) && !PendingList.SelectedItems.Contains(row))
                        PendingList.SelectedItems.Add(row);
            }
        }
        finally { _synchronizingPendingSelection = false; }
        UpdatePendingSelectionState();
    }

    private void OnPendingSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_synchronizingPendingSelection || !ReferenceEquals(PendingList.ItemsSource, _pending)) return;
        if (_busy) { SynchronizePendingSelection(); return; }
        // Includes the page's Escape clear: stop a drag before it could reselect rows.
        EndPendingPointerSelection();
        foreach (var row in e.RemovedItems.OfType<BatchInboundEditableRow>().Where(_pending.Contains))
            _pendingSelection.Remove(row);
        foreach (var row in e.AddedItems.OfType<BatchInboundEditableRow>().Where(_pending.Contains))
            _pendingSelection.Add(row);
        if (PendingList.SelectedItems.Count == 1 &&
            !InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Shift).HasFlag(CoreVirtualKeyStates.Down) &&
            !InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Control).HasFlag(CoreVirtualKeyStates.Down))
            _pendingSelectionAnchor = PendingList.SelectedItems[0] as BatchInboundEditableRow;
        SynchronizePendingSelection();
    }

    private void OnTogglePendingSelection(object sender, RoutedEventArgs e)
    {
        if (_busy || _pending.Count == 0) return;
        EndPendingPointerSelection();
        ApplyPendingSelection(BatchInboundRangeSelection.ToggleAll(PendingSelectionSnapshot()));
    }

    private void OnInvertPendingSelection(object sender, RoutedEventArgs e)
    {
        if (_busy || _pending.Count == 0) return;
        EndPendingPointerSelection();
        ApplyPendingSelection(BatchInboundRangeSelection.Invert(PendingSelectionSnapshot()));
    }

    private bool[] PendingSelectionSnapshot() => _pending.Select(_pendingSelection.Contains).ToArray();

    private void ApplyPendingSelection(IReadOnlyList<bool> selected)
    {
        var desired = _pending.Where((_, index) => selected[index]).ToHashSet();
        if (_pendingSelection.SetEquals(desired)) return;
        _pendingSelection.Clear();
        _pendingSelection.UnionWith(desired);
        SynchronizePendingSelection();
    }

    private void UpdatePendingSelectionState()
    {
        var selectedCount = _pendingSelection.Count;
        TogglePendingSelectionButton.Content = _pending.Count > 0 && selectedCount == _pending.Count ? "清空全选" : "全选";
        TogglePendingSelectionButton.IsEnabled = !_busy && _pending.Count > 0;
        InvertPendingSelectionButton.IsEnabled = !_busy && _pending.Count > 0;
        RetryButton.IsEnabled = !_busy && selectedCount > 0;
        RemoveButton.IsEnabled = !_busy && selectedCount > 0;
        var text = $"已选 {selectedCount}/{_pending.Count} 条";
        if (PendingSelectionText.Text != text)
        {
            PendingSelectionText.Text = text;
            FrameworkElementAutomationPeer.FromElement(PendingSelectionText)?.RaiseAutomationEvent(AutomationEvents.LiveRegionChanged);
        }
        if (_busy) EndPendingPointerSelection();
    }

    private void OnPendingSelectionEscapeKeyDown(object sender, KeyRoutedEventArgs e)
    {
        // The existing page handler clears SelectedItems. Also stop an empty,
        // uncaptured press, which would not produce a SelectionChanged event.
        if (!_busy && e.Key == VirtualKey.Escape) EndPendingPointerSelection();
    }

    private void OnPendingPointerPressed(object sender, PointerRoutedEventArgs e)
    {
        if (_busy || e.Pointer.PointerDeviceType != PointerDeviceType.Mouse ||
            !e.GetCurrentPoint(PendingList).Properties.IsLeftButtonPressed) return;
        EndPendingPointerSelection();
        var container = ReadySelectionContainer(e.OriginalSource as DependencyObject);
        if (container?.Content is not BatchInboundEditableRow row) return;
        var index = _pending.IndexOf(row);
        if (index < 0) return;
        _pendingSelectionPointer = e.Pointer;
        _pendingSelectionStartContainer = container;
        _pendingSelectionStart = _pendingSelectionPosition = e.GetCurrentPoint(PendingList).Position;
        _pendingSelectionOriginal = PendingSelectionSnapshot();
        _pendingSelectionPreserveExisting = e.KeyModifiers.HasFlag(VirtualKeyModifiers.Control);
        var shift = e.KeyModifiers.HasFlag(VirtualKeyModifiers.Shift);
        var anchorRow = _pendingSelectionAnchor ?? PendingList.SelectedItem as BatchInboundEditableRow;
        var shiftAnchor = anchorRow is null ? -1 : _pending.IndexOf(anchorRow);
        _pendingSelectionDragAnchor = shift && shiftAnchor >= 0 ? shiftAnchor : index;
        _pendingSelectionAnchor = _pending[_pendingSelectionDragAnchor];
        if (shift)
        {
            // Programmatic selection does not update WinUI's private native anchor.
            if (!CapturePendingSelectionPointer(e.Pointer)) return;
            container.Focus(FocusState.Pointer);
            ApplyPendingSelection(BatchInboundRangeSelection.Calculate(_pendingSelectionOriginal,
                _pendingSelectionDragAnchor, index, _pendingSelectionPreserveExisting));
            e.Handled = true;
        }
    }

    private bool CapturePendingSelectionPointer(Pointer pointer)
    {
        if (_pendingSelectionCaptured) return true;
        _pendingSelectionCaptured = true;
        if (PendingList.CapturePointer(pointer)) return true;
        EndPendingPointerSelection();
        return false;
    }

    private void OnPendingPointerMoved(object sender, PointerRoutedEventArgs e)
    {
        if (_pendingSelectionPointer?.PointerId != e.Pointer.PointerId) return;
        if (_busy || !e.GetCurrentPoint(PendingList).Properties.IsLeftButtonPressed)
        {
            EndPendingPointerSelection();
            return;
        }
        _pendingSelectionPosition = e.GetCurrentPoint(PendingList).Position;
        if (!_pendingSelectionDragging)
        {
            var dx = _pendingSelectionPosition.X - _pendingSelectionStart.X;
            var dy = _pendingSelectionPosition.Y - _pendingSelectionStart.Y;
            if (dx * dx + dy * dy < 25) return;
            _pendingSelectionDragging = true;
            if (!CapturePendingSelectionPointer(e.Pointer)) return;
            _pendingSelectionStartContainer?.Focus(FocusState.Pointer);
            _pendingSelectionScrollTimer.Start();
        }
        UpdatePendingDragRange();
        e.Handled = true;
    }

    private void OnPendingPointerEnded(object sender, PointerRoutedEventArgs e)
    {
        if (_pendingSelectionPointer?.PointerId != e.Pointer.PointerId) return;
        if (_pendingSelectionCaptured) e.Handled = true;
        EndPendingPointerSelection();
    }

    private void OnPendingPointerExited(object sender, PointerRoutedEventArgs e)
    {
        if (_pendingSelectionCaptured || _pendingSelectionPointer?.PointerId != e.Pointer.PointerId) return;
        var point = e.GetCurrentPoint(PendingList).Position;
        if (point.X < 0 || point.Y < 0 || point.X > PendingList.ActualWidth || point.Y > PendingList.ActualHeight)
            EndPendingPointerSelection();
    }

    private void OnPendingPointerCaptureLost(object sender, PointerRoutedEventArgs e)
    {
        if (_pendingSelectionPointer?.PointerId == e.Pointer.PointerId &&
            (!_pendingSelectionCaptured || ReferenceEquals(e.OriginalSource, PendingList)))
            EndPendingPointerSelection();
    }

    private void EndPendingPointerSelection()
    {
        var pointer = _pendingSelectionPointer;
        _pendingSelectionPointer = null;
        _pendingSelectionStartContainer = null;
        _pendingSelectionCaptured = false;
        _pendingSelectionDragging = false;
        _pendingSelectionScrollTimer.Stop();
        if (pointer is not null) PendingList.ReleasePointerCapture(pointer);
    }

    private void OnPendingSelectionPreviewKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (_busy || _pending.Count == 0 ||
            !InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Shift).HasFlag(CoreVirtualKeyStates.Down)) return;
        var container = ReadySelectionContainer(e.OriginalSource as DependencyObject);
        if (container?.Content is not BatchInboundEditableRow currentRow ||
            !ReferenceEquals(FocusManager.GetFocusedElement(XamlRoot), container)) return;
        var current = _pending.IndexOf(currentRow);
        if (current < 0) return;
        var page = Math.Max(1, (int)(PendingList.ActualHeight / Math.Max(1, container.ActualHeight)) - 1);
        var target = e.Key switch
        {
            VirtualKey.Up => Math.Max(0, current - 1),
            VirtualKey.Down => Math.Min(_pending.Count - 1, current + 1),
            VirtualKey.Home => 0,
            VirtualKey.End => _pending.Count - 1,
            VirtualKey.PageUp => Math.Max(0, current - page),
            VirtualKey.PageDown => Math.Min(_pending.Count - 1, current + page),
            VirtualKey.Space or VirtualKey.Enter => current,
            _ => -1,
        };
        if (target < 0) return;
        EndPendingPointerSelection();
        var anchor = _pendingSelectionAnchor is null ? -1 : _pending.IndexOf(_pendingSelectionAnchor);
        if (anchor < 0) anchor = current;
        _pendingSelectionAnchor = _pending[anchor];
        var preserve = InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Control).HasFlag(CoreVirtualKeyStates.Down);
        ApplyPendingSelection(BatchInboundRangeSelection.Calculate(PendingSelectionSnapshot(), anchor, target, preserve));
        PendingList.ScrollIntoView(_pending[target]);
        PendingList.UpdateLayout();
        (PendingList.ContainerFromIndex(target) as ListViewItem)?.Focus(FocusState.Keyboard);
        e.Handled = true;
    }

    private void OnPendingSelectionNativeKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (_busy || !e.Handled || ReadySelectionContainer(e.OriginalSource as DependencyObject) is null ||
            InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Shift).HasFlag(CoreVirtualKeyStates.Down)) return;
        if (e.Key is not (VirtualKey.Up or VirtualKey.Down or VirtualKey.Home or VirtualKey.End or
            VirtualKey.PageUp or VirtualKey.PageDown or VirtualKey.Space or VirtualKey.Enter)) return;
        if (InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Control).HasFlag(CoreVirtualKeyStates.Down) &&
            e.Key is not (VirtualKey.Space or VirtualKey.Enter)) return;
        if (FocusManager.GetFocusedElement(XamlRoot) is ListViewItem { Content: BatchInboundEditableRow row } && _pending.Contains(row))
            _pendingSelectionAnchor = row;
    }

    private void UpdatePendingDragRange()
    {
        if (!_pendingSelectionDragging || _busy || _pending.Count != _pendingSelectionOriginal.Length) return;
        var nearest = -1;
        var distance = double.MaxValue;
        for (var index = 0; index < _pending.Count; index++)
        {
            if (PendingList.ContainerFromIndex(index) is not ListViewItem container || container.ActualHeight <= 0) continue;
            var top = container.TransformToVisual(PendingList).TransformPoint(new Point()).Y;
            var bottom = top + container.ActualHeight;
            if (bottom < 0 || top > PendingList.ActualHeight) continue;
            var candidate = _pendingSelectionPosition.Y < top ? top - _pendingSelectionPosition.Y :
                _pendingSelectionPosition.Y > bottom ? _pendingSelectionPosition.Y - bottom : 0;
            if (candidate >= distance) continue;
            nearest = index;
            distance = candidate;
        }
        if (nearest >= 0)
            ApplyPendingSelection(BatchInboundRangeSelection.Calculate(_pendingSelectionOriginal,
                _pendingSelectionDragAnchor, nearest, _pendingSelectionPreserveExisting));
    }

    private void OnPendingSelectionScrollTick(object? sender, object e)
    {
        if (!_pendingSelectionDragging || _busy) { EndPendingPointerSelection(); return; }
        var scroll = FindReadyScrollViewer(PendingList);
        if (scroll is null) return;
        const double edge = 32;
        var y = _pendingSelectionPosition.Y;
        var delta = y < edge ? -Math.Min(48, edge - y) :
            y > PendingList.ActualHeight - edge ? Math.Min(48, y - PendingList.ActualHeight + edge) : 0;
        if (delta != 0)
        {
            scroll.ChangeView(null, Math.Clamp(scroll.VerticalOffset + delta, 0, scroll.ScrollableHeight), null, true);
            DispatcherQueue.TryEnqueue(UpdatePendingDragRange);
        }
        UpdatePendingDragRange();
    }
}
