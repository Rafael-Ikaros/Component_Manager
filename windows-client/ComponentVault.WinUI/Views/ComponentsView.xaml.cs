using ComponentVault.WinUI.Design;
using ComponentVault.WinUI.Localization;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.ViewModels;
using ComponentVault.WinUI.Services.Migration;
using ComponentVault.WinUI.Services.Catalog;
using ComponentVault.WinUI.Services;
using ComponentVault.WinUI.Views.Components;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Windows.UI;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace ComponentVault.WinUI.Views;

public sealed partial class ComponentsView : Page
{
		private sealed record LocationOption (string Id, string Label)
		{
				public override string ToString () => Label;
		}

		private readonly LcscPublicLookup _catalogLookup = new();
		private readonly HashSet<string> _lazyLookupAttempted = new(StringComparer.OrdinalIgnoreCase);
		private bool _componentEditorBusy;
		private bool _isBatchSelecting;
		private MainViewModel? RuntimeViewModel => ViewModelResolver.GetRuntimeViewModel(DataContext);

		public ComponentsView ()
		{
				InitializeComponent();
				NavigationCacheMode = NavigationCacheMode.Required;
				DataContext = ViewModelResolver.ResolveMainViewModel();
				if (RuntimeViewModel is { } viewModel)
				{
						viewModel.Components.CollectionChanged += (_, _) => UpdateInventoryListState();
						viewModel.PropertyChanged += (_, args) =>
						{
								if (args.PropertyName == nameof(MainViewModel.SelectedComponent) && !_isBatchSelecting)
										ComponentsListView.SelectedItem = viewModel.SelectedComponent;
						};
						ComponentsListView.SelectedItem = viewModel.SelectedComponent;
				}
				UpdateInventoryListState();
		}

		private void UpdateInventoryListState ()
		{
				InventoryEmptyState.Visibility = RuntimeViewModel?.Components.Count == 0
					? Visibility.Visible : Visibility.Collapsed;
				if (_isBatchSelecting)
						UpdateBatchSelectionSummary();
		}

		private void OnSearchTextChanged (
			AutoSuggestBox sender,
			AutoSuggestBoxTextChangedEventArgs args
		)
		{
				if (RuntimeViewModel is { } viewModel)
				{
						viewModel.ComponentSearchText = sender.Text;
				}
		}

		private void OnLowStockOnlyToggled (object sender, RoutedEventArgs e)
		{
				if (RuntimeViewModel is { } viewModel)
				{
						viewModel.ShowLowStockOnly = LowStockOnlyToggle.IsOn;
				}
		}

		private void OnComponentSelectionChanged (object sender, SelectionChangedEventArgs e)
		{
				if (_isBatchSelecting)
				{
						UpdateBatchSelectionSummary();
						return;
				}
				if (RuntimeViewModel is { } viewModel)
				{
						viewModel.SelectedComponent = ComponentsListView.SelectedItem as ComponentRecord;
						_ = TryLazyImageLookupAsync(viewModel.SelectedComponent);
				}
		}

		private void OnStartBatchSelectionClicked (object sender, RoutedEventArgs e)
		{
				_isBatchSelecting = true;
				ComponentsListView.SelectionMode = ListViewSelectionMode.Multiple;
				ComponentsListView.SelectedItems.Clear();
				InventoryListSummary.Visibility = Visibility.Collapsed;
				StartBatchSelectionButton.Visibility = Visibility.Collapsed;
				BatchSelectionSummary.Visibility = Visibility.Visible;
				BatchSelectionActions.Visibility = Visibility.Visible;
				UpdateBatchSelectionSummary();
		}

		private void OnCancelBatchSelectionClicked (object sender, RoutedEventArgs e) => EndBatchSelection();

		private void OnSelectAllVisibleClicked (object sender, RoutedEventArgs e)
		{
				if (!_isBatchSelecting)
						return;
				ComponentsListView.SelectAll();
				UpdateBatchSelectionSummary();
		}

