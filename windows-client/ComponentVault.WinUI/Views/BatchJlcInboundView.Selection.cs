using System.Collections.Specialized;
using System.ComponentModel;
using ComponentVault.WinUI.Services.BatchInbound;
using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation.Peers;
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
		private readonly HashSet<BatchInboundEditableRow> _observedReadyRows = [];
		private bool _synchronizingReadySelection;
		private bool _readySelectionDirty;
		private Pointer? _readySelectionPointer;
		private ListViewItem? _readySelectionStartContainer;
		private bool _readySelectionCaptured;
		private bool _readySelectionDragging;
		private Point _readySelectionStart;
		private Point _readySelectionPosition;
		private bool[] _readySelectionOriginal = [];
		private int _readySelectionDragAnchor;
		private bool _readySelectionPreserveExisting;
		private BatchInboundEditableRow? _readySelectionAnchor;
		private readonly DispatcherTimer _readySelectionScrollTimer = new() { Interval = TimeSpan.FromMilliseconds(40) };

		private void InitializeReadySelection ()
		{
				AddHandler(KeyDownEvent, new KeyEventHandler(OnSelectionEscapeKeyDown), true);
				ReadyList.SelectionChanged += OnReadySelectionChanged;
				ReadyList.PreviewKeyDown += OnReadySelectionPreviewKeyDown;
				ReadyList.AddHandler(KeyDownEvent, new KeyEventHandler(OnReadySelectionNativeKeyDown), true);
				_ready.CollectionChanged += OnReadyRowsChanged;
				ReadyList.AddHandler(PointerPressedEvent, new PointerEventHandler(OnReadyPointerPressed), true);
				ReadyList.AddHandler(PointerMovedEvent, new PointerEventHandler(OnReadyPointerMoved), true);
				ReadyList.AddHandler(PointerExitedEvent, new PointerEventHandler(OnReadyPointerExited), true);
				ReadyList.AddHandler(PointerReleasedEvent, new PointerEventHandler(OnReadyPointerEnded), true);
				ReadyList.AddHandler(PointerCanceledEvent, new PointerEventHandler(OnReadyPointerEnded), true);
				ReadyList.AddHandler(PointerCaptureLostEvent, new PointerEventHandler(OnReadyPointerCaptureLost), true);
				ReadyList.Unloaded += (_, _) => EndReadyPointerSelection(false);
				_readySelectionScrollTimer.Tick += OnReadySelectionScrollTick;
				OnReadyRowsChanged(null, new NotifyCollectionChangedEventArgs(NotifyCollectionChangedAction.Reset));
		}

		private void OnSelectionEscapeKeyDown (object sender, KeyRoutedEventArgs e)
		{
				// Bubble after the editor/popup has handled its own Escape behavior.
				// Busy dialogs retain Escape for cancellation and cannot change the batch.
				if (_busy || e.Key != VirtualKey.Escape)
						return;
				var changed = _ready.Any(row => row.Selected);
				var dirty = _readySelectionDirty;
				var hadSelection = changed || ReadyList.SelectedItems.Count > 0 || PendingList.SelectedItems.Count > 0;
				var hadPointer = _readySelectionPointer is not null;
				EndReadyPointerSelection(false);
				EndPendingPointerSelection();
				var wasSynchronizing = _synchronizingReadySelection;
				_synchronizingReadySelection = true;
				try
				{
						foreach (var row in _ready)
								row.Selected = false;
						ReadyList.SelectedItems.Clear();
						// Pending row.Selected is a saved inbound choice, separate from its
						// transient list selection used by the Retry and Remove commands.
						PendingList.SelectedItems.Clear();
				}
				finally { _synchronizingReadySelection = wasSynchronizing; }
				UpdateReadySelectionState();
				if (changed || dirty)
						PersistEdits();
				if (hadSelection || hadPointer)
						e.Handled = true;
		}

		private void OnReadyRowsChanged (object? sender, NotifyCollectionChangedEventArgs e)
		{
				// The caller owns saving collection edits. Saving in a Remove/Insert notification
				// would persist an incomplete collection, or revive a draft after Clear().
				EndReadyPointerSelection(false);
				var currentRows = _ready.ToHashSet();
				foreach (var row in _observedReadyRows.Where(row => !currentRows.Contains(row)).ToArray())
				{
						row.PropertyChanged -= OnReadyRowPropertyChanged;
						_observedReadyRows.Remove(row);
				}
				foreach (var row in _ready)
						if (_observedReadyRows.Add(row))
								row.PropertyChanged += OnReadyRowPropertyChanged;
				SynchronizeReadySelectionFromRows();
		}

		// A template refresh must never turn a draft's checked rows into unchecked rows.
		private void RefreshReadySelection (Action refresh)
		{
				EndReadyPointerSelection();
				var wasSynchronizing = _synchronizingReadySelection;
				_synchronizingReadySelection = true;
				try
				{
						refresh();
				}
				finally { _synchronizingReadySelection = wasSynchronizing; }
				SynchronizeReadySelectionFromRows();
		}

		private void SynchronizeReadySelectionFromRows ()
		{
				if (_synchronizingReadySelection)
						return;
				_synchronizingReadySelection = true;
				try
				{
						if (ReferenceEquals(ReadyList.ItemsSource, _ready))
						{
								foreach (var item in ReadyList.SelectedItems.Cast<BatchInboundEditableRow>().ToArray())
										if (!_ready.Contains(item) || !item.Selected)
												ReadyList.SelectedItems.Remove(item);
								foreach (var row in _ready)
										if (row.Selected && !ReadyList.SelectedItems.Contains(row))
												ReadyList.SelectedItems.Add(row);
						}
				}
				finally { _synchronizingReadySelection = false; }
				UpdateReadySelectionState();
		}

		private void OnReadyRowPropertyChanged (object? sender, PropertyChangedEventArgs e)
		{
				if (_synchronizingReadySelection || e.PropertyName != nameof(BatchInboundEditableRow.Selected))
						return;
				SynchronizeReadySelectionFromRows();
				PersistEdits();
		}

		private void OnReadySelectionChanged (object sender, SelectionChangedEventArgs e)
		{
				if (_synchronizingReadySelection || !ReferenceEquals(ReadyList.ItemsSource, _ready))
						return;
				if (_busy)
				{
						SynchronizeReadySelectionFromRows();
						return;
				}
				var changed = false;
				_synchronizingReadySelection = true;
				try
				{
						foreach (var row in e.RemovedItems.OfType<BatchInboundEditableRow>().Where(_ready.Contains))
						{
								changed |= row.Selected;
								row.Selected = false;
						}
						foreach (var row in e.AddedItems.OfType<BatchInboundEditableRow>().Where(_ready.Contains))
						{
								changed |= !row.Selected;
								row.Selected = true;
						}
						// An ordinary arrow key may keep one already-selected row and only remove
						// the others. Track that resulting anchor even when AddedItems is empty.
						if (_readySelectionPointer is null && ReadyList.SelectedItems.Count == 1 &&
							!InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Shift).HasFlag(CoreVirtualKeyStates.Down) &&
							!InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Control).HasFlag(CoreVirtualKeyStates.Down))
								_readySelectionAnchor = ReadyList.SelectedItems[0] as BatchInboundEditableRow;
				}
				finally { _synchronizingReadySelection = false; }
				UpdateReadySelectionState();
				if (!changed)
						return;
				if (_readySelectionPointer is not null)
						_readySelectionDirty = true;
				else
						PersistEdits();
		}

		private void OnToggleReadySelection (object sender, RoutedEventArgs e)
		{
				if (_busy || _ready.Count == 0)
						return;
				EndReadyPointerSelection();
				ApplyReadySelection(BatchInboundRangeSelection.ToggleAll(_ready.Select(row => row.Selected).ToArray()), true);
		}

		private void OnInvertReadySelection (object sender, RoutedEventArgs e)
		{
				if (_busy || _ready.Count == 0)
						return;
				EndReadyPointerSelection();
				ApplyReadySelection(BatchInboundRangeSelection.Invert(_ready.Select(row => row.Selected).ToArray()), true);
		}

		private void ApplyReadySelection (IReadOnlyList<bool> selected, bool persist)
		{
				var changed = false;
				_synchronizingReadySelection = true;
				try
				{
						for (var index = 0; index < _ready.Count; index++)
						{
								changed |= _ready[index].Selected != selected[index];
								_ready[index].Selected = selected[index];
						}
				}
				finally { _synchronizingReadySelection = false; }
				if (!changed)
						return;
				SynchronizeReadySelectionFromRows();
				if (persist)
						PersistEdits();
				else
						_readySelectionDirty = true;
		}

		private void UpdateReadySelectionState ()
		{
				var selectedCount = _ready.Count(row => row.Selected);
				ApplyLocationButton.Content = selectedCount > 0 && selectedCount < _ready.Count
					? "应用到选中项" : "应用到全部项";
				ToggleReadySelectionButton.Content = _ready.Count > 0 && selectedCount == _ready.Count ? "清空全选" : "全选";
				ToggleReadySelectionButton.IsEnabled = !_busy && _ready.Count > 0;
				InvertReadySelectionButton.IsEnabled = !_busy && _ready.Count > 0;
				var selectionText = $"已选 {selectedCount}/{_ready.Count} 条";
				if (ReadySelectionText.Text != selectionText)
				{
						ReadySelectionText.Text = selectionText;
						FrameworkElementAutomationPeer.FromElement(ReadySelectionText)?.RaiseAutomationEvent(AutomationEvents.LiveRegionChanged);
				}
				if (_busy)
						EndReadyPointerSelection();
		}

		private void OnReadyPointerPressed (object sender, PointerRoutedEventArgs e)
		{
				if (_busy || e.Pointer.PointerDeviceType != PointerDeviceType.Mouse ||
					!e.GetCurrentPoint(ReadyList).Properties.IsLeftButtonPressed)
						return;
				// A new editor press also invalidates any previous uncaptured press.
				EndReadyPointerSelection();
				var container = ReadySelectionContainer(e.OriginalSource as DependencyObject);
				if (container?.Content is not BatchInboundEditableRow row)
						return;
				var index = _ready.IndexOf(row);
				if (index < 0)
						return;
				_readySelectionPointer = e.Pointer;
				_readySelectionStartContainer = container;
				_readySelectionStart = _readySelectionPosition = e.GetCurrentPoint(ReadyList).Position;
				_readySelectionOriginal = _ready.Select(item => item.Selected).ToArray();
				_readySelectionPreserveExisting = e.KeyModifiers.HasFlag(VirtualKeyModifiers.Control);
				var shift = e.KeyModifiers.HasFlag(VirtualKeyModifiers.Shift);
				var anchorRow = _readySelectionAnchor ?? ReadyList.SelectedItem as BatchInboundEditableRow;
				var shiftAnchor = anchorRow is null ? -1 : _ready.IndexOf(anchorRow);
				_readySelectionDragAnchor = shift && shiftAnchor >= 0 ? shiftAnchor : index;
				_readySelectionAnchor = _ready[_readySelectionDragAnchor];
				if (shift)
				{
						// SelectedItems and SelectedIndex do not update WinUI's private range
						// anchor. Capture Shift clicks before its queued native tap can use an
						// older anchor, and use the same anchor as drag and keyboard selection.
						if (!CaptureReadySelectionPointer(e.Pointer))
								return;
						container.Focus(FocusState.Pointer);
						ApplyReadySelection(BatchInboundRangeSelection.Calculate(_readySelectionOriginal,
							_readySelectionDragAnchor, index, _readySelectionPreserveExisting), false);
						e.Handled = true;
				}
				// Ordinary clicks retain native Extended selection until dragging starts.
		}

		private bool CaptureReadySelectionPointer (Pointer pointer)
		{
				if (_readySelectionCaptured)
						return true;
				// A capture transfer synchronously raises CaptureLost on the old container.
				_readySelectionCaptured = true;
				if (ReadyList.CapturePointer(pointer))
						return true;
				EndReadyPointerSelection();
				return false;
		}

		private void OnReadyPointerMoved (object sender, PointerRoutedEventArgs e)
		{
				if (_readySelectionPointer?.PointerId != e.Pointer.PointerId)
						return;
				if (_busy || !e.GetCurrentPoint(ReadyList).Properties.IsLeftButtonPressed)
				{
						EndReadyPointerSelection();
						return;
				}
				_readySelectionPosition = e.GetCurrentPoint(ReadyList).Position;
				if (!_readySelectionDragging)
				{
						var dx = _readySelectionPosition.X - _readySelectionStart.X;
						var dy = _readySelectionPosition.Y - _readySelectionStart.Y;
						if (dx * dx + dy * dy < 25)
								return;
						_readySelectionDragging = true;
						if (!CaptureReadySelectionPointer(e.Pointer))
								return;
						_readySelectionStartContainer?.Focus(FocusState.Pointer);
						_readySelectionScrollTimer.Start();
				}
				UpdateReadyDragRange();
				e.Handled = true;
		}

		private void OnReadyPointerEnded (object sender, PointerRoutedEventArgs e)
		{
				if (_readySelectionPointer?.PointerId != e.Pointer.PointerId)
						return;
				if (_readySelectionCaptured)
						e.Handled = true;
				EndReadyPointerSelection();
		}

		private void OnReadyPointerExited (object sender, PointerRoutedEventArgs e)
		{
				if (_readySelectionCaptured || _readySelectionPointer?.PointerId != e.Pointer.PointerId)
						return;
				var point = e.GetCurrentPoint(ReadyList).Position;
				if (point.X < 0 || point.Y < 0 || point.X > ReadyList.ActualWidth || point.Y > ReadyList.ActualHeight)
						EndReadyPointerSelection();
		}

		private void OnReadyPointerCaptureLost (object sender, PointerRoutedEventArgs e)
		{
				// Ignore a child's old capture being transferred to ReadyList when a drag starts.
				if (_readySelectionPointer?.PointerId == e.Pointer.PointerId &&
					( !_readySelectionCaptured || ReferenceEquals(e.OriginalSource, ReadyList) ))
						EndReadyPointerSelection();
		}

		private void EndReadyPointerSelection (bool persist = true)
		{
				var pointer = _readySelectionPointer;
				_readySelectionPointer = null;
				_readySelectionStartContainer = null;
				_readySelectionDragging = false;
				_readySelectionCaptured = false;
				_readySelectionScrollTimer.Stop();
				if (pointer is not null)
						ReadyList.ReleasePointerCapture(pointer);
				if (_readySelectionDirty)
				{
						_readySelectionDirty = false;
						if (persist)
								PersistEdits();
				}
		}

		private void OnReadySelectionPreviewKeyDown (object sender, KeyRoutedEventArgs e)
		{
				if (_busy || _ready.Count == 0 ||
					!InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Shift).HasFlag(CoreVirtualKeyStates.Down))
						return;
				// Restrict range navigation to a row container. Editors retain Shift text
				// selection, spin-button editing, dropdown navigation and Shift+Tab.
				var container = ReadySelectionContainer(e.OriginalSource as DependencyObject);
				if (container?.Content is not BatchInboundEditableRow currentRow ||
					!ReferenceEquals(FocusManager.GetFocusedElement(XamlRoot), container))
						return;
				var current = _ready.IndexOf(currentRow);
				if (current < 0)
						return;
				var page = Math.Max(1, (int)(ReadyList.ActualHeight / Math.Max(1, container.ActualHeight)) - 1);
				var target = e.Key switch
				{
						VirtualKey.Up => Math.Max(0, current - 1),
						VirtualKey.Down => Math.Min(_ready.Count - 1, current + 1),
						VirtualKey.Home => 0,
						VirtualKey.End => _ready.Count - 1,
						VirtualKey.PageUp => Math.Max(0, current - page),
						VirtualKey.PageDown => Math.Min(_ready.Count - 1, current + page),
						VirtualKey.Space or VirtualKey.Enter => current,
						_ => -1,
				};
				if (target < 0)
						return;
				EndReadyPointerSelection();
				var anchor = _readySelectionAnchor is null ? -1 : _ready.IndexOf(_readySelectionAnchor);
				if (anchor < 0)
						anchor = current;
				_readySelectionAnchor = _ready[anchor];
				var preserve = InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Control).HasFlag(CoreVirtualKeyStates.Down);
				ApplyReadySelection(BatchInboundRangeSelection.Calculate(_ready.Select(row => row.Selected).ToArray(),
					anchor, target, preserve), true);
				ReadyList.ScrollIntoView(_ready[target]);
				ReadyList.UpdateLayout();
				( ReadyList.ContainerFromIndex(target) as ListViewItem )?.Focus(FocusState.Keyboard);
				e.Handled = true;
		}

		private void OnReadySelectionNativeKeyDown (object sender, KeyRoutedEventArgs e)
		{
				// Native navigation sets focus after selecting. Observe that final focus
				// even if the target was already the sole selection (no SelectionChanged),
				// and track the anchor of native Ctrl+Space without taking over that key.
				if (_busy || !e.Handled || ReadySelectionContainer(e.OriginalSource as DependencyObject) is null ||
					InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Shift).HasFlag(CoreVirtualKeyStates.Down))
						return;
				if (e.Key is not ( VirtualKey.Up or VirtualKey.Down or VirtualKey.Home or VirtualKey.End or
					VirtualKey.PageUp or VirtualKey.PageDown or VirtualKey.Space or VirtualKey.Enter ))
						return;
				if (InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Control).HasFlag(CoreVirtualKeyStates.Down) &&
					e.Key is not ( VirtualKey.Space or VirtualKey.Enter ))
						return;
				if (FocusManager.GetFocusedElement(XamlRoot) is ListViewItem { Content: BatchInboundEditableRow row } && _ready.Contains(row))
						_readySelectionAnchor = row;
		}

		private void UpdateReadyDragRange ()
		{
				if (!_readySelectionDragging || _busy || _ready.Count != _readySelectionOriginal.Length)
						return;
				var nearest = -1;
				var distance = double.MaxValue;
				for (var index = 0; index < _ready.Count; index++)
				{
						if (ReadyList.ContainerFromIndex(index) is not ListViewItem container || container.ActualHeight <= 0)
								continue;
						var top = container.TransformToVisual(ReadyList).TransformPoint(new Point()).Y;
						var bottom = top + container.ActualHeight;
						if (bottom < 0 || top > ReadyList.ActualHeight)
								continue;
						var candidate = _readySelectionPosition.Y < top ? top - _readySelectionPosition.Y :
				_readySelectionPosition.Y > bottom ? _readySelectionPosition.Y - bottom : 0;
						if (candidate >= distance)
								continue;
						nearest = index;
						distance = candidate;
				}
				if (nearest >= 0)
						ApplyReadySelection(BatchInboundRangeSelection.Calculate(_readySelectionOriginal,
							_readySelectionDragAnchor, nearest, _readySelectionPreserveExisting), false);
		}

		private void OnReadySelectionScrollTick (object? sender, object e)
		{
				if (!_readySelectionDragging || _busy)
				{
						EndReadyPointerSelection();
						return;
				}
				var scroll = FindReadyScrollViewer(ReadyList);
				if (scroll is null)
						return;
				const double edge = 32;
				var y = _readySelectionPosition.Y;
				var delta = y < edge ? -Math.Min(48, edge - y) :
			y > ReadyList.ActualHeight - edge ? Math.Min(48, y - ReadyList.ActualHeight + edge) : 0;
				if (delta != 0)
				{
						scroll.ChangeView(null, Math.Clamp(scroll.VerticalOffset + delta, 0, scroll.ScrollableHeight), null, true);
						DispatcherQueue.TryEnqueue(UpdateReadyDragRange);
				}
				UpdateReadyDragRange();
		}

		private static ListViewItem? ReadySelectionContainer (DependencyObject? source)
		{
				while (source is not null)
				{
						if (source is ListViewItem item)
								return item;
						if (source is TextBox or PasswordBox or RichEditBox or NumberBox or ComboBox or
							ButtonBase or ScrollBar or Slider or ToggleSwitch or HyperlinkButton)
								return null;
						if (source is TextBlock { IsTextSelectionEnabled: true } or RichTextBlock { IsTextSelectionEnabled: true })
								return null;
						source = VisualTreeHelper.GetParent(source);
				}
				return null;
		}

		private static ScrollViewer? FindReadyScrollViewer (DependencyObject parent)
		{
				for (var index = 0; index < VisualTreeHelper.GetChildrenCount(parent); index++)
				{
						var child = VisualTreeHelper.GetChild(parent, index);
						if (child is ScrollViewer viewer)
								return viewer;
						if (FindReadyScrollViewer(child) is { } descendant)
								return descendant;
				}
				return null;
		}
}
