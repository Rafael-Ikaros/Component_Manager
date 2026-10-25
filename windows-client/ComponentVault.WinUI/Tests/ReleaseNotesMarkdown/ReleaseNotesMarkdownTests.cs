using ComponentVault.WinUI.Services.Updates;
using Markdig.Extensions.Tables;
using Markdig.Extensions.TaskLists;
using Markdig.Syntax;
using Markdig.Syntax.Inlines;
using Xunit;

namespace ComponentVault.WinUI.ReleaseNotesTests;

public sealed class ReleaseNotesMarkdownTests
{
    [Fact]
    public void CommonReleaseFormattingRemainsStructuredAndUnicodeTextIsPreserved()
    {
        var document = ReleaseNotesMarkdown.Parse("## 更新日志\n\n修复 **同步**、*库位* 和 ~~旧行为~~，保留 `C123`。\n\n[部署说明](dockerhub.md)");

        Assert.Equal(2, Assert.IsType<HeadingBlock>(document[0]).Level);
        var emphasis = document.Descendants<EmphasisInline>().ToArray();
        Assert.Contains(emphasis, inline => inline.DelimiterChar == '*' && inline.DelimiterCount == 2);
        Assert.Contains(emphasis, inline => inline.DelimiterChar == '*' && inline.DelimiterCount == 1);
        Assert.Contains(emphasis, inline => inline.DelimiterChar == '~' && inline.DelimiterCount == 2);
        Assert.Equal("C123", Assert.Single(document.Descendants<CodeInline>()).Content);
        Assert.Equal("dockerhub.md", Assert.Single(document.Descendants<LinkInline>()).Url);
        Assert.Contains("更新日志", LiteralText(document));
        Assert.Contains("库位", LiteralText(document));
    }

    [Fact]
    public void NestedListsKeepOrderingStartAndSeparateItemStructure()
    {
        var document = ReleaseNotesMarkdown.Parse("""
            7. 第一项
               - 嵌套无序项

                 3. 嵌套有序项

            8. 第二项
            """);

        var outer = Assert.IsType<ListBlock>(Assert.Single(document));
        Assert.True(outer.IsOrdered);
        Assert.Equal("7", outer.OrderedStart);
        Assert.Equal(2, outer.Count);
        var firstItem = Assert.IsType<ListItemBlock>(outer[0]);
        var unordered = Assert.Single(firstItem.Descendants<ListBlock>(), list => !list.IsOrdered);
        Assert.Single(unordered);
        var ordered = Assert.Single(unordered.Descendants<ListBlock>());
        Assert.True(ordered.IsOrdered);
        Assert.Equal("3", ordered.OrderedStart);
        Assert.Contains("第二项", LiteralText(Assert.IsType<ListItemBlock>(outer[1])));
    }

    [Fact]
    public void LooseListItemsRetainContinuationParagraphsAndCodeBlocks()
    {
        var document = ReleaseNotesMarkdown.Parse("""
            - 第一段

              继续说明。

              ```text
              dotnet test
              ```

            - 第二项
            """);

        var list = Assert.IsType<ListBlock>(Assert.Single(document));
        Assert.Equal(2, list.Count);
        var item = Assert.IsType<ListItemBlock>(list[0]);
        Assert.Equal(2, item.OfType<ParagraphBlock>().Count());
        Assert.Contains("dotnet test", Assert.Single(item.OfType<FencedCodeBlock>()).Lines.ToString());
    }

    [Fact]
    public void HtmlIsLiteralWhileFencedCodeKeepsItsExactContent()
    {
        var document = ReleaseNotesMarkdown.Parse("""
            <script>alert('release')</script>

            ```html
            <div onclick="run()">保留内容</div>
            ```
            """);

        Assert.Empty(document.Descendants<HtmlBlock>());
        Assert.Empty(document.Descendants<HtmlInline>());
        Assert.Contains("<script>alert('release')</script>", LiteralText(document));
        var code = Assert.Single(document.Descendants<FencedCodeBlock>());
        Assert.Contains("<div onclick=\"run()\">保留内容</div>", code.Lines.ToString());
        Assert.Empty(code.Descendants<LinkInline>());
    }

