using System.Globalization;
using ComponentVault.WinUI.Services.Updates;
using Markdig.Extensions.Tables;
using Markdig.Extensions.TaskLists;
using Markdig.Syntax;
using Markdig.Syntax.Inlines;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Documents;
using Microsoft.UI.Xaml.Media;
using Windows.UI.Text;
using FontWeights = Microsoft.UI.Text.FontWeights;
using MarkdownBlock = Markdig.Syntax.Block;
using MarkdownInline = Markdig.Syntax.Inlines.Inline;

namespace ComponentVault.WinUI.Controls;

/// <summary>Renders release notes with selectable native text, without HTML or remote media.</summary>
public sealed class ReleaseNotesMarkdownView : StackPanel
{
    private const int MaximumCharacters = 100_000;
    private const int MaximumNodes = 2_500;
    private const int MaximumDepth = 32;
    private const int MaximumTableColumns = 8;
    private static readonly FontFamily CodeFont = new("Consolas");
    private int _remainingNodes;
    private bool _truncated;

    public static readonly DependencyProperty TextProperty = DependencyProperty.Register(
        nameof(Text), typeof(string), typeof(ReleaseNotesMarkdownView),
        new PropertyMetadata(string.Empty, OnTextChanged));

    public ReleaseNotesMarkdownView()
    {
        Spacing = 10;
        HorizontalAlignment = HorizontalAlignment.Stretch;
    }

    public string Text
    {
        get => (string)GetValue(TextProperty);
        set => SetValue(TextProperty, value ?? string.Empty);
    }

    private static void OnTextChanged(DependencyObject sender, DependencyPropertyChangedEventArgs args) =>
        ((ReleaseNotesMarkdownView)sender).Rebuild();

    private void Rebuild()
    {
        Children.Clear();
        _remainingNodes = MaximumNodes;
        var markdown = Text ?? string.Empty;
        _truncated = markdown.Length > MaximumCharacters;
        if (_truncated) markdown = markdown[..MaximumCharacters];
        if (string.IsNullOrWhiteSpace(markdown)) return;

        MarkdownDocument document;
        try
        {
            document = ReleaseNotesMarkdown.Parse(markdown);
        }
        catch (Exception)
        {
            // Malformed or excessively nested notes must not break the update screen.
            Children.Add(CreatePlainText(markdown));
            if (_truncated) AddTruncationNotice();
            return;
        }
        RenderBlocks(document, this, 0);
        if (_truncated) AddTruncationNotice();
    }

    private void AddTruncationNotice() =>
        Children.Add(CreatePlainText("更新日志内容较长，部分内容已省略。请在 Release 页面查看全文。"));

    private bool CanRender(int depth)
    {
        if (depth > MaximumDepth || _remainingNodes <= 0)
        {
            _truncated = true;
            return false;
        }
        _remainingNodes--;
        return true;
    }

    private void RenderBlocks(ContainerBlock blocks, Panel target, int depth)
    {
        foreach (var block in blocks)
        {
            if (!CanRender(depth)) break;
            var element = RenderBlock(block, depth);
            if (element is not null) target.Children.Add(element);
        }
    }

    private FrameworkElement? RenderBlock(MarkdownBlock block, int depth)
    {
        switch (block)
        {
            case HeadingBlock heading:
                var headingText = CreateText(heading, depth);
                headingText.FontSize = heading.Level switch { 1 => 24, 2 => 21, 3 => 18, _ => 15 };
                headingText.FontWeight = FontWeights.SemiBold;
                headingText.Margin = new Thickness(0, 6, 0, 0);
                return headingText;
            case ParagraphBlock paragraph:
                return CreateText(paragraph, depth);
            case CodeBlock code:
                var codeText = CreatePlainText(code.Lines.ToString());
                codeText.FontFamily = CodeFont;
                codeText.FontSize = 13;
                return CreateInset(codeText);
            case QuoteBlock quote:
                var quotePanel = CreateBlockPanel();
                RenderBlocks(quote, quotePanel, depth + 1);
                var quoteBorder = CreateInset(quotePanel);
                quoteBorder.BorderThickness = new Thickness(3, 0, 0, 0);
                return quoteBorder;
            case ListBlock list:
                return CreateList(list, depth + 1);
            case ThematicBreakBlock:
                return new Border
                {
                    Height = 1,
                    Margin = new Thickness(0, 5, 0, 5),
                    Background = FindResource<Brush>("VaultBorderBrush"),
                };
            case Table table:
                return CreateTable(table, depth + 1);
            case LinkReferenceDefinition:
                return null;
            case ContainerBlock container:
                var children = CreateBlockPanel();
                RenderBlocks(container, children, depth + 1);
                return children;
            case LeafBlock leaf:
                return CreateText(leaf, depth);
            default:
                return null;
        }
    }

