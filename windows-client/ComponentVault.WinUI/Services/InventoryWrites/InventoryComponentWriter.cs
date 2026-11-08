using System.Globalization;
using ComponentVault.WinUI.Models;
using Microsoft.Data.Sqlite;
using static ComponentVault.WinUI.Services.InventoryWrites.InventoryWriteSql;

namespace ComponentVault.WinUI.Services.InventoryWrites;

internal sealed class InventoryComponentWriter
{
    private readonly InventoryDatabase _database;

    internal InventoryComponentWriter(InventoryDatabase database) => _database = database;

    public ComponentRecord SaveComponent(ComponentDraft draft, string? newLocationName = null)
    {
        ValidateComponentDraft(draft);

        using var connection = _database.OpenConnection();
        using var transaction = connection.BeginTransaction();

        EnsureUniqueActiveSku(connection, draft.Sku.Trim(), draft.Id);
        if (newLocationName is not null)
        {
            var created = InventoryLocationWriter.InsertNewLocation(connection, draft.Location, newLocationName);
            if (!created.IsSuccess) throw new InvalidOperationException(created.Message);
        }

        var componentId = draft.Id ?? $"cmp-{Guid.NewGuid():N}";
        var existingComponent = draft.Id is null ? null : InventoryQueries.GetComponentById(connection, componentId);
        var initialPurchase = existingComponent is null ? InventoryCost.Input(draft.PurchaseAmount) : null;
        if (initialPurchase is not null && draft.Quantity == 0)
            throw new InvalidOperationException("填写采购金额时，初始库存数量必须大于 0。");
        var locationChanged = existingComponent is not null
            && !string.Equals(existingComponent.Location, draft.Location.Trim(), StringComparison.Ordinal);
        var updatedAt = UtcNow();

        using var command = connection.CreateCommand();
        command.CommandText =
            """
            INSERT INTO components (
                id,
                sku,
                name,
                category,
                package_name,
                location,
                description,
                quantity,
                min_stock,
                updated_at,
                deleted,
                base_updated_at
            ) VALUES (
                $id,
                $sku,
                $name,
                $category,
                $package_name,
                $location,
                $description,
                $quantity,
                $min_stock,
                $updated_at,
                0,
                NULL
            )
            ON CONFLICT(id) DO UPDATE SET
                sku = excluded.sku,
                name = excluded.name,
                category = excluded.category,
                package_name = excluded.package_name,
                location = excluded.location,
                description = excluded.description,
                quantity = excluded.quantity,
                min_stock = excluded.min_stock,
                updated_at = excluded.updated_at,
                deleted = 0
            """;
        command.Parameters.AddWithValue("$id", componentId);
        command.Parameters.AddWithValue("$sku", draft.Sku.Trim());
        command.Parameters.AddWithValue("$name", draft.Name.Trim());
        command.Parameters.AddWithValue("$category", draft.Category.Trim());
        command.Parameters.AddWithValue("$package_name", draft.PackageName.Trim());
        command.Parameters.AddWithValue("$location", draft.Location.Trim());
        command.Parameters.AddWithValue("$description", draft.Description.Trim());
        command.Parameters.AddWithValue("$quantity", draft.Quantity);
        command.Parameters.AddWithValue("$min_stock", draft.MinStock);
        command.Parameters.AddWithValue("$updated_at", updatedAt);
        command.ExecuteNonQuery();

        var locationId = NormalizeLocationId(draft.Location);
        if (existingComponent is null || locationChanged)
        {
            UpsertLocalLocation(connection, locationId, locationId, updatedAt);
            SetAllocationQuantity(connection, componentId, locationId, draft.Quantity);
            using var clearOtherAllocations = connection.CreateCommand();
            clearOtherAllocations.CommandText = "UPDATE component_allocations SET quantity=0 WHERE component_id=$component_id AND location_id<>$location_id";
            clearOtherAllocations.Parameters.AddWithValue("$component_id", componentId);
            clearOtherAllocations.Parameters.AddWithValue("$location_id", locationId);
            clearOtherAllocations.ExecuteNonQuery();
        }
        else if (existingComponent.Quantity != draft.Quantity)
        {
            var adjustedQuantity = (long)GetAllocationQuantity(connection, componentId, locationId) + draft.Quantity - existingComponent.Quantity;
            if (adjustedQuantity < 0 || adjustedQuantity > int.MaxValue) throw new InvalidOperationException("The edited total would make the selected location invalid.");
            SetAllocationQuantity(connection, componentId, locationId, (int)adjustedQuantity);
        }

        var average = draft.Quantity == 0 ? null : existingComponent is not null ? existingComponent.InventoryUnitCost : InventoryCost.Average(initialPurchase, draft.Quantity);
        if (initialPurchase is not null)
        {
            var movementId = "mov-" + Guid.NewGuid().ToString("N");
            using var movement = connection.CreateCommand();
            movement.CommandText = "INSERT INTO stock_movements(id,component_id,movement_type,quantity,reason,note,happened_at,updated_at,deleted,location_id) VALUES($mid,$id,'inbound',$quantity,'初始采购入库','新建库存的实际采购金额',$at,$at,0,$location)";
            movement.Parameters.AddWithValue("$mid", movementId);
            movement.Parameters.AddWithValue("$id", componentId);
            movement.Parameters.AddWithValue("$quantity", draft.Quantity);
            movement.Parameters.AddWithValue("$at", updatedAt);
            movement.Parameters.AddWithValue("$location", locationId);
            movement.ExecuteNonQuery();
            SetCost(connection, componentId, average, movementId, initialPurchase, initialPurchase);
            EnqueueEntity(connection, "stock_movement", movementId, updatedAt);
        }
        else SetCost(connection, componentId, average);
        EnqueueEntity(connection, "component", componentId, updatedAt);

        var savedComponent = InventoryQueries.GetComponentById(connection, componentId)
            ?? throw new InvalidOperationException("保存后未找到元器件。");
        transaction.Commit();
        return savedComponent;
    }

