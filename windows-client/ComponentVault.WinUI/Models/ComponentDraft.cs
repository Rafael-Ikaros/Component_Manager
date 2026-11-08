namespace ComponentVault.WinUI.Models;

public sealed class ComponentDraft
{
    public string? Id { get; init; }
    public string? PurchaseAmount { get; init; }
    public required string Sku { get; init; }
    public required string Name { get; init; }
    public required string Category { get; init; }
    public required string PackageName { get; init; }
    public required string Location { get; init; }
    public string Description { get; init; } = string.Empty;
    public required int Quantity { get; init; }
    public required int MinStock { get; init; }
}
