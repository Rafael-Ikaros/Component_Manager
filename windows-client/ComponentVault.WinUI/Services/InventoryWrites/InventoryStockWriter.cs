using System.Globalization;
using ComponentVault.WinUI.Models;
using Microsoft.Data.Sqlite;
using static ComponentVault.WinUI.Services.InventoryWrites.InventoryWriteSql;

namespace ComponentVault.WinUI.Services.InventoryWrites;

internal sealed class InventoryStockWriter
{
    private readonly InventoryDatabase _database;

    internal InventoryStockWriter(InventoryDatabase database) => _database = database;

    public OperationResult TransferBatch(InventoryStore.BatchTransferRequest request)
    {
        if (request.Lines.Count is 0 or > 500 || request.Lines.Any(line => line.Quantity <= 0)
            || request.Lines.Select(line => line.ComponentId).Distinct(StringComparer.Ordinal).Count() != request.Lines.Count)
            return OperationResult.Failure("请选择 1–500 个不重复元器件，并填写正整数调拨数量。");
        var source = NormalizeLocationId(request.SourceLocationId);
        var destination = NormalizeLocationId(request.DestinationLocationId);
        if (source == destination) return OperationResult.Failure("目标库位不能与来源相同。");

        try
        {
            using var connection = _database.OpenConnection();
            using var transaction = connection.BeginTransaction();
            using (var location = connection.CreateCommand())
            {
                location.CommandText = "SELECT COUNT(*) FROM storage_locations WHERE id IN ($source,$destination) AND deleted=0";
                location.Parameters.AddWithValue("$source", source);
                location.Parameters.AddWithValue("$destination", destination);
                if (Convert.ToInt64(location.ExecuteScalar(), CultureInfo.InvariantCulture) != 2)
                    return OperationResult.Failure("来源或目标库位不存在或已删除。");
            }

            var validated = new List<(ComponentRecord Component, int Quantity)>();
            foreach (var line in request.Lines)
            {
                var component = InventoryQueries.GetComponentById(connection, line.ComponentId);
                if (component is null || component.Deleted)
                    return OperationResult.Failure("选中的元器件已不存在，请重新核对。");
                if (component.UpdatedAt != line.ExpectedUpdatedAt)
                    return OperationResult.Failure($"{component.Sku} 已变化，请重新核对调拨数量。");
                if (GetAllocationQuantity(connection, component.Id, source) < line.Quantity)
                    return OperationResult.Failure($"{component.Sku} 的来源库位库存不足。");
                _ = checked(GetAllocationQuantity(connection, component.Id, destination) + line.Quantity);
                validated.Add((component, line.Quantity));
            }

            foreach (var (component, quantity) in validated)
            {
                var result = TransferAllocation(connection, component, source, destination, quantity, "批量库位转移", "");
                if (!result.IsSuccess) return result;
            }
            transaction.Commit();
            return OperationResult.Success($"已将 {validated.Count} 个元器件从 {source} 转移到 {destination}；总库存未变化。");
        }
        catch (Exception exception) when (exception is OverflowException or SqliteException)
        {
            return OperationResult.Failure($"批量转移失败，未提交任何项目：{exception.Message}");
        }
    }

    public OperationResult RecordMovement(MovementEntryDraft draft)
    {
        ValidateMovementDraft(draft);

        using var connection = _database.OpenConnection();
        using var transaction = connection.BeginTransaction();

        var component = InventoryQueries.GetComponentById(connection, draft.ComponentId);
        if (component is null || component.Deleted)
        {
            return OperationResult.Failure("请先选择一个可用元器件。");
        }

        var locationId = NormalizeLocationId(draft.LocationId ?? component.Location);
        if (draft.MovementType.Equals("transfer", StringComparison.OrdinalIgnoreCase))
        {
            var result = TransferAllocation(connection, component, locationId, NormalizeLocationId(draft.DestinationLocationId), draft.Quantity, draft.Reason, draft.Note);
            if (result.IsSuccess) transaction.Commit();
            return result;
        }
        var quantityDelta = CalculateQuantityDelta(draft.MovementType, draft.Quantity);
        var newQuantity = component.Quantity + quantityDelta;
        if (newQuantity < 0)
        {
            return OperationResult.Failure("这次变动会让库存变成负数。");
        }
        var currentAllocation = GetAllocationQuantity(connection, component.Id, locationId);
        var newAllocation = currentAllocation + quantityDelta;
        if (newAllocation < 0) return OperationResult.Failure("所选库位的库存不足。");

        var happenedAt = UtcNow();
        var movementId = $"mov-{Guid.NewGuid():N}";
        using var insertMovement = connection.CreateCommand();
        insertMovement.CommandText =
            """
            INSERT INTO stock_movements (
                id,
                component_id,
                movement_type,
                quantity,
                reason,
                note,
                happened_at,
                updated_at,
                deleted,
                location_id,
                destination_location_id
            ) VALUES (
                $id,
                $component_id,
                $movement_type,
                $quantity,
                $reason,
                $note,
                $happened_at,
                $updated_at,
                0
                , $location_id, NULL
            )
            """;
        insertMovement.Parameters.AddWithValue("$id", movementId);
        insertMovement.Parameters.AddWithValue("$component_id", draft.ComponentId);
        insertMovement.Parameters.AddWithValue(
            "$movement_type",
            draft.MovementType.Trim().ToLowerInvariant()
        );
        insertMovement.Parameters.AddWithValue(
            "$quantity",
            NormalizeMovementQuantity(draft.MovementType, draft.Quantity)
        );
        insertMovement.Parameters.AddWithValue("$reason", draft.Reason.Trim());
        insertMovement.Parameters.AddWithValue("$note", draft.Note.Trim());
        insertMovement.Parameters.AddWithValue("$happened_at", happenedAt);
        insertMovement.Parameters.AddWithValue("$updated_at", happenedAt);
        insertMovement.Parameters.AddWithValue("$location_id", locationId);
        insertMovement.ExecuteNonQuery();

        SetAllocationQuantity(connection, component.Id, locationId, newAllocation);

        using var updateComponent = connection.CreateCommand();
        updateComponent.CommandText =
            """
            UPDATE components
            SET quantity = $quantity, updated_at = $updated_at
            WHERE id = $id
            """;
        updateComponent.Parameters.AddWithValue("$quantity", newQuantity);
        updateComponent.Parameters.AddWithValue("$updated_at", happenedAt);
        updateComponent.Parameters.AddWithValue("$id", draft.ComponentId);
        updateComponent.ExecuteNonQuery();

        var purchase = draft.MovementType.Equals("inbound", StringComparison.OrdinalIgnoreCase) ? InventoryCost.Input(draft.PurchaseAmount) : null;
        var average = newQuantity == 0 ? null : purchase is not null || draft.MovementType.Equals("inbound", StringComparison.OrdinalIgnoreCase)
            ? InventoryCost.Inbound(component.Quantity, component.InventoryUnitCost, draft.Quantity, purchase) : component.InventoryUnitCost;
        SetCost(connection, component.Id, average, movementId, purchase, draft.MovementType.Equals("inbound", StringComparison.OrdinalIgnoreCase) ? purchase : InventoryCost.Multiply(component.InventoryUnitCost, Math.Abs(quantityDelta)));
        EnqueueEntity(connection, "component", draft.ComponentId, happenedAt);
        EnqueueEntity(connection, "stock_movement", movementId, happenedAt);

        transaction.Commit();
        return OperationResult.Success("库存变动已记录到本机。");
    }