    public OperationResult SetInventoryCost(string componentId, string expectedUpdatedAt, string amount)
    {
        var total = InventoryCost.Input(amount) ?? throw new InvalidOperationException("请填写库存总成本。");
        using var connection = _database.OpenConnection();
        using var transaction = connection.BeginTransaction();
        var current = InventoryQueries.GetComponentById(connection, componentId);
        if (current is null || current.Deleted || current.Quantity == 0 || current.UpdatedAt != expectedUpdatedAt)
            return OperationResult.Failure("库存已变化或为空，请重新核对。");
        var at = UtcNow();
        var movementId = "mov-" + Guid.NewGuid().ToString("N");
        var oldTotal = InventoryCost.Multiply(current.InventoryUnitCost, current.Quantity);
        using var command = connection.CreateCommand();
        command.CommandText = "INSERT INTO stock_movements(id,component_id,movement_type,quantity,reason,note,happened_at,updated_at,deleted,location_id) VALUES($mid,$id,'adjustment',0,'补录当前库存成本','当前估值调整，不回写历史采购',$at,$at,0,$location); UPDATE components SET updated_at=$at WHERE id=$id";
        command.Parameters.AddWithValue("$mid",movementId);command.Parameters.AddWithValue("$id",componentId);command.Parameters.AddWithValue("$at",at);command.Parameters.AddWithValue("$location",current.Location);command.ExecuteNonQuery();
        SetCost(connection, componentId, InventoryCost.Average(total,current.Quantity),movementId,null,oldTotal is null ? total : InventoryCost.Difference(total,oldTotal));
        EnqueueEntity(connection,"component",componentId,at);EnqueueEntity(connection,"stock_movement",movementId,at);
        transaction.Commit();return OperationResult.Success("当前库存成本已补录，历史采购未修改。");
    }

    public OperationResult SoftDeleteComponent(string componentId)
    {
        using var connection = _database.OpenConnection();
        using var transaction = connection.BeginTransaction();

        if (!InventoryQueries.ComponentExists(connection, componentId))
        {
            return OperationResult.Failure("请先选择一个要删除的元器件。");
        }

        var updatedAt = UtcNow();
        using var command = connection.CreateCommand();
        command.CommandText =
            """
            UPDATE components
            SET deleted = 1, updated_at = $updated_at
            WHERE id = $id
            """;
        command.Parameters.AddWithValue("$id", componentId);
        command.Parameters.AddWithValue("$updated_at", updatedAt);
        command.ExecuteNonQuery();

        EnqueueEntity(connection, "component", componentId, updatedAt);
        transaction.Commit();
        return OperationResult.Success("元器件已在本机软删除。");
    }

    private static void EnsureUniqueActiveSku(
        SqliteConnection connection,
        string sku,
        string? componentId
    )
    {
        using var command = connection.CreateCommand();
        command.CommandText =
            """
            SELECT COUNT(*)
            FROM components
            WHERE deleted = 0 AND UPPER(sku) = UPPER($sku) AND id != COALESCE($id, '')
            """;
        command.Parameters.AddWithValue("$sku", sku);
        command.Parameters.AddWithValue("$id", (object?)componentId ?? DBNull.Value);
        var duplicates = Convert.ToInt32(command.ExecuteScalar(), CultureInfo.InvariantCulture);
        if (duplicates > 0)
        {
            throw new InvalidOperationException("已存在相同 SKU 的有效元器件。");
        }
    }

    private static void ValidateComponentDraft(ComponentDraft draft)
    {
        if (string.IsNullOrWhiteSpace(draft.Sku)
            || string.IsNullOrWhiteSpace(draft.Name)
            || string.IsNullOrWhiteSpace(draft.Category)
            || string.IsNullOrWhiteSpace(draft.PackageName)
            || string.IsNullOrWhiteSpace(draft.Location))
        {
            throw new InvalidOperationException("请填写 SKU、名称、分类、封装和库位。");
        }

        if (draft.Quantity < 0 || draft.MinStock < 0)
        {
            throw new InvalidOperationException("数量和最低库存不能为负数。");
        }
    }
}
