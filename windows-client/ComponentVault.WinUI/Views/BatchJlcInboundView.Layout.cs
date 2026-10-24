using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace ComponentVault.WinUI.Views;

public sealed partial class BatchJlcInboundView
{
    private ScrollViewer? _readyLayoutScrollViewer;
    private double _readyHeaderHorizontalOffset = double.NaN;
    private bool _readyLayoutInitialized;
    private ScrollViewer? _pendingLayoutScrollViewer;
    private double _pendingHeaderHorizontalOffset = double.NaN;
    private bool _pendingLayoutInitialized;
    private bool _pendingLayoutActive;

    private void InitializePendingLayout()
    {
        if (_pendingLayoutInitialized) return;
        _pendingLayoutInitialized = true;
        PendingList.Loaded += OnPendingLayoutLoaded;
        PendingList.Unloaded += OnPendingLayoutUnloaded;
        PendingHeaderScrollViewer.Loaded += OnPendingHeaderLoaded;
        PendingHeaderScrollViewer.SizeChanged += OnPendingHeaderSizeChanged;
        Unloaded += OnPendingLayoutUnloaded;
        if (PendingList.IsLoaded) ActivatePendingLayout();
    }

    private void OnPendingLayoutLoaded(object sender, RoutedEventArgs e) => ActivatePendingLayout();

    private void ActivatePendingLayout()
    {
        if (!_pendingLayoutActive)
        {
            _pendingLayoutActive = true;
            PendingList.LayoutUpdated += OnPendingLayoutUpdated;
        }
        AttachPendingLayoutScrollViewer(true);
    }

    private void OnPendingLayoutUpdated(object? sender, object e)
    {
        // A replacement ListView template can supply a new ScrollViewer without
        // reloading the ListView itself. Retire the old subscription when it does.
        if (_pendingLayoutActive) AttachPendingLayoutScrollViewer();
    }

    private void AttachPendingLayoutScrollViewer(bool force = false)
    {
        var scrollViewer = FindReadyScrollViewer(PendingList);
        if (!ReferenceEquals(_pendingLayoutScrollViewer, scrollViewer))
        {
            DetachPendingLayoutScrollViewer();
            _pendingLayoutScrollViewer = scrollViewer;
            if (_pendingLayoutScrollViewer is not null)
            {
                _pendingLayoutScrollViewer.ViewChanged += OnPendingLayoutViewChanged;
                _pendingLayoutScrollViewer.Unloaded += OnPendingScrollViewerUnloaded;
            }
            force = true;
        }
        if (force) SynchronizePendingHeaderHorizontalOffset(true);
    }

    private void OnPendingLayoutUnloaded(object sender, RoutedEventArgs e)
    {
        _pendingLayoutActive = false;
        PendingList.LayoutUpdated -= OnPendingLayoutUpdated;
        DetachPendingLayoutScrollViewer();
    }

    private void OnPendingScrollViewerUnloaded(object sender, RoutedEventArgs e)
    {
        if (ReferenceEquals(sender, _pendingLayoutScrollViewer))
            DetachPendingLayoutScrollViewer();
    }

    private void DetachPendingLayoutScrollViewer()
    {
        if (_pendingLayoutScrollViewer is not null)
        {
            _pendingLayoutScrollViewer.ViewChanged -= OnPendingLayoutViewChanged;
            _pendingLayoutScrollViewer.Unloaded -= OnPendingScrollViewerUnloaded;
        }
        _pendingLayoutScrollViewer = null;
        _pendingHeaderHorizontalOffset = double.NaN;
    }

    private void OnPendingLayoutViewChanged(object? sender, ScrollViewerViewChangedEventArgs e)
    {
        if (ReferenceEquals(sender, _pendingLayoutScrollViewer))
            SynchronizePendingHeaderHorizontalOffset();
    }

    private void OnPendingHeaderLoaded(object sender, RoutedEventArgs e) => SynchronizePendingHeaderHorizontalOffset(true);

    private void OnPendingHeaderSizeChanged(object sender, SizeChangedEventArgs e) => SynchronizePendingHeaderHorizontalOffset(true);

    private void SynchronizePendingHeaderHorizontalOffset(bool force = false)
    {
        if (_pendingLayoutScrollViewer is null) return;
        var horizontalOffset = _pendingLayoutScrollViewer.HorizontalOffset;
        if (!force && _pendingHeaderHorizontalOffset == horizontalOffset) return;
        _pendingHeaderHorizontalOffset = horizontalOffset;
        if (PendingHeaderScrollViewer.HorizontalOffset != horizontalOffset)
            PendingHeaderScrollViewer.ChangeView(horizontalOffset, null, null, true);
    }

    private void InitializeReadyLayout()
    {
        if (_readyLayoutInitialized) return;
        _readyLayoutInitialized = true;
        ReadyList.Loaded += OnReadyLayoutLoaded;
        ReadyList.Unloaded += OnReadyLayoutUnloaded;
        ReadyHeaderScrollViewer.Loaded += OnReadyHeaderLoaded;
        ReadyHeaderScrollViewer.SizeChanged += OnReadyHeaderSizeChanged;
        Unloaded += OnReadyLayoutUnloaded;
        if (ReadyList.IsLoaded) AttachReadyLayoutScrollViewer();
    }

    private void OnReadyLayoutLoaded(object sender, RoutedEventArgs e) => AttachReadyLayoutScrollViewer();

    private void AttachReadyLayoutScrollViewer()
    {
        var scrollViewer = FindReadyScrollViewer(ReadyList);
        if (!ReferenceEquals(_readyLayoutScrollViewer, scrollViewer))
        {
            DetachReadyLayoutScrollViewer();
            _readyLayoutScrollViewer = scrollViewer;
            if (_readyLayoutScrollViewer is not null)
                _readyLayoutScrollViewer.ViewChanged += OnReadyLayoutViewChanged;
        }
        SynchronizeReadyHeaderHorizontalOffset(true);
    }

    private void OnReadyLayoutUnloaded(object sender, RoutedEventArgs e) => DetachReadyLayoutScrollViewer();

    private void DetachReadyLayoutScrollViewer()
    {
        if (_readyLayoutScrollViewer is not null)
            _readyLayoutScrollViewer.ViewChanged -= OnReadyLayoutViewChanged;
        _readyLayoutScrollViewer = null;
        _readyHeaderHorizontalOffset = double.NaN;
    }

    private void OnReadyLayoutViewChanged(object? sender, ScrollViewerViewChangedEventArgs e)
    {
        if (ReferenceEquals(sender, _readyLayoutScrollViewer))
            SynchronizeReadyHeaderHorizontalOffset();
    }

    private void OnReadyHeaderLoaded(object sender, RoutedEventArgs e) => SynchronizeReadyHeaderHorizontalOffset(true);

    private void OnReadyHeaderSizeChanged(object sender, SizeChangedEventArgs e) => SynchronizeReadyHeaderHorizontalOffset(true);

    private void SynchronizeReadyHeaderHorizontalOffset(bool force = false)
    {
        if (_readyLayoutScrollViewer is null) return;
        var horizontalOffset = _readyLayoutScrollViewer.HorizontalOffset;
        // ViewChanged also fires during vertical scrolling; avoid submitting an
        // unchanged horizontal view on every vertical frame.
        if (!force && _readyHeaderHorizontalOffset == horizontalOffset) return;
        _readyHeaderHorizontalOffset = horizontalOffset;
        if (ReadyHeaderScrollViewer.HorizontalOffset != horizontalOffset)
            ReadyHeaderScrollViewer.ChangeView(horizontalOffset, null, null, true);
    }
}