		private void UpdateBatchSelectionSummary ()
		{
				var count = ComponentsListView.SelectedItems.Count;
				BatchSelectionSummary.Text = count > 500
					? $"已选择 {count} 项（最多转移 500 项）"
					: $"已选择 {count} 项";
				TransferSelectedButton.IsEnabled = count is > 0 and <= 500;
		}

		private void EndBatchSelection ()
		{
				if (!_isBatchSelecting)
						return;
				_isBatchSelecting = false;
				var selectedId = RuntimeViewModel?.SelectedComponent?.Id;
				ComponentsListView.SelectionMode = ListViewSelectionMode.Single;
				ComponentsListView.SelectedItem = RuntimeViewModel?.Components.FirstOrDefault(item => item.Id == selectedId)
					?? RuntimeViewModel?.Components.FirstOrDefault();
				InventoryListSummary.Visibility = Visibility.Visible;
				StartBatchSelectionButton.Visibility = Visibility.Visible;
				BatchSelectionSummary.Visibility = Visibility.Collapsed;
				BatchSelectionActions.Visibility = Visibility.Collapsed;
		}

		private async void OnAddComponentClicked (object sender, RoutedEventArgs e)
		{
				if (RuntimeViewModel is { } viewModel)
						await RunComponentEditorAsync(viewModel, null);
		}

		private async Task RunComponentEditorAsync (MainViewModel viewModel, ComponentRecord? existing)
		{
				if (_componentEditorBusy)
						return;
				_componentEditorBusy = true;
				var editorDialog = new ComponentEditorDialog(
			XamlRoot, _catalogLookup, viewModel.StorageLocations.ToArray());
				try
				{
						while (true)
						{
								var request = await editorDialog.ShowAsync(existing);
								if (request is null)
										return;
								ComponentEditPlan plan;
								try
								{
										plan = viewModel.PrepareComponentEdit(request);
								}
								catch (Exception exception)
								{
										await ShowMessageAsync("无法准备保存",
											$"读取现有库存失败：{exception.Message}。输入已保留，可重试或取消。");
										continue;
								}
								if (plan.AppendTarget is not null)
								{
										var confirmed = await ConfirmCatalogAppendAsync(plan, viewModel);
										if (confirmed is false)
												return;
										if (confirmed is null)
												continue;
								}

								OperationResult result;
								try
								{
										result = viewModel.CommitComponentEdit(plan);
								}
								catch (Exception exception)
								{
										await ShowMessageAsync("无法确认保存结果",
											$"保存过程发生意外错误：{exception.Message}。请重新进入库存页核对后再操作，避免重复入库。");
										return;
								}
								await ShowOperationResultAsync(result);
								if (result.IsSuccess)
										return;
						}
				}
				finally
				{
						_componentEditorBusy = false;
				}
		}

		private async Task<bool?> ConfirmCatalogAppendAsync (
			ComponentEditPlan plan, MainViewModel viewModel)
		{
				var existing = plan.AppendTarget!;
				var draft = plan.Request;
				int combined;
				try
				{
						combined = checked(existing.Quantity + draft.Draft.Quantity);
				}
				catch (OverflowException)
				{
						await ShowMessageAsync("无法追加库存", "追加后库存超过整数范围。");
						return null;
				}
				var locationName = draft.NewLocationName
			?? viewModel.StorageLocations.FirstOrDefault(location =>
				location.Id == draft.Draft.Location)?.Name
			?? draft.Draft.Location;
				var dialog = new ContentDialog
				{
						Title = "确认追加现有 SKU",
						Content = new TextBlock
						{
								Text = $"{existing.Sku}\n当前库存：{existing.Quantity}\n本次入库：{draft.Draft.Quantity}\n确认后合计：{combined}\n入库库位：{locationName}\n\n现有元件名称、分类、封装和说明将保留。",
								TextWrapping = TextWrapping.Wrap,
						},
						PrimaryButtonText = "确认追加",
						CloseButtonText = AppStrings.Get("Common_Cancel"),
						DefaultButton = ContentDialogButton.Close,
						XamlRoot = XamlRoot,
				};
				return await dialog.ShowAsync() == ContentDialogResult.Primary;
		}

