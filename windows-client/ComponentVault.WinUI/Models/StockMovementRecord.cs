using System.Globalization;

namespace ComponentVault.WinUI.Models;

public sealed class StockMovementRecord
{
    public string? PurchaseAmount { get; init; }
    public string? CostAmount { get; init; }
    public required string Id { get; init; }
    public required string ComponentId { get; init; }
    public required string ComponentSku { get; init; }
    public required string ComponentName { get; init; }
    public required string MovementType { get; init; }
    public required int Quantity { get; init; }
    public required string Reason { get; init; }
    public required string Note { get; init; }
    public required string HappenedAt { get; init; }
    public required string UpdatedAt { get; init; }
    public required bool Deleted { get; init; }
    public string? LocationId { get; init; }
    public string? DestinationLocationId { get; init; }

    public long QuantityChange => MovementType.Trim().ToLowerInvariant() switch
    {
        "outbound" => -Math.Abs((long)Quantity),
        "inbound" => Math.Abs((long)Quantity),
        "transfer" => 0,
        _ => Quantity,
    };

    public string QuantityLabel => QuantityChange.ToString("+#,0;-#,0;0");

    public string MovementTypeLabel => MovementType.Trim().ToLowerInvariant() switch
    {
        "inbound" => "入库",
        "outbound" => "出库",
        "adjustment" => "调整",
        "transfer" => "调拨",
        _ => MovementType,
    };

    public string DisplayHappenedAt => FormatLocalDateTime(HappenedAt);

    public string DisplayUpdatedAt => FormatLocalDateTime(UpdatedAt);

    public string DisplayTimeline =>
        $"{DisplayHappenedAt} · 更新于 {DisplayUpdatedAt}";

    private static string FormatLocalDateTime(string value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return "—";
        }

        return DateTimeOffset.TryParse(
            value,
            CultureInfo.InvariantCulture,
            DateTimeStyles.AllowWhiteSpaces | DateTimeStyles.AssumeUniversal,
            out var timestamp
        )
            ? timestamp.ToLocalTime().ToString("g", CultureInfo.CurrentCulture)
            : value;
    }
}
