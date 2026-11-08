namespace ComponentVault.WinUI.Services;

public sealed class SyncComponentDto
{
    public string? InventoryUnitCost { get; init; }
    public required string Id { get; init; }
    public required string Sku { get; init; }
    public required string Name { get; init; }
    public required string Category { get; init; }
    public required string PackageName { get; init; }
    public required string Location { get; init; }
    public string? Description { get; init; }
    public required int Quantity { get; init; }
    public required int MinStock { get; init; }
    public required string UpdatedAt { get; init; }
    public required bool Deleted { get; init; }
    public string? BaseUpdatedAt { get; init; }
    public IReadOnlyList<SyncAllocationDto>? Allocations { get; init; }
}

public sealed class SyncAllocationDto
{
    public required string LocationId { get; init; }
    public required int Quantity { get; init; }
}

public sealed class SyncStorageLocationDto
{
    public required string Id { get; init; }
    public required string Name { get; init; }
    public required string UpdatedAt { get; init; }
    public required bool Deleted { get; init; }
}

public sealed class SyncStockMovementDto
{
    public string? PurchaseAmount { get; init; }
    public string? CostAmount { get; init; }
    public required string Id { get; init; }
    public required string ComponentId { get; init; }
    public required string MovementType { get; init; }
    public required int Quantity { get; init; }
    public required string Reason { get; init; }
    public string? Note { get; init; }
    public required string HappenedAt { get; init; }
    public required string UpdatedAt { get; init; }
    public required bool Deleted { get; init; }
    public string? LocationId { get; init; }
    public string? DestinationLocationId { get; init; }
}

public sealed class SyncPushRequest
{
    public int CostProtocol { get; init; } = 1;
    public int InventoryProtocol { get; init; } = 1;
    public required string DeviceId { get; init; }
    public IReadOnlyList<SyncComponentDto> Components { get; init; } = [];
    public IReadOnlyList<SyncStockMovementDto> StockMovements { get; init; } = [];
    public IReadOnlyList<SyncStorageLocationDto> StorageLocations { get; init; } = [];
}

public sealed class SyncPushResponse
{
    public required int AcceptedComponents { get; init; }
    public required int AcceptedStockMovements { get; init; }
    public required string ServerTime { get; init; }
}

public sealed class SyncPullResponse
{
    public int InventoryProtocol { get; init; }
    public int CostProtocol { get; init; }
    public required string ServerTime { get; init; }
    public long? SyncCursor { get; init; }
    public IReadOnlyList<SyncComponentDto> Components { get; init; } = [];
    public IReadOnlyList<SyncStockMovementDto> StockMovements { get; init; } = [];
    public IReadOnlyList<SyncStorageLocationDto> StorageLocations { get; init; } = [];
}

public sealed class SyncTokenStatusResponse
{
    public int PhotoProtocol { get; init; }
    public int BomPresetProtocol { get; init; }
    public required string Status { get; init; }
    public required string ServerTime { get; init; }
    public int InventoryProtocol { get; init; }
    public int CostProtocol { get; init; }
}

public sealed class SyncAccountIdentity
{
    public required string ServerId { get; init; }
    public required string AccountId { get; init; }
    public required string Name { get; init; }
    public required string Role { get; init; }
}

public sealed class ApiErrorResponse
{
    public string? Detail { get; init; }
}
