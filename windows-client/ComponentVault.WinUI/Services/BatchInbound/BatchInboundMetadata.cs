namespace ComponentVault.WinUI.Services.BatchInbound;

public sealed record BatchInboundMetadata(string Name, string Category, string PackageName, string Description)
{
    public bool HasRequiredFields => !string.IsNullOrWhiteSpace(Name)
        && !string.IsNullOrWhiteSpace(Category) && !string.IsNullOrWhiteSpace(PackageName);

    public BatchInboundMetadata FillMissingFrom(BatchInboundMetadata fallback) => new(
        Fill(Name, fallback.Name), Fill(Category, fallback.Category),
        Fill(PackageName, fallback.PackageName), Fill(Description, fallback.Description));

    private static string Fill(string current, string fallback) =>
        string.IsNullOrWhiteSpace(current) ? fallback : current;
}
