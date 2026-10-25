using Markdig;
using Markdig.Extensions.EmphasisExtras;
using Markdig.Syntax;

namespace ComponentVault.WinUI.Services.Updates;

public static class ReleaseNotesMarkdown
{
    private static readonly MarkdownPipeline Pipeline = new MarkdownPipelineBuilder { MaximumNestingDepth = 32 }
        .DisableHtml().UseAutoLinks().UsePipeTables().UseTaskLists()
        .UseEmphasisExtras(EmphasisExtraOptions.Strikethrough).Build();
    private static readonly Uri RepositoryBase = new(
        $"https://github.com/{GitHubReleaseParser.Owner}/{GitHubReleaseParser.Repository}/blob/master/");

    public static MarkdownDocument Parse(string text) => Markdown.Parse(text, Pipeline);

    public static Uri? ResolveLink(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        var link = value.Trim();
        if (!Uri.TryCreate(link, UriKind.Absolute, out var uri))
        {
            // Release notes use documentation-relative links such as dockerhub.md.
            var path = link.Split(['?', '#'])[0];
            var baseUri = !path.Contains('/') && path.EndsWith(".md", StringComparison.OrdinalIgnoreCase)
                ? new Uri(RepositoryBase, "docs/") : RepositoryBase;
            if (!Uri.TryCreate(baseUri, link, out uri)) return null;
        }
        return uri.Scheme is "https" or "http" ? uri : null;
    }
}