		private async void OnManageLocationsClicked (object sender, RoutedEventArgs e)
		{
				if (RuntimeViewModel is not { } viewModel)
						return;
				var selector = new ComboBox
				{
						ItemsSource = viewModel.StorageLocations,
						DisplayMemberPath = "Name",
						PlaceholderText = "选择已有库位",
						HorizontalAlignment = HorizontalAlignment.Stretch,
				};
				var idBox = CreateTextBox(null, "稳定编码，例如 A-01-02（保存后不可修改）");
				var nameBox = CreateTextBox(null, "显示名称，例如 电阻柜 A1");
				selector.SelectionChanged += (_, _) => { if (selector.SelectedItem is StorageLocationRecord item) { idBox.Text = item.Id; idBox.IsReadOnly = true; nameBox.Text = item.Name; } };
				var newButton = new Button { Content = "新建库位" };
				newButton.Click += (_, _) => { selector.SelectedItem = null; idBox.IsReadOnly = false; idBox.Text = string.Empty; nameBox.Text = string.Empty; };
				var deleteButton = new Button { Content = "删除所选库位" };
				var error = new TextBlock { TextWrapping = TextWrapping.Wrap };
				deleteButton.Click += (_, _) =>
				{
						if (selector.SelectedItem is not StorageLocationRecord item)
						{
								error.Text = "请先选择库位。";
								return;
						}
						var result = viewModel.DeleteStorageLocation(item.Id);
						error.Text = result.Message;
						if (result.IsSuccess)
						{
								selector.ItemsSource = viewModel.StorageLocations;
								selector.SelectedItem = null;
								idBox.Text = nameBox.Text = string.Empty;
								idBox.IsReadOnly = false;
						}
				};
				var panel = new StackPanel { Width = 480, Spacing = 10 };
				panel.Children.Add(CreateField("现有独立库位", selector));
				panel.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Children = { newButton, deleteButton } });
				panel.Children.Add(CreateField("库位编码", idBox));
				panel.Children.Add(CreateField("库位名称", nameBox));
				panel.Children.Add(error);
				var dialog = new ContentDialog
				{
						Title = "管理库位",
						Content = panel,
						PrimaryButtonText = "保存库位",
						CloseButtonText = AppStrings.Get("Common_Cancel"),
						XamlRoot = XamlRoot,
				};
				dialog.PrimaryButtonClick += (_, args) =>
				{
						var result = selector.SelectedItem is StorageLocationRecord selected
				? viewModel.UpdateStorageLocation(selected.Id, nameBox.Text)
				: viewModel.CreateStorageLocation(idBox.Text, nameBox.Text);
						if (!result.IsSuccess)
						{
								error.Text = result.Message;
								args.Cancel = true;
						}
				};
				await dialog.ShowAsync();
		}

		private async void OnBatchTransferClicked (object sender, RoutedEventArgs e)
		{
				if (!_isBatchSelecting || RuntimeViewModel is not { } viewModel)
						return;
				var selected = ComponentsListView.SelectedItems.Cast<ComponentRecord>().ToArray();
				if (selected.Length is 0 or > 500)
						return;

				var sourceIds = selected.SelectMany(component => component.Allocations
				.Where(allocation => allocation.Quantity > 0).Select(allocation => allocation.LocationId))
			.GroupBy(id => id, StringComparer.Ordinal)
			.Where(group => group.Count() == selected.Length)
			.Select(group => group.Key).OrderBy(id => id, StringComparer.Ordinal).ToArray();
				if (sourceIds.Length == 0)
				{
						await ShowMessageAsync("无法批量转移", "所选元器件没有共同且有库存的来源库位。请调整选择范围。");
						return;
				}

				var targetIds = viewModel.StorageLocations.Where(location => !location.Deleted)
			.Select(location => location.Id).OrderBy(id => id, StringComparer.Ordinal).ToArray();
				var allNames = viewModel.GetAllStorageLocations().ToDictionary(location => location.Id,
			location => location.Name, StringComparer.Ordinal);
				var duplicateNames = allNames.Values.GroupBy(name => name, StringComparer.OrdinalIgnoreCase)
			.Where(group => group.Count() > 1).Select(group => group.Key)
			.ToHashSet(StringComparer.OrdinalIgnoreCase);
				LocationOption Option (string id)
				{
						var name = allNames.GetValueOrDefault(id, id);
						return new(id, duplicateNames.Contains(name) ? $"{name} ({id})" : name);
				}
				var sourceBox = new ComboBox
				{
						Header = "来源库位",
						ItemsSource = sourceIds.Select(Option).ToArray(),
						SelectedIndex = 0,
						HorizontalAlignment = HorizontalAlignment.Stretch
				};
				var targetBox = new ComboBox
				{
						Header = "目标库位",
						ItemsSource = targetIds.Select(Option).ToArray(),
						SelectedIndex = -1,
						HorizontalAlignment = HorizontalAlignment.Stretch
				};
				var route = new Grid { ColumnSpacing = 12 };
				route.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
				route.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
				route.Children.Add(sourceBox);
				Grid.SetColumn(targetBox, 1);
				route.Children.Add(targetBox);

				var quantityRows = new StackPanel { Spacing = 4 };
				var inputs = new List<(ComponentRecord Component, NumberBox Input)>();
				void RebuildQuantityRows ()
				{
						quantityRows.Children.Clear();
						inputs.Clear();
						if (sourceBox.SelectedItem is not LocationOption source)
								return;
						var sourceId = source.Id;
						foreach (var component in selected)
						{
								var available = component.Allocations.First(allocation => allocation.LocationId == sourceId).Quantity;
								var row = new Grid { ColumnSpacing = 12, Padding = new Thickness(0, 6, 0, 6) };
								row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
								row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(116) });
								var caption = new StackPanel { Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
								caption.Children.Add(new TextBlock
								{
										Text = $"{component.Sku} · {component.Name}",
										FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
										TextTrimming = TextTrimming.CharacterEllipsis
								});
								caption.Children.Add(new TextBlock { Text = $"来源可用 {available} 件" });
								row.Children.Add(caption);
								var input = new NumberBox
								{
										Value = available,
										Minimum = 1,
										Maximum = available,
										SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact
								};
								AutomationProperties.SetName(input, $"{component.Sku} 转移数量");
								Grid.SetColumn(input, 1);
								row.Children.Add(input);
								quantityRows.Children.Add(row);
								inputs.Add((component, input));
						}
				}
				sourceBox.SelectionChanged += (_, _) => RebuildQuantityRows();
				RebuildQuantityRows();

				var form = new StackPanel { MaxWidth = 560, Spacing = 12 };
				form.Children.Add(new TextBlock
				{
						Text = $"第 1 步：选择库位并填写 {selected.Length} 项数量。",
						TextWrapping = TextWrapping.Wrap
				});
				form.Children.Add(route);
				form.Children.Add(new TextBlock { Text = "转移数量", FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
				form.Children.Add(new ScrollViewer
				{
						Content = quantityRows,
						MaxHeight = 340,
						VerticalScrollBarVisibility = ScrollBarVisibility.Auto
				});
				var formError = new TextBlock { TextWrapping = TextWrapping.Wrap };
				form.Children.Add(formError);
				var formDialog = new ContentDialog
				{
						Title = "批量转移 · 设置",
						Content = form,
						PrimaryButtonText = "下一步：复核",
						CloseButtonText = "取消",
						XamlRoot = XamlRoot,
				};
				formDialog.PrimaryButtonClick += (_, args) =>
				{
						if (sourceBox.SelectedItem is not LocationOption source || targetBox.SelectedItem is not LocationOption target
					|| source.Id == target.Id)
						{
								formError.Text = "请选择不同的来源库位和目标库位。";
								args.Cancel = true;
								return;
						}
						if (inputs.Any(item => !double.IsFinite(item.Input.Value)
					|| item.Input.Value != Math.Truncate(item.Input.Value)
					|| item.Input.Value < 1
					|| item.Input.Value > item.Component.Allocations.First(allocation => allocation.LocationId == source.Id).Quantity))
						{
								formError.Text = "每项数量必须是正整数，且不超过来源库位可用数量。";
								args.Cancel = true;
						}
				};
				if (await formDialog.ShowAsync() != ContentDialogResult.Primary)
						return;

				var source = ((LocationOption)sourceBox.SelectedItem).Id;
				var target = ((LocationOption)targetBox.SelectedItem).Id;
				var lines = inputs.Select(item => new InventoryStore.BatchTransferLine(
			item.Component.Id, (int)item.Input.Value, item.Component.UpdatedAt)).ToArray();
				var review = string.Join("\n", inputs.Select(item => $"{item.Component.Sku} · {item.Component.Name} · {item.Input.Value:0} 件"));
				var confirmation = new ContentDialog
				{
						Title = "批量转移 · 复核",
						Content = new ScrollViewer
						{
								MaxHeight = 420,
								Content = new TextBlock
								{
										Text = $"第 2 步：确认 {selected.Length} 项转移\n{Option(source)} → {Option(target)}\n\n{review}\n\n总库存保持不变。",
										TextWrapping = TextWrapping.Wrap,
								},
						},
						PrimaryButtonText = "确认转移",
						CloseButtonText = "返回库存",
						DefaultButton = ContentDialogButton.Close,
						XamlRoot = XamlRoot,
				};
				if (await confirmation.ShowAsync() != ContentDialogResult.Primary)
						return;
				var result = viewModel.TransferBatch(new(source, target, lines));
				await ShowOperationResultAsync(result);
				if (result.IsSuccess)
						EndBatchSelection();
		}

		private async void OnImportComponentHubClicked (object sender, RoutedEventArgs e)
		{
				var viewModel = RuntimeViewModel;
				if (viewModel is null)
						return;
				var picker = new FileOpenPicker();
				picker.FileTypeFilter.Add(".json");
				InitializeWithWindow.Initialize(
					picker,
					WindowNative.GetWindowHandle(( ( App )Application.Current ).Window)
				);
				var file = await picker.PickSingleFileAsync();
				if (file is null)
						return;

				var skipToggle = new ToggleSwitch
				{
						Header = "重复 SKU",
						OffContent = "阻止整个导入（默认）",
						OnContent = "明确跳过重复项",
				};
				var policyDialog = new ContentDialog
				{
						Title = "component-hub 迁移策略",
						Content = skipToggle,
						PrimaryButtonText = "生成预览",
						CloseButtonText = "取消",
						XamlRoot = XamlRoot,
				};
				if (await policyDialog.ShowAsync() != ContentDialogResult.Primary)
						return;
				var policy = skipToggle.IsOn ? DuplicateSkuPolicy.Skip : DuplicateSkuPolicy.Block;
				try
				{
						var preview = viewModel.PreviewComponentHubMigration(file.Path, policy);
						var text = string.Join(
				"\n",
				new[] { $"可导入：{preview.Items.Count}；跳过重复：{preview.SkippedDuplicates}" }
					.Concat(preview.Items.Take(12).Select(item => $"{item.Sku} · {item.Name} · 库存 {item.Quantity}"))
					.Concat(preview.Issues.Select(issue => $"阻止：{issue}"))
			);
						var previewDialog = new ContentDialog
						{
								Title = "确认 component-hub 迁移",
								Content = new ScrollViewer { Content = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap }, MaxHeight = 520 },
								PrimaryButtonText = "确认导入",
								CloseButtonText = "取消",
								DefaultButton = ContentDialogButton.Close,
								IsPrimaryButtonEnabled = preview.CanConfirm,
								XamlRoot = XamlRoot,
						};
						if (await previewDialog.ShowAsync() != ContentDialogResult.Primary)
								return;
						await ShowOperationResultAsync(viewModel.ImportComponentHub(new(preview.Fingerprint, policy, preview.Items)));
				}
				catch (Exception exception)
				{
						await ShowMessageAsync("迁移文件读取失败", exception.Message);
				}
		}

		private void OnKittingClicked (object sender, RoutedEventArgs e)
		{
				if (Application.Current is App { Window: MainWindow window })
						window.NavigateTo("Kitting");
		}
		private void OnBatchJlcInboundClicked (object sender, RoutedEventArgs e)
		{
				if (Application.Current is App { Window: MainWindow window })
						window.NavigateTo("BatchInbound");
		}

		private async void OnEditComponentClicked (object sender, RoutedEventArgs e)
		{
				var viewModel = RuntimeViewModel;
				if (viewModel is null)
				{
						return;
				}

				if (viewModel.SelectedComponent is null)
				{
						await ShowMessageAsync(
							AppStrings.Get("Components_Dialog_EditTitle"),
							AppStrings.Get("Components_Dialog_SelectFirstMessage")
						);
						return;
				}

				await RunComponentEditorAsync(viewModel, viewModel.SelectedComponent);
		}

		private async void OnDeleteComponentClicked (object sender, RoutedEventArgs e)
		{
				var viewModel = RuntimeViewModel;
				if (viewModel is null)
				{
						return;
				}

				if (viewModel.SelectedComponent is null)
				{
						await ShowMessageAsync(
							AppStrings.Get("Components_Dialog_DeleteTitle"),
							AppStrings.Get("Components_Dialog_SelectFirstMessage")
						);
						return;
				}

				var dialog = new ContentDialog
				{
						Title = AppStrings.Get("Components_Dialog_DeleteTitle"),
						PrimaryButtonText = AppStrings.Get("Components_Dialog_DeleteConfirmPrimary"),
						CloseButtonText = AppStrings.Get("Common_Cancel"),
						DefaultButton = ContentDialogButton.Close,
						XamlRoot = XamlRoot,
						Content = new TextBlock
						{
								Text = AppStrings.Format(
					"Components_Dialog_DeleteConfirmMessagePattern",
					viewModel.SelectedComponent.Name
				),
								TextWrapping = TextWrapping.Wrap,
								MaxWidth = 420,
						},
				};

				if (await dialog.ShowAsync() != ContentDialogResult.Primary)
				{
						return;
				}

				await ShowOperationResultAsync(viewModel.DeleteSelectedComponent());
		}

		private async Task TryLazyImageLookupAsync (ComponentRecord? component)
		{
				if (component is null || component.ProductImageUrl is not null || PrivateProductImageStore.KeyFromDescription(component.Description) is not null)
						return;
				var sku = LcscPublicCatalog.NormalizeSku(component.Sku);
				if (sku is null || !_lazyLookupAttempted.Add(sku))
						return;
				try
				{
						var metadata = await _catalogLookup.LookupAsync(sku);
						if (metadata?.ImageUrl is null || RuntimeViewModel is not { } viewModel)
								return;
						viewModel.Refresh();
				}
				catch { }
		}

		private async Task ShowOperationResultAsync (OperationResult result)
		{
				await ShowMessageAsync(
					result.IsSuccess
						? AppStrings.Get("Components_Dialog_OperationSuccessTitle")
						: AppStrings.Get("Components_Dialog_OperationFailureTitle"),
					result.Message
				);
		}

		private async Task ShowMessageAsync (string title, string message)
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

		private static TextBox CreateTextBox (string? value, string placeholderText) =>
			new()
			{
					Text = value ?? string.Empty,
					PlaceholderText = placeholderText
			};

		private static FrameworkElement CreateField (string label, FrameworkElement control)
		{
				var panel = new StackPanel { Spacing = 6 };
				panel.Children.Add(new TextBlock
				{
						Text = label,
						FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
				});
				panel.Children.Add(control);
				return panel;
		}

}