    private static OperationResult TransferAllocation(
        SqliteConnection connection, ComponentRecord component,
        string sourceId, string destinationId, int quantity, string reason, string note)
    {
        if (quantity <= 0 || sourceId == destinationId) return OperationResult.Failure("调拨数量必须为正数，且目标库位不能与来源相同。");
        var sourceQuantity = GetAllocationQuantity(connection, component.Id, sourceId);
        if (sourceQuantity < quantity) return OperationResult.Failure("来源库位库存不足。");
        var happenedAt = UtcNow();
        SetAllocationQuantity(connection, component.Id, sourceId, sourceQuantity - quantity);
        SetAllocationQuantity(connection, component.Id, destinationId, checked(GetAllocationQuantity(connection, component.Id, destinationId) + quantity));
        var movementId = $"mov-{Guid.NewGuid():N}";
        using var movement = connection.CreateCommand();
        movement.CommandText = "INSERT INTO stock_movements(id,component_id,movement_type,quantity,reason,note,happened_at,updated_at,deleted,location_id,destination_location_id) " +
            "VALUES($id,$component_id,'transfer',$quantity,$reason,$note,$at,$at,0,$source,$destination)";
        movement.Parameters.AddWithValue("$id", movementId);
        movement.Parameters.AddWithValue("$component_id", component.Id);
        movement.Parameters.AddWithValue("$quantity", quantity);
        movement.Parameters.AddWithValue("$reason", reason.Trim());
        movement.Parameters.AddWithValue("$note", note.Trim());
        movement.Parameters.AddWithValue("$at", happenedAt);
        movement.Parameters.AddWithValue("$source", sourceId);
        movement.Parameters.AddWithValue("$destination", destinationId);
        movement.ExecuteNonQuery();
        using var update = connection.CreateCommand();
        update.CommandText = "UPDATE components SET location=$location,updated_at=$at WHERE id=$id";
        update.Parameters.AddWithValue("$location", destinationId);
        update.Parameters.AddWithValue("$at", happenedAt);
        update.Parameters.AddWithValue("$id", component.Id);
        update.ExecuteNonQuery();
        EnqueueEntity(connection, "component", component.Id, happenedAt);
        EnqueueEntity(connection, "stock_movement", movementId, happenedAt);
        return OperationResult.Success("库存已在库位间调拨；总库存未变化。");
    }

    private static void ValidateMovementDraft(MovementEntryDraft draft)
    {
        if (string.IsNullOrWhiteSpace(draft.ComponentId)
            || string.IsNullOrWhiteSpace(draft.MovementType)
            || string.IsNullOrWhiteSpace(draft.Reason))
        {
            throw new InvalidOperationException("请选择元器件、变动类型并填写原因。");
        }

        if (draft.MovementType.Equals("adjustment", StringComparison.OrdinalIgnoreCase))
        {
            if (draft.Quantity == 0)
            {
                throw new InvalidOperationException("调整数量不能为 0。");
            }
        }
        else if (draft.Quantity <= 0)
        {
            throw new InvalidOperationException("变动数量必须大于 0。");
        }
    }

    private static int CalculateQuantityDelta(string movementType, int quantity) =>
        movementType.Trim().ToLowerInvariant() switch
        {
            "inbound" => quantity,
            "outbound" => -quantity,
            "adjustment" => quantity,
            _ => throw new InvalidOperationException("不支持的变动类型。"),
        };

    private static int NormalizeMovementQuantity(string movementType, int quantity) =>
        movementType.Trim().ToLowerInvariant() switch
        {
            "adjustment" => quantity,
            _ => Math.Abs(quantity),
        };
}
