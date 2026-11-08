using System.Globalization;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services.Bom;
using ComponentVault.WinUI.Services.Migration;
using Microsoft.Data.Sqlite;
using static ComponentVault.WinUI.Services.InventoryWrites.InventoryWriteSql;

namespace ComponentVault.WinUI.Services.InventoryWrites;

internal sealed class InventoryBomImportWriter
{
    private readonly InventoryDatabase _database;

    internal InventoryBomImportWriter(InventoryDatabase database) => _database = database;

    public BomConfirmResult ConfirmBomConsumption(BomConfirmRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.ReleaseId) || string.IsNullOrWhiteSpace(request.BatchId))
        {
            return BomConfirmResult.Failure("发行 ID 与批次 ID 不能为空。");
        }
        if (string.IsNullOrWhiteSpace(request.ProjectName) || request.BatchQuantity <= 0 || request.Lines.Count == 0)
        {
            return BomConfirmResult.Failure("BOM 确认参数无效。");
        }
        if (request.Lines.Select(line => line.ComponentId).Distinct(StringComparer.Ordinal).Count() != request.Lines.Count)
        {
            return BomConfirmResult.Failure("BOM 确认行包含重复库存项。");
        }

        using var connection = _database.OpenConnection();
        using var transaction = connection.BeginTransaction();
        if (BomReleaseExists(connection, request.ReleaseId))
        {
            transaction.Rollback();
            return BomConfirmResult.Duplicate("该发行已经扣减，无需重复处理。");
        }
        if (BomBatchExists(connection, request.BatchId))
        {
            transaction.Rollback();
            return BomConfirmResult.Failure("批次 ID 已被其他发行使用。");
        }

        var current = new Dictionary<string, ComponentRecord>(StringComparer.Ordinal);
        foreach (var line in request.Lines)
        {
            var component = InventoryQueries.GetComponentById(connection, line.ComponentId);
            if (component is null || component.Deleted)
            {
                transaction.Rollback();
                return BomConfirmResult.Failure($"{line.ComponentSku} 已不存在或已删除，请重新预览。");
            }
            if (!InventoryStore.TryCompareInstants(component.UpdatedAt, line.ExpectedUpdatedAt, out var comparison) || comparison != 0)
            {
                transaction.Rollback();
                return BomConfirmResult.Failure($"{line.ComponentSku} 在预览后发生变化，请重新预览。");
            }
            if (line.RequiredQuantity <= 0 || component.Quantity < line.RequiredQuantity)
            {
                transaction.Rollback();
                return BomConfirmResult.Failure($"{line.ComponentSku} 库存不足，请重新预览。");
            }
            current[line.ComponentId] = component;
        }

        var happenedAt = UtcNow();
        foreach (var line in request.Lines)
        {
            using var update = connection.CreateCommand();
            update.CommandText = "UPDATE components SET quantity = $quantity, updated_at = $updated_at WHERE id = $id";
            update.Parameters.AddWithValue("$quantity", current[line.ComponentId].Quantity - line.RequiredQuantity);
            update.Parameters.AddWithValue("$updated_at", happenedAt);
            update.Parameters.AddWithValue("$id", line.ComponentId);
            update.ExecuteNonQuery();
            foreach (var deduction in DeductAllocations(connection, line.ComponentId, line.RequiredQuantity))
            {
                var movementId = $"mov-{Guid.NewGuid():N}";
                using var movement = connection.CreateCommand();
                movement.CommandText =
                    "INSERT INTO stock_movements(id,component_id,movement_type,quantity,reason,note,happened_at,updated_at,deleted,location_id,destination_location_id) " +
                    "VALUES($id,$component_id,'outbound',$quantity,'BOM production',$note,$at,$at,0,$location_id,NULL)";
                movement.Parameters.AddWithValue("$id", movementId);
                movement.Parameters.AddWithValue("$component_id", line.ComponentId);
                movement.Parameters.AddWithValue("$quantity", deduction.Quantity);
                movement.Parameters.AddWithValue("$note", $"项目：{request.ProjectName}；批次：{request.BatchId}");
                movement.Parameters.AddWithValue("$at", happenedAt);
                movement.Parameters.AddWithValue("$location_id", deduction.LocationId);
                movement.ExecuteNonQuery();
                var sourceCost = current[line.ComponentId].InventoryUnitCost;
                SetCost(connection, line.ComponentId, current[line.ComponentId].Quantity == line.RequiredQuantity ? null : sourceCost, movementId, null, InventoryCost.Multiply(sourceCost, deduction.Quantity));
                EnqueueEntity(connection, "stock_movement", movementId, happenedAt);
            }
            EnqueueEntity(connection, "component", line.ComponentId, happenedAt);
        }

        using (var batch = connection.CreateCommand())
        {
            batch.CommandText =
                """
                INSERT INTO bom_consumption_batches (
                    batch_id, release_id, project_name, batch_quantity, source_fingerprint, created_at
                ) VALUES ($batch_id, $release_id, $project_name, $batch_quantity, $source_fingerprint, $created_at)
                """;
            batch.Parameters.AddWithValue("$batch_id", request.BatchId.Trim());
            batch.Parameters.AddWithValue("$release_id", request.ReleaseId.Trim());
            batch.Parameters.AddWithValue("$project_name", request.ProjectName.Trim());
            batch.Parameters.AddWithValue("$batch_quantity", request.BatchQuantity);
            batch.Parameters.AddWithValue("$source_fingerprint", request.SourceFingerprint.Trim());
            batch.Parameters.AddWithValue("$created_at", happenedAt);
            batch.ExecuteNonQuery();
        }
        transaction.Commit();
        return BomConfirmResult.Success("BOM 批次已原子扣减并写入出库记录。");
    }

    public OperationResult ImportComponentHub(ComponentHubImportRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.Fingerprint) || request.Items.Count == 0)
            return OperationResult.Failure("迁移预览为空或缺少文件指纹。");
        using var connection = _database.OpenConnection();
        using var transaction = connection.BeginTransaction();
        using (var duplicateFile = connection.CreateCommand())
        {
            duplicateFile.CommandText = "SELECT COUNT(*) FROM component_hub_imports WHERE fingerprint = $fingerprint";
            duplicateFile.Parameters.AddWithValue("$fingerprint", request.Fingerprint);
            if (Convert.ToInt32(duplicateFile.ExecuteScalar(), CultureInfo.InvariantCulture) > 0)
            {
                transaction.Rollback();
                return OperationResult.Failure("该 component-hub 文件已导入，未重复写入。");
            }
        }

        var imported = 0;
        foreach (var item in request.Items)
        {
            if (item.Quantity < 0 || item.MinStock < 0 || string.IsNullOrWhiteSpace(item.Sku))
            {
                transaction.Rollback();
                return OperationResult.Failure($"第 {item.Index} 条迁移数据无效。");
            }
            using var exists = connection.CreateCommand();
            exists.CommandText = "SELECT COUNT(*) FROM components WHERE sku = $sku AND deleted = 0";
            exists.Parameters.AddWithValue("$sku", item.Sku);
            if (Convert.ToInt32(exists.ExecuteScalar(), CultureInfo.InvariantCulture) > 0)
            {
                if (request.DuplicatePolicy == DuplicateSkuPolicy.Skip) continue;
                transaction.Rollback();
                return OperationResult.Failure($"SKU {item.Sku} 已存在，迁移已全部回滚。");
            }

            var componentId = $"cmp-{Guid.NewGuid():N}";
            var updatedAt = UtcNow();
            using var component = connection.CreateCommand();
            component.CommandText =
                """
                INSERT INTO components (
                    id, sku, name, category, package_name, location, description,
                    quantity, min_stock, updated_at, deleted
                ) VALUES (
                    $id, $sku, $name, $category, $package_name, $location, $description,
                    $quantity, $min_stock, $updated_at, 0
                )
                """;
            component.Parameters.AddWithValue("$id", componentId);
            component.Parameters.AddWithValue("$sku", item.Sku);
            component.Parameters.AddWithValue("$name", item.Name);
            component.Parameters.AddWithValue("$category", item.Category);
            component.Parameters.AddWithValue("$package_name", item.PackageName);
            component.Parameters.AddWithValue("$location", item.Location);
            component.Parameters.AddWithValue("$description", item.Description);
            component.Parameters.AddWithValue("$quantity", item.Quantity);
            component.Parameters.AddWithValue("$min_stock", item.MinStock);
            component.Parameters.AddWithValue("$updated_at", updatedAt);
            component.ExecuteNonQuery();
            var locationId = NormalizeLocationId(item.Location);
            UpsertLocalLocation(connection, locationId, locationId, updatedAt);
            SetAllocationQuantity(connection, componentId, locationId, item.Quantity);
            EnqueueEntity(connection, "component", componentId, updatedAt);

            if (item.Quantity > 0)
            {
                var movementId = $"mov-{Guid.NewGuid():N}";
                using var movement = connection.CreateCommand();
                movement.CommandText =
                    """
                    INSERT INTO stock_movements (
                        id, component_id, movement_type, quantity, reason, note,
                        happened_at, updated_at, deleted
                        , location_id, destination_location_id
                    ) VALUES (
                        $id, $component_id, 'inbound', $quantity, 'component-hub migration',
                        'component-hub JSON 导入初始库存', $updated_at, $updated_at, 0
                        , $location_id, NULL
                    )
                    """;
                movement.Parameters.AddWithValue("$id", movementId);
                movement.Parameters.AddWithValue("$component_id", componentId);
                movement.Parameters.AddWithValue("$quantity", item.Quantity);
                movement.Parameters.AddWithValue("$updated_at", updatedAt);
                movement.Parameters.AddWithValue("$location_id", locationId);
                movement.ExecuteNonQuery();
                EnqueueEntity(connection, "stock_movement", movementId, updatedAt);
            }
            imported++;
        }

        using var marker = connection.CreateCommand();
        marker.CommandText = "INSERT INTO component_hub_imports (fingerprint, imported_count, created_at) VALUES ($fingerprint, $count, $created_at)";
        marker.Parameters.AddWithValue("$fingerprint", request.Fingerprint);
        marker.Parameters.AddWithValue("$count", imported);
        marker.Parameters.AddWithValue("$created_at", UtcNow());
        marker.ExecuteNonQuery();
        transaction.Commit();
        return OperationResult.Success($"component-hub 迁移完成，新增 {imported} 个元器件。");
    }

    private static IReadOnlyList<(string LocationId, int Quantity)> DeductAllocations(SqliteConnection connection, string componentId, int required)
    {
        var allocations = InventoryQueries.GetAllocations(connection, componentId).Where(item => item.Quantity > 0).OrderBy(item => item.LocationId, StringComparer.Ordinal).ToArray();
        var remaining = required;
        var deductions = new List<(string, int)>();
        foreach (var allocation in allocations)
        {
            if (remaining == 0) break;
            var take = Math.Min(remaining, allocation.Quantity);
            SetAllocationQuantity(connection, componentId, allocation.LocationId, allocation.Quantity - take);
            deductions.Add((allocation.LocationId, take));
            remaining -= take;
        }
        if (remaining != 0) throw new InvalidOperationException("分配库存合计不足，请重新预览。");
        return deductions;
    }

    private static bool BomReleaseExists(SqliteConnection connection, string releaseId)
    {
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT COUNT(*) FROM bom_consumption_batches WHERE release_id = $release_id";
        command.Parameters.AddWithValue("$release_id", releaseId.Trim());
        return Convert.ToInt32(command.ExecuteScalar(), CultureInfo.InvariantCulture) > 0;
    }

    private static bool BomBatchExists(SqliteConnection connection, string batchId)
    {
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT COUNT(*) FROM bom_consumption_batches WHERE batch_id = $batch_id";
        command.Parameters.AddWithValue("$batch_id", batchId.Trim());
        return Convert.ToInt32(command.ExecuteScalar(), CultureInfo.InvariantCulture) > 0;
    }
}