    [Fact]
    public void TablesAndTaskListsExposeTheirSemanticNodes()
    {
        var document = ReleaseNotesMarkdown.Parse("""
            | 平台 | 状态 |
            | --- | --- |
            | Windows | **完成** |

            - [x] 已修复
            - [ ] 待验收
            """);

        var table = Assert.Single(document.Descendants<Table>());
        Assert.Equal(2, table.Count);
        Assert.True(Assert.IsType<TableRow>(table[0]).IsHeader);
        Assert.Equal(2, Assert.IsType<TableRow>(table[1]).Count);
        Assert.Equal(new[] { true, false }, document.Descendants<TaskList>().Select(task => task.Checked));
    }

    [Fact]
    public void ReferenceAndAutomaticLinksRetainDestinations()
    {
        var document = ReleaseNotesMarkdown.Parse("""
            查看 [说明][guide]，或访问 https://example.test/releases

            [guide]: docs/runbook.md
            """);

        var destinations = document.Descendants<LinkInline>().Select(link => link.Url).ToArray();
        Assert.Contains("docs/runbook.md", destinations);
        Assert.Contains("https://example.test/releases", destinations);
    }

    [Theory]
    [InlineData("https://example.test/path?q=1#details", "https://example.test/path?q=1#details")]
    [InlineData("http://example.test/releases", "http://example.test/releases")]
    [InlineData("  HTTPS://EXAMPLE.TEST/notes  ", "https://example.test/notes")]
    public void WebLinksRemainClickable(string input, string expected)
    {
        Assert.Equal(expected, ReleaseNotesMarkdown.ResolveLink(input)?.AbsoluteUri);
    }

    [Theory]
    [InlineData("docs/runbook.md", "blob/master/docs/runbook.md")]
    [InlineData("./docs/runbook.md#同步", "blob/master/docs/runbook.md#%E5%90%8C%E6%AD%A5")]
    [InlineData("dockerhub.md", "blob/master/docs/dockerhub.md")]
    [InlineData("dockerhub.md?plain=1#deploy", "blob/master/docs/dockerhub.md?plain=1#deploy")]
    [InlineData("dockerhub.md?return=/settings", "blob/master/docs/dockerhub.md?return=/settings")]
    [InlineData("DOCKERHUB.MD", "blob/master/docs/DOCKERHUB.MD")]
    [InlineData("images/example.png", "blob/master/images/example.png")]
    public void RelativeLinksUseTheCurrentRepositoryAndDocumentationBase(string input, string expectedPath)
    {
        var expected = $"https://github.com/{GitHubReleaseParser.Owner}/{GitHubReleaseParser.Repository}/{expectedPath}";
        Assert.Equal(expected, ReleaseNotesMarkdown.ResolveLink(input)?.AbsoluteUri);
    }

    [Fact]
    public void GitHubRootRelativeLinksRetainTheirRepositoryPath()
    {
        var path = $"/{GitHubReleaseParser.Owner}/{GitHubReleaseParser.Repository}/issues/42";
        Assert.Equal($"https://github.com{path}", ReleaseNotesMarkdown.ResolveLink(path)?.AbsoluteUri);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData(" \t\r\n")]
    [InlineData("javascript:alert(1)")]
    [InlineData("JaVaScRiPt:alert(1)")]
    [InlineData("data:text/html,<script>alert(1)</script>")]
    [InlineData("file:///C:/Windows/System32/cmd.exe")]
    [InlineData("C:\\Windows\\System32\\cmd.exe")]
    [InlineData("ms-appx:///App.xaml")]
    [InlineData("mailto:someone@example.test")]
    [InlineData("ftp://example.test/update.zip")]
    public void NonWebSchemesAndEmptyLinksCannotBeLaunched(string? input)
    {
        Assert.Null(ReleaseNotesMarkdown.ResolveLink(input));
    }

    [Fact]
    public void EmptyNotesProduceAnEmptyDocument()
    {
        Assert.Empty(ReleaseNotesMarkdown.Parse(""));
    }

    private static string LiteralText(MarkdownObject node) =>
        string.Concat(node.Descendants<LiteralInline>().Select(literal => literal.Content.ToString()));
}
