namespace ComponentVault.WinUI.Models;

public sealed class MovementEntryDraft
{
    public string? PurchaseAmount { get; init; }
    public required string ComponentId { get; init; }
    public required string MovementType { get; init; }
    public required int Quantity { get; init; }
    public required string Reason { get; init; }
    public string Note { get; init; } = string.Empty;
    public string? LocationId { get; init; }
    public string? DestinationLocationId { get; init; }
}