    private StackPanel CreateList(ListBlock list, int depth)
    {
        var panel = CreateBlockPanel();
        var number = long.TryParse(list.OrderedStart, NumberStyles.None, CultureInfo.InvariantCulture, out var start)
            ? start : 1;
        foreach (var block in list)
        {
            if (!CanRender(depth)) break;
            if (block is not ListItemBlock item) continue;
            var row = new Grid { ColumnSpacing = 8 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var marker = CreatePlainText(list.IsOrdered ? $"{number}{list.OrderedDelimiter}" : "•");
            marker.MinWidth = 14;
            var body = CreateBlockPanel();
            RenderBlocks(item, body, depth + 1);
            Grid.SetColumn(body, 1);
            row.Children.Add(marker);
            row.Children.Add(body);
            panel.Children.Add(row);
            if (number < long.MaxValue) number++;
        }
        return panel;
    }

    private Grid CreateTable(Table table, int depth)
    {
        var grid = new Grid();
        var columns = Math.Min(MaximumTableColumns, table.ColumnDefinitions.Count);
        if (columns == 0) return grid;
        if (table.ColumnDefinitions.Count > columns) _truncated = true;
        for (var column = 0; column < columns; column++)
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        foreach (var block in table)
        {
            if (!CanRender(depth)) break;
            if (block is not TableRow row) continue;
            var rowIndex = grid.RowDefinitions.Count;
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            foreach (var cellBlock in row)
            {
                if (!CanRender(depth + 1)) break;
                if (cellBlock is not TableCell cell || cell.ColumnIndex >= columns) continue;
                var content = CreateBlockPanel();
                RenderBlocks(cell, content, depth + 2);
                if (row.IsHeader)
                    foreach (var text in content.Children.OfType<RichTextBlock>()) text.FontWeight = FontWeights.SemiBold;
                var border = CreateInset(content);
                border.Padding = new Thickness(8);
                border.CornerRadius = new CornerRadius(0);
                Grid.SetRow(border, rowIndex);
                Grid.SetColumn(border, cell.ColumnIndex);
                Grid.SetColumnSpan(border, Math.Clamp(cell.ColumnSpan, 1, columns - cell.ColumnIndex));
                grid.Children.Add(border);
            }
        }
        return grid;
    }

    private RichTextBlock CreateText(LeafBlock block, int depth)
    {
        var text = CreateTextBlock();
        var paragraph = new Paragraph { Margin = new Thickness(0) };
        if (block.Inline is { } inline) RenderInlines(inline, paragraph.Inlines, depth + 1);
        else paragraph.Inlines.Add(new Run { Text = block.Lines.ToString() });
        text.Blocks.Add(paragraph);
        return text;
    }

    private void RenderInlines(ContainerInline container, InlineCollection target, int depth, bool insideLink = false)
    {
        for (var inline = container.FirstChild; inline is not null; inline = inline.NextSibling)
        {
            if (!CanRender(depth)) break;
            switch (inline)
            {
                case LiteralInline literal:
                    target.Add(new Run { Text = literal.Content.ToString() });
                    break;
                case CodeInline code:
                    target.Add(new Run { Text = code.Content, FontFamily = CodeFont });
                    break;
                case LineBreakInline lineBreak:
                    if (lineBreak.IsHard) target.Add(new LineBreak());
                    else target.Add(new Run { Text = " " });
                    break;
                case HtmlEntityInline entity:
                    target.Add(new Run { Text = entity.Transcoded.ToString() });
                    break;
                case HtmlInline html:
                    target.Add(new Run { Text = html.Tag });
                    break;
                case TaskList task:
                    target.Add(new Run { Text = task.Checked ? "☑ " : "☐ " });
                    break;
                case EmphasisInline emphasis:
                    var emphasisSpan = new Span();
                    if (emphasis.DelimiterChar == '~' && emphasis.DelimiterCount == 2)
                        emphasisSpan.TextDecorations = TextDecorations.Strikethrough;
                    else if (emphasis.DelimiterCount >= 2) emphasisSpan.FontWeight = FontWeights.Bold;
                    else emphasisSpan.FontStyle = FontStyle.Italic;
                    RenderInlines(emphasis, emphasisSpan.Inlines, depth + 1, insideLink);
                    target.Add(emphasisSpan);
                    break;
                case LinkInline { IsImage: true } image:
                    target.Add(new Run { Text = "[图片：" });
                    RenderInlines(image, target, depth + 1, true);
                    target.Add(new Run { Text = "]" });
                    break;
                case LinkInline link:
                    var uri = insideLink ? null : ReleaseNotesMarkdown.ResolveLink(link.Url);
                    if (uri is null) RenderInlines(link, target, depth + 1, insideLink);
                    else
                    {
                        var hyperlink = new Hyperlink { NavigateUri = uri };
                        RenderInlines(link, hyperlink.Inlines, depth + 1, true);
                        target.Add(hyperlink);
                    }
                    break;
                case AutolinkInline autolink:
                    var autoUri = insideLink || autolink.IsEmail ? null : ReleaseNotesMarkdown.ResolveLink(autolink.Url);
                    if (autoUri is null) target.Add(new Run { Text = autolink.Url });
                    else
                    {
                        var hyperlink = new Hyperlink { NavigateUri = autoUri };
                        hyperlink.Inlines.Add(new Run { Text = autolink.Url });
                        target.Add(hyperlink);
                    }
                    break;
                case ContainerInline nested:
                    RenderInlines(nested, target, depth + 1, insideLink);
                    break;
                default:
                    AppendSourceText(inline, target);
                    break;
            }
        }
    }

    private void AppendSourceText(MarkdownInline inline, InlineCollection target)
    {
        // Keep unsupported inline extensions readable instead of discarding their source.
        var source = Text ?? string.Empty;
        if (inline.Span.Start >= 0 && inline.Span.End >= inline.Span.Start && inline.Span.End < source.Length)
            target.Add(new Run { Text = source.Substring(inline.Span.Start, inline.Span.Length) });
    }

    private static StackPanel CreateBlockPanel() => new()
    {
        Spacing = 6,
        HorizontalAlignment = HorizontalAlignment.Stretch,
    };

    private static RichTextBlock CreateTextBlock() => new()
    {
        FontSize = 14,
        IsTextSelectionEnabled = true,
        TextWrapping = TextWrapping.Wrap,
        HorizontalAlignment = HorizontalAlignment.Stretch,
    };

    private static RichTextBlock CreatePlainText(string text)
    {
        var block = CreateTextBlock();
        var paragraph = new Paragraph { Margin = new Thickness(0) };
        paragraph.Inlines.Add(new Run { Text = text });
        block.Blocks.Add(paragraph);
        return block;
    }

    private static Border CreateInset(UIElement content) => new()
    {
        Style = FindResource<Style>("CardBorderStyle"),
        Padding = new Thickness(12),
        CornerRadius = new CornerRadius(4),
        Child = content,
    };

    private static T? FindResource<T>(string key) where T : class =>
        Application.Current.Resources.TryGetValue(key, out var value) ? value as T : null;
}
