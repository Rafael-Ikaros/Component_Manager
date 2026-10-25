using ComponentVault.WinUI.Localization;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.System;
using Windows.UI;

namespace ComponentVault.WinUI.Views.Movements;

/// <summary>Collects a movement draft; persistence and refresh belong to the view models.</summary>
public sealed class MovementEntryDialog(
    XamlRoot xamlRoot,
    IReadOnlyList<ComponentRecord> components,
    IReadOnlyList<StorageLocationRecord> locations)
{
    private sealed record MovementTypeOption(string Value, string Label);
    private sealed record ComponentOption(ComponentRecord Component)
    {
        public string Label => $"{Component.Name} · {Component.Sku} · {Component.PackageName}";
        public override string ToString() => Label;
    }

    public async Task<MovementEntryDraft?> ShowAsync()
    {
        var componentOptions = components.Select(component => new ComponentOption(component)).ToArray();
        var selectedComponent = componentOptions.FirstOrDefault();
        var componentSearch = new AutoSuggestBox
        {
            PlaceholderText = "搜索 SKU、名称、封装或库位",
            TextMemberPath = nameof(ComponentOption.Label),
            Text = selectedComponent?.Label ?? "",
            ItemsSource = componentOptions.Take(20).ToArray(),
        };
        var movementTypeCombo = new ComboBox
        {
            ItemsSource = new[]
            {
                new MovementTypeOption("inbound", "入库"),
                new MovementTypeOption("outbound", "出库"),
                new MovementTypeOption("adjustment", "调整"),
                new MovementTypeOption("transfer", "库位调拨"),
            },
            DisplayMemberPath = nameof(MovementTypeOption.Label),
            SelectedIndex = 0,
        };
        var sourceLocationCombo = new ComboBox
        {
            ItemsSource = locations,
            DisplayMemberPath = nameof(StorageLocationRecord.Name),
            PlaceholderText = "选择来源/作业库位",
        };
        var destinationLocationCombo = new ComboBox
        {
            ItemsSource = locations,
            DisplayMemberPath = nameof(StorageLocationRecord.Name),
            PlaceholderText = "调拨目标库位",
        };
        void RefreshSourceLocations()
        {
            sourceLocationCombo.SelectedItem = selectedComponent is null
                ? null
                : locations.FirstOrDefault(item => item.Id == selectedComponent.Component.Location)
                    ?? locations.FirstOrDefault();
        }
        void SelectComponent(ComponentOption option)
        {
            selectedComponent = option;
            componentSearch.Text = option.Label;
            RefreshSourceLocations();
        }
        componentSearch.TextChanged += (sender, args) =>
        {
            if (args.Reason != AutoSuggestionBoxTextChangeReason.UserInput) return;
            selectedComponent = null;
            RefreshSourceLocations();
            sender.ItemsSource = componentOptions
                .Where(option => InventorySearch.Matches(option.Component, sender.Text))
                .Take(20).ToArray();
        };
        componentSearch.SuggestionChosen += (_, args) =>
        {
            if (args.SelectedItem is ComponentOption option) SelectComponent(option);
        };
        componentSearch.QuerySubmitted += (_, args) =>
        {
            if (args.ChosenSuggestion is ComponentOption option) SelectComponent(option);
        };
        // Enter confirms a suggestion without invoking the dialog's default Save button.
        componentSearch.AddHandler(UIElement.KeyDownEvent, new KeyEventHandler((_, args) =>
        {
            if (args.Key == VirtualKey.Enter) args.Handled = true;
        }), true);
        RefreshSourceLocations();
        var quantityBox = new NumberBox
        {
            Value = 1,
            Minimum = -100000,
            SmallChange = 1,
            SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact,
        };
        var reasonBox = new TextBox
        {
            PlaceholderText = "例如 到货、样机装配、盘点修正",
        };
        var noteBox = new TextBox
        {
            PlaceholderText = "填写批次、工单、责任人或额外说明",
            AcceptsReturn = true,
            MinHeight = 92,
            TextWrapping = TextWrapping.Wrap,
        };
        var helperText = new TextBlock
        {
            Foreground = new SolidColorBrush(Color.FromArgb(255, 96, 96, 96)),
            Text = "入库和出库请填写正数；库存调整可以填写正数或负数。",
            TextWrapping = TextWrapping.Wrap,
        };
        var errorText = new TextBlock
        {
            Foreground = new SolidColorBrush(Color.FromArgb(255, 176, 0, 32)),
            TextWrapping = TextWrapping.Wrap,
        };

        var panel = new StackPanel
        {
            Spacing = 16,
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        panel.Children.Add(CreateInlineField("元器件", componentSearch));
        panel.Children.Add(CreateInlineField("变动类型", movementTypeCombo));
        panel.Children.Add(CreateInlineField("作业 / 来源库位", sourceLocationCombo));
        panel.Children.Add(CreateInlineField("调拨目标库位", destinationLocationCombo));

        panel.Children.Add(CreateSectionHeader("数量与原因", "数量将直接影响本地库存。"));
        panel.Children.Add(CreateField("数量", quantityBox));
        panel.Children.Add(helperText);
        panel.Children.Add(CreateField("原因", reasonBox));

        panel.Children.Add(CreateSectionHeader("补充说明", "可填写工单号、批次或盘点备注。"));
        panel.Children.Add(CreateField("备注", noteBox));
        panel.Children.Add(errorText);

        var overlayLayout = new Grid
        {
            MaxHeight = 600,
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        overlayLayout.Children.Add(new ScrollViewer
        {
            Content = panel,
            Padding = new Thickness(0, 0, 24, 0),
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            HorizontalScrollMode = ScrollMode.Disabled,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            VerticalScrollMode = ScrollMode.Enabled,
        });

        MovementEntryDraft? draft = null;
        var dialog = new ContentDialog
        {
            Title = AppStrings.Get("Movements_Dialog_Title"),
            Content = overlayLayout,
            PrimaryButtonText = AppStrings.Get("Common_Save"),
            CloseButtonText = AppStrings.Get("Common_Cancel"),
            DefaultButton = ContentDialogButton.Primary,
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            XamlRoot = xamlRoot,
        };
        const double maximumDialogWidth = 720;
        const double maximumContentHeight = 600;
        const double verticalChromeAndMargins = 224;
        const double windowMargin = 24;
        var dialogPadding = new Thickness(24);
        dialog.Resources["ContentDialogMinWidth"] = 0d;
        dialog.Resources["ContentDialogMaxWidth"] = maximumDialogWidth;
        dialog.Resources["ContentDialogPadding"] = dialogPadding;
        void UpdateLayoutSize()
        {
            var dialogWidth = Math.Min(maximumDialogWidth, Math.Max(0, xamlRoot.Size.Width - 2 * windowMargin));
            var contentWidth = Math.Max(0, dialogWidth - dialogPadding.Left - dialogPadding.Right
                - dialog.BorderThickness.Left - dialog.BorderThickness.Right);
            if (overlayLayout.Width != contentWidth) overlayLayout.Width = contentWidth;
            var contentHeight = Math.Min(maximumContentHeight, Math.Max(0, xamlRoot.Size.Height - verticalChromeAndMargins));
            if (overlayLayout.MaxHeight != contentHeight) overlayLayout.MaxHeight = contentHeight;
        }
        void OnRootChanged(XamlRoot sender, XamlRootChangedEventArgs args) => UpdateLayoutSize();
        dialog.Opened += (_, _) =>
        {
            UpdateLayoutSize();
            movementTypeCombo.Focus(FocusState.Programmatic);
            componentSearch.IsSuggestionListOpen = false;
        };
        dialog.PrimaryButtonClick += (_, args) =>
        {
            if (selectedComponent is null || componentSearch.Text != selectedComponent.Label)
            {
                errorText.Text = "请从搜索建议中选择一个元器件。";
                args.Cancel = true;
                return;
            }

            if (movementTypeCombo.SelectedItem is not MovementTypeOption movementType)
            {
                errorText.Text = "请选择变动类型。";
                args.Cancel = true;
                return;
            }

            if (double.IsNaN(quantityBox.Value))
            {
                errorText.Text = "请输入有效数量。";
                args.Cancel = true;
                return;
            }
            if (sourceLocationCombo.SelectedItem is not StorageLocationRecord source)
            {
                errorText.Text = "请选择具体作业库位。";
                args.Cancel = true;
                return;
            }
            var destination = destinationLocationCombo.SelectedItem as StorageLocationRecord;
            if (movementType.Value == "transfer" && destination is null)
            {
                errorText.Text = "调拨时请选择目标库位。";
                args.Cancel = true;
                return;
            }

            draft = new MovementEntryDraft
            {
                ComponentId = selectedComponent.Component.Id,
                MovementType = movementType.Value,
                Quantity = (int)Math.Round(quantityBox.Value),
                Reason = reasonBox.Text,
                Note = noteBox.Text,
                LocationId = source.Id,
                DestinationLocationId = destination?.Id,
            };
        };

        UpdateLayoutSize();
        xamlRoot.Changed += OnRootChanged;
        try
        {
            var result = await dialog.ShowAsync();
            return result == ContentDialogResult.Primary ? draft : null;
        }
        finally
        {
            xamlRoot.Changed -= OnRootChanged;
        }
    }

    private static FrameworkElement CreateInlineField(string label, FrameworkElement control)
    {
        var grid = new Grid { ColumnSpacing = 12 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(3, GridUnitType.Star), MaxWidth = 126 });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(7, GridUnitType.Star) });
        var labelText = new TextBlock
        {
            Text = label,
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            VerticalAlignment = VerticalAlignment.Center,
            TextWrapping = TextWrapping.Wrap,
        };
        control.HorizontalAlignment = HorizontalAlignment.Stretch;
        control.VerticalAlignment = VerticalAlignment.Center;
        AutomationProperties.SetLabeledBy(control, labelText);
        Grid.SetColumn(control, 1);
        grid.Children.Add(labelText);
        grid.Children.Add(control);
        return grid;
    }

    private static FrameworkElement CreateField(string label, FrameworkElement control)
    {
        var panel = new StackPanel { Spacing = 6 };
        panel.Children.Add(new TextBlock { Text = label, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
        panel.Children.Add(control);
        return panel;
    }

    private static FrameworkElement CreateSectionHeader(string title, string description)
    {
        var panel = new StackPanel { Spacing = 2 };
        panel.Children.Add(
            new TextBlock
            {
                Text = title,
                FontSize = 18,
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            }
        );
        panel.Children.Add(
            new TextBlock
            {
                Text = description,
                Foreground = new SolidColorBrush(Color.FromArgb(255, 96, 96, 96)),
                TextWrapping = TextWrapping.Wrap,
            }
        );
        return panel;
    }
}
