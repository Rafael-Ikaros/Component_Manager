using ComponentVault.WinUI.Localization;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services;
using ComponentVault.WinUI.Services.Catalog;
using ComponentVault.WinUI.ViewModels;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.UI;

namespace ComponentVault.WinUI.Views.Components;

public sealed class ComponentEditorDialog(
    XamlRoot xamlRoot,
    LcscPublicLookup catalogLookup,
    IReadOnlyList<StorageLocationRecord> locations)
{
    private readonly XamlRoot _xamlRoot = xamlRoot;
    private readonly LcscPublicLookup _catalogLookup = catalogLookup;
    private readonly IReadOnlyList<StorageLocationRecord> _locations = locations;
    private ComponentEditorViewModel? _editor;

    public async Task<ComponentEditRequest?> ShowAsync(ComponentRecord? existing)
    {
        var editor = _editor ??= new ComponentEditorViewModel(
            existing, _locations, "LOC-" + Guid.NewGuid().ToString("N")[..16].ToUpperInvariant());
        var skuBox = CreateTextBox(editor.Sku, "例如 RES-10K-0402");
        var nameBox = CreateTextBox(editor.Name, "例如 10k 电阻");
        var categoryBox = CreateTextBox(editor.Category, "例如 电阻");
        var packageBox = CreateTextBox(editor.PackageName, "例如 0402");
        var locationOptions = _locations.ToArray();
        var locationBox = new ComboBox
        {
            ItemsSource = locationOptions,
            DisplayMemberPath = "Name",
            PlaceholderText = "选择已有库位",
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        locationBox.SelectedItem = locationOptions.FirstOrDefault(item => item.Id == editor.SelectedLocationId);
        var newLocationToggle = new ToggleSwitch
        {
            Header = "库位来源", OffContent = "选择已有", OnContent = "新建库位",
            IsOn = editor.UseNewLocation,
        };
        var newLocationNameBox = new TextBox
        {
            Header = "新库位名称", PlaceholderText = "例如 电阻抽屉",
            Text = editor.NewLocationName,
        };
        var newLocationFields = new StackPanel { Spacing = 8 };
        newLocationFields.Children.Add(newLocationNameBox);
        void UpdateLocationMode()
        {
            locationBox.Visibility = newLocationToggle.IsOn ? Visibility.Collapsed : Visibility.Visible;
            newLocationFields.Visibility = newLocationToggle.IsOn ? Visibility.Visible : Visibility.Collapsed;
        }
        newLocationToggle.Toggled += (_, _) => UpdateLocationMode();
        UpdateLocationMode();
        var descriptionBox = new TextBox
        {
            Text = editor.Description,
            PlaceholderText = "填写用途、兼容料号或补货说明",
            AcceptsReturn = true,
            MinHeight = 96,
            TextWrapping = TextWrapping.Wrap,
        };
        var quantityBox = new NumberBox
        {
            Value = editor.Quantity,
            Minimum = 0,
            SmallChange = 1,
            SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact,
        };
        var minStockBox = new NumberBox
        {
            Value = editor.MinStock,
            Minimum = 0,
            SmallChange = 1,
            SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact,
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
        panel.Children.Add(CreateSectionHeader("基本信息", "名称、SKU、分类与封装。"));
        var lookupButton = new Button { Content = AppStrings.Get("Components_Catalog_ExactLookup") };
        var chinaSearchButton = new Button { Content = AppStrings.Get("Components_Catalog_OpenSearch") };
        var retryDomesticButton = new Button { Content = "重试国内查询 / Retry China lookup" };
        var skuActions = CreateActionRow(lookupButton, chinaSearchButton, retryDomesticButton);
        var skuPanel = new StackPanel { Spacing = 6 };
        skuPanel.Children.Add(skuBox);
        skuPanel.Children.Add(skuActions);
        panel.Children.Add(CreateField("SKU / 立创 C 编号", skuPanel));
        panel.Children.Add(CreateField("名称", nameBox));
        panel.Children.Add(CreateField("分类", categoryBox));
        panel.Children.Add(CreateField("封装", packageBox));

        panel.Children.Add(CreateSectionHeader(
            AppStrings.Get("Components_Catalog_SectionTitle"),
            AppStrings.Get("Components_Catalog_SectionDescription")
        ));
        var keywordBox = CreateTextBox(null, AppStrings.Get("Components_Catalog_KeywordPlaceholder"));
        var searchButton = new Button { Content = AppStrings.Get("Components_Catalog_Search") };
        var searchRow = new Grid { ColumnSpacing = 8 };
        searchRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        searchRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        searchRow.Children.Add(keywordBox);
        Grid.SetColumn(searchButton, 1);
        searchRow.Children.Add(searchButton);
        panel.Children.Add(searchRow);
        panel.Children.Add(new TextBlock
        {
            Text = "浏览器与 App 使用独立会话；在浏览器完成验证不保证 App 自动恢复。 Browser and app sessions are separate; browser verification may not restore app lookup.",
            TextWrapping = TextWrapping.Wrap,
            Opacity = 0.72,
        });

        var candidateList = new ListView
        {
            MaxHeight = 220,
            SelectionMode = ListViewSelectionMode.Single,
            Visibility = Visibility.Collapsed,
        };
        var parameterText = new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            IsTextSelectionEnabled = true,
            Visibility = Visibility.Collapsed,
        };
        var fillCandidateButton = new Button { Content = AppStrings.Get("Components_Catalog_FillSelected") };
        var openProductButton = new Button { Content = AppStrings.Get("Components_Catalog_OpenProduct") };
        var openDatasheetButton = new Button { Content = AppStrings.Get("Components_Catalog_OpenDatasheet"), IsEnabled = false };
        var candidateActions = CreateActionRow(fillCandidateButton, openProductButton, openDatasheetButton);
        candidateActions.Visibility = Visibility.Collapsed;
        panel.Children.Add(candidateList);
        panel.Children.Add(parameterText);
        panel.Children.Add(candidateActions);

        panel.Children.Add(CreateSectionHeader("库存与仓位", "数量与最低库存均不能为负数。"));
        panel.Children.Add(newLocationToggle);
        panel.Children.Add(CreateField("已有库位", locationBox));
        panel.Children.Add(newLocationFields);
        panel.Children.Add(CreateField("当前库存", quantityBox));
        panel.Children.Add(CreateField("最低库存", minStockBox));

        panel.Children.Add(CreateSectionHeader("备注", "填写用途、风险或替代料信息。"));
        panel.Children.Add(CreateField("描述 / 备注", descriptionBox));
        panel.Children.Add(errorText);

        retryDomesticButton.Click += async (_, _) =>
        {
            _catalogLookup.RetryDomesticNow();
            var normalized = LcscPublicCatalog.NormalizeSku(skuBox.Text);
            if (normalized is null) { errorText.Text = "请输入有效立创 C 编号后重试。 Enter a valid LCSC C-number before retrying."; return; }
            retryDomesticButton.IsEnabled = false;
            try
            {
                var lookup = await _catalogLookup.LookupDetailedAsync(normalized, LcscCatalogSource.Domestic);
                if (lookup.Metadata is { } metadata) FillFromCatalog(metadata);
                errorText.Text = lookup.UserMessage;
            }
            catch (Exception exception) { errorText.Text = exception.Message; }
            finally { retryDomesticButton.IsEnabled = true; }
        };

        lookupButton.Click += async (_, _) =>
        {
            var normalized = LcscPublicCatalog.NormalizeSku(skuBox.Text);
            if (normalized is null)
            {
                errorText.Text = "请输入 C 开头、后接 1–10 位数字的立创编号。";
                return;
            }
            lookupButton.IsEnabled = false;
            errorText.Text = "正在读取 LCSC 公共商品页…";
            try
            {
                var lookup = await _catalogLookup.LookupDetailedAsync(normalized);
                var metadata = lookup.Metadata;
                if (!string.Equals(LcscPublicCatalog.NormalizeSku(skuBox.Text), normalized, StringComparison.Ordinal))
                {
                    errorText.Text = "编号已更改，请按新编号重新查询。";
                    return;
                }
                if (metadata is null)
                {
                    errorText.Text = string.IsNullOrWhiteSpace(lookup.UserMessage)
                        ? "国内立创和国际 LCSC 均未返回精确匹配。"
                        : lookup.UserMessage;
                    return;
                }
                FillFromCatalog(metadata);
                errorText.Text = lookup.UserMessage;
            }
            catch (LcscDomesticBlockedException)
            {
                errorText.Text = AppStrings.Get("Components_Catalog_VerificationRequired");
            }
            catch (Exception) { errorText.Text = "商城查询失败，请稍后重试。"; }
            finally { lookupButton.IsEnabled = true; }
        };
        chinaSearchButton.Click += async (_, _) =>
        {
            var uri = LcscPublicCatalog.ChinaSearchUri(
                string.IsNullOrWhiteSpace(keywordBox.Text) ? skuBox.Text : keywordBox.Text
            );
            if (uri is null) { errorText.Text = AppStrings.Get("Components_Catalog_KeywordRequired"); return; }
            await Windows.System.Launcher.LaunchUriAsync(uri);
        };

        searchButton.Click += async (_, _) =>
        {
            var keyword = keywordBox.Text.Trim();
            if (keyword.Length == 0)
            {
                errorText.Text = AppStrings.Get("Components_Catalog_KeywordRequired");
                return;
            }
            searchButton.IsEnabled = false;
            candidateList.Visibility = Visibility.Collapsed;
            candidateActions.Visibility = Visibility.Collapsed;
            parameterText.Visibility = Visibility.Collapsed;
            errorText.Text = AppStrings.Get("Components_Catalog_Searching");
            try
            {
                var search = await _catalogLookup.SearchPreferredAsync(keyword);
                var results = search.Items;
                candidateList.ItemsSource = results.Select(item => new CatalogCandidateItem(item)).ToArray();
                candidateList.Visibility = results.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
                var resultMessage = results.Count == 0
                    ? AppStrings.Get("Components_Catalog_NoResults")
                    : AppStrings.Format("Components_Catalog_ResultCountPattern", results.Count);
                errorText.Text = string.IsNullOrWhiteSpace(search.Notice)
                    ? resultMessage : $"{search.Notice} {resultMessage}";
                if (results.Count > 0) candidateList.SelectedIndex = 0;
            }
            catch (LcscDomesticBlockedException)
            {
                errorText.Text = AppStrings.Get("Components_Catalog_VerificationRequired");
            }
            catch (LcscDomesticRateLimitedException) { errorText.Text = "国内立创请求过于频繁，已短暂限流。 China lookup is rate limited; retry after the cooldown."; }
            catch (LcscDomesticCoolingDownException) { errorText.Text = "国内查询暂时暂停，可使用“重试国内查询”立即重试。 China lookup is paused; use Retry China lookup to retry now."; }
            catch (Exception) { errorText.Text = "国内立创关键词搜索失败，请稍后重试或在浏览器中打开搜索。"; }
            finally { searchButton.IsEnabled = true; }
        };
        candidateList.SelectionChanged += (_, _) =>
        {
            var selected = (candidateList.SelectedItem as CatalogCandidateItem)?.Metadata;
            candidateActions.Visibility = selected is null ? Visibility.Collapsed : Visibility.Visible;
            if (selected is null)
            {
                parameterText.Visibility = Visibility.Collapsed;
                return;
            }
            parameterText.Text = FormatCatalogDetails(selected);
            parameterText.Visibility = Visibility.Visible;
            openDatasheetButton.IsEnabled = selected.DatasheetUrl is not null;
        };
        fillCandidateButton.Click += (_, _) =>
        {
            if ((candidateList.SelectedItem as CatalogCandidateItem)?.Metadata is not { } selected) return;
            FillFromCatalog(selected);
            errorText.Text = AppStrings.Get("Components_Catalog_CandidateFilled");
        };
        openProductButton.Click += async (_, _) =>
        {
            if ((candidateList.SelectedItem as CatalogCandidateItem)?.Metadata is { } selected
                && Uri.TryCreate(selected.OfficialUrl, UriKind.Absolute, out var uri))
                await Windows.System.Launcher.LaunchUriAsync(uri);
        };
        openDatasheetButton.Click += async (_, _) =>
        {
            var url = (candidateList.SelectedItem as CatalogCandidateItem)?.Metadata.DatasheetUrl;
            if (LcscPublicCatalog.TrustedDatasheetUrl(url) is { } trusted)
                await Windows.System.Launcher.LaunchUriAsync(new Uri(trusted));
        };

        ComponentEditRequest? draft = null;
        void FillFromCatalog(LcscProductMetadata metadata)
        {
            ApplyMetadata(metadata, skuBox, nameBox, categoryBox, packageBox, descriptionBox);
            editor.Sku = skuBox.Text;
            editor.Name = nameBox.Text;
            editor.Category = categoryBox.Text;
            editor.PackageName = packageBox.Text;
            editor.Description = descriptionBox.Text;
            editor.RecordCatalogFields(metadata.Sku);
        }
        var overlayLayout = new Grid
        {
            MaxHeight = 620,
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
        var dialog = new ContentDialog
        {
            Title = existing is null
                ? AppStrings.Get("Components_Dialog_AddTitle")
                : AppStrings.Get("Components_Dialog_EditTitle"),
            Content = overlayLayout,
            PrimaryButtonText = AppStrings.Get("Common_Save"),
            CloseButtonText = AppStrings.Get("Common_Cancel"),
            DefaultButton = ContentDialogButton.Primary,
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            XamlRoot = _xamlRoot,
        };
        const double maximumDialogWidth = 720;
        const double maximumContentHeight = 620;
        const double verticalChromeAndMargins = 224;
        const double windowMargin = 24;
        var dialogPadding = new Thickness(24);
        dialog.Resources["ContentDialogMinWidth"] = 0d;
        dialog.Resources["ContentDialogMaxWidth"] = maximumDialogWidth;
        dialog.Resources["ContentDialogPadding"] = dialogPadding;
        void UpdateLayoutSize()
        {
            var dialogWidth = Math.Min(maximumDialogWidth, Math.Max(0, _xamlRoot.Size.Width - 2 * windowMargin));
            var contentWidth = Math.Max(0, dialogWidth - dialogPadding.Left - dialogPadding.Right
                - dialog.BorderThickness.Left - dialog.BorderThickness.Right);
            if (overlayLayout.Width != contentWidth) overlayLayout.Width = contentWidth;
            var contentHeight = Math.Min(maximumContentHeight, Math.Max(0, _xamlRoot.Size.Height - verticalChromeAndMargins));
            if (overlayLayout.MaxHeight != contentHeight) overlayLayout.MaxHeight = contentHeight;
            var actionColumns = contentWidth - 24 >= 560 ? 3 : 1;
            UpdateActionRow(skuActions, actionColumns);
            UpdateActionRow(candidateActions, actionColumns);
        }
        void OnRootChanged(XamlRoot sender, XamlRootChangedEventArgs args) => UpdateLayoutSize();
        dialog.Opened += (_, _) => UpdateLayoutSize();
        dialog.PrimaryButtonClick += (_, args) =>
        {
            editor.Sku = skuBox.Text;
            editor.Name = nameBox.Text;
            editor.Category = categoryBox.Text;
            editor.PackageName = packageBox.Text;
            editor.Description = descriptionBox.Text;
            editor.Quantity = quantityBox.Value;
            editor.MinStock = minStockBox.Value;
            editor.SelectedLocationId = (locationBox.SelectedItem as StorageLocationRecord)?.Id;
            editor.UseNewLocation = newLocationToggle.IsOn;
            editor.NewLocationName = newLocationNameBox.Text;
            if (!editor.TryCreateRequest(out draft, out var error))
            {
                errorText.Text = error;
                args.Cancel = true;
            }
        };

        UpdateLayoutSize();
        _xamlRoot.Changed += OnRootChanged;
        try
        {
            var result = await dialog.ShowAsync();
            return result == ContentDialogResult.Primary ? draft : null;
        }
        finally
        {
            _xamlRoot.Changed -= OnRootChanged;
        }
    }

    private static string AppendOfficialNotes(string description, LcscProductMetadata metadata)
    {
        var notes = new List<string>();
        if (!string.IsNullOrWhiteSpace(description))
            notes.AddRange(description.Split(['\n', '；'], StringSplitOptions.RemoveEmptyEntries)
                .Select(note => note.Trim())
                .Where(note => metadata.ImageUrl is null || !note.StartsWith("商品图片：", StringComparison.Ordinal)));
        AddUnique(notes, metadata.Model is null ? null : $"型号：{metadata.Model}");
        AddUnique(notes, metadata.Description is null ? null : $"官方描述：{metadata.Description}");
        AddUnique(notes, metadata.Brand is null ? null : $"品牌：{metadata.Brand}");
        AddUnique(notes, $"官方商品页：{metadata.OfficialUrl}");
        AddUnique(notes, metadata.CategoryPath is null ? null : $"官方分类路径：{metadata.CategoryPath}");
        AddUnique(notes, metadata.ImageUrl is null ? null : $"商品图片：{metadata.ImageUrl}");
        AddUnique(notes, metadata.DatasheetUrl is null ? null : $"数据手册：{metadata.DatasheetUrl}");
        if (metadata.Parameters is not null)
            foreach (var parameter in metadata.Parameters) AddUnique(notes, $"参数·{parameter.Key}：{parameter.Value}");
        return string.Join("\n", notes);
    }

    private static void ApplyMetadata(
        LcscProductMetadata metadata,
        TextBox skuBox,
        TextBox nameBox,
        TextBox categoryBox,
        TextBox packageBox,
        TextBox descriptionBox
    )
    {
        if (string.IsNullOrWhiteSpace(skuBox.Text)) skuBox.Text = metadata.Sku;
        if (string.IsNullOrWhiteSpace(nameBox.Text)) nameBox.Text = OfficialSpecifications.AutoName(metadata);
        if (string.IsNullOrWhiteSpace(categoryBox.Text)) categoryBox.Text = metadata.Category;
        if (string.IsNullOrWhiteSpace(packageBox.Text) && !string.IsNullOrWhiteSpace(metadata.PackageName)) packageBox.Text = metadata.PackageName;
        descriptionBox.Text = AppendOfficialNotes(descriptionBox.Text, metadata);
    }

    private static string FormatCatalogDetails(LcscProductMetadata metadata)
    {
        var lines = new List<string>
        {
            $"{metadata.Sku} · {metadata.Name}",
            $"型号：{metadata.Model ?? "—"}   品牌：{metadata.Brand ?? "—"}",
            $"分类：{metadata.Category}   封装：{metadata.PackageName ?? "—"}",
        };
        if (!string.IsNullOrWhiteSpace(metadata.LookupNotice)) lines.Add(metadata.LookupNotice);
        if (metadata.Parameters is { Count: > 0 })
        {
            lines.Add("参数：");
            lines.AddRange(metadata.Parameters.Select(pair => $"  {pair.Key}：{pair.Value}"));
        }
        return string.Join("\n", lines);
    }

    private sealed record CatalogCandidateItem(LcscProductMetadata Metadata)
    {
        public override string ToString() => $"{Metadata.Sku} · {Metadata.Name} · {Metadata.Brand ?? "未知品牌"} · {Metadata.PackageName ?? "未知封装"}";
    }

    private static void AddUnique(List<string> notes, string? value)
    {
        if (!string.IsNullOrWhiteSpace(value) && !notes.Any(note => note.Contains(value, StringComparison.Ordinal))) notes.Add(value);
    }

    private static Grid CreateActionRow(params Button[] buttons)
    {
        var grid = new Grid { ColumnSpacing = 8, RowSpacing = 8 };
        foreach (var button in buttons)
        {
            button.Content = new TextBlock
            {
                Text = button.Content?.ToString() ?? string.Empty,
                TextWrapping = TextWrapping.Wrap,
                TextAlignment = TextAlignment.Center,
            };
            button.HorizontalAlignment = HorizontalAlignment.Stretch;
            button.VerticalAlignment = VerticalAlignment.Stretch;
            grid.Children.Add(button);
        }
        return grid;
    }

    private static void UpdateActionRow(Grid grid, int columns)
    {
        if (grid.ColumnDefinitions.Count == columns) return;
        grid.ColumnDefinitions.Clear();
        grid.RowDefinitions.Clear();
        for (var column = 0; column < columns; column++)
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        for (var row = 0; row < (grid.Children.Count + columns - 1) / columns; row++)
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        for (var index = 0; index < grid.Children.Count; index++)
        {
            var element = (FrameworkElement)grid.Children[index];
            Grid.SetColumn(element, index % columns);
            Grid.SetRow(element, index / columns);
        }
    }

    private static TextBox CreateTextBox(string? value, string placeholderText) =>
        new()
        {
            Text = value ?? string.Empty,
            PlaceholderText = placeholderText,
        };

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
