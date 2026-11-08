using System.Globalization;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services.BatchInbound;
using ComponentVault.WinUI.Services.Catalog;
using Microsoft.Data.Sqlite;
using static ComponentVault.WinUI.Services.InventoryWrites.InventoryWriteSql;

namespace ComponentVault.WinUI.Services.InventoryWrites;

internal sealed class InventoryBatchWriter
{
    private readonly InventoryDatabase _database;

    internal InventoryBatchWriter(InventoryDatabase database) => _database = database;

    public bool IsBatchReceiptCommitted(string receiptId)
    {
        using var connection = _database.OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT EXISTS(SELECT 1 FROM batch_inbound_receipts WHERE receipt_id=$id)";
        command.Parameters.AddWithValue("$id", receiptId);
        return Convert.ToInt64(command.ExecuteScalar(), CultureInfo.InvariantCulture) != 0;
    }

    public static IReadOnlyList<BatchInboundSummary> SummarizeBatchInbound(IEnumerable<BatchInboundLine> lines) => lines
        .GroupBy(line => (line.Sku.ToUpperInvariant(), line.LocationId))
        .Select(group => new BatchInboundSummary(
            group.Key.Item1, group.Key.LocationId, checked(group.Sum(line => line.Quantity)), group.Count()))
        .OrderBy(summary => summary.Sku, StringComparer.Ordinal)
        .ThenBy(summary => summary.LocationId, StringComparer.Ordinal)
        .ToArray();

    public OperationResult CommitBatchInbound(BatchInboundCommitRequest request)
    {
        if (request.Lines.Count is 0 or > 500)
            return OperationResult.Failure("批量入库必须包含 1..500 个包装。");
        if (request.Lines.Select(line => line.ReceiptId).Distinct(StringComparer.Ordinal).Count() != request.Lines.Count)
            return OperationResult.Failure("批次 receipt ID 重复。");
        if (request.Lines.Any(line => !Guid.TryParse(line.ReceiptId, out _)
            || line.Quantity <= 0
            || LcscPublicCatalog.NormalizeSku(line.Sku) is null
            || string.IsNullOrWhiteSpace(line.LocationId)))
            return OperationResult.Failure("批次包含无效 receipt、C 编号、数量或库位。");
        try
        {
            using var connection = _database.OpenConnection();
            using var transaction = connection.BeginTransaction();
            var committed = 0;
            var skipped = 0;
            foreach (var line in request.Lines)
            {
                using (var receipt = connection.CreateCommand())
                {
                    receipt.CommandText = "SELECT EXISTS(SELECT 1 FROM batch_inbound_receipts WHERE receipt_id=$id)";
                    receipt.Parameters.AddWithValue("$id", line.ReceiptId);
                    if (Convert.ToInt64(receipt.ExecuteScalar(), CultureInfo.InvariantCulture) != 0)
                    {
                        skipped++;
                        continue;
                    }
                }
                using (var location = connection.CreateCommand())
                {
                    location.CommandText = "SELECT EXISTS(SELECT 1 FROM storage_locations WHERE id=$id AND deleted=0)";
                    location.Parameters.AddWithValue("$id", line.LocationId);
                    if (Convert.ToInt64(location.ExecuteScalar(), CultureInfo.InvariantCulture) == 0)
                        throw new InvalidOperationException($"库位 {line.LocationId} 不存在或已删除。");
                }
                string? componentId = null;
                var total = 0;
                using (var find = connection.CreateCommand())
                {
                    find.CommandText = "SELECT id,quantity FROM components WHERE UPPER(sku)=UPPER($sku) AND deleted=0";
                    find.Parameters.AddWithValue("$sku", line.Sku);
                    using var reader = find.ExecuteReader();
                    if (reader.Read())
                    {
                        componentId = reader.GetString(0);
                        total = reader.GetInt32(1);
                        if (reader.Read())
                            throw new InvalidOperationException($"SKU {line.Sku} 存在多个活动记录，无法安全入库。");
                    }
                }
                var at = UtcNow();
                if (componentId is null)
                {
                    if (string.IsNullOrWhiteSpace(line.Name)
                        || string.IsNullOrWhiteSpace(line.Category)
                        || string.IsNullOrWhiteSpace(line.PackageName))
                        throw new InvalidOperationException($"新 SKU {line.Sku} 的名称、分类和封装必须完整。");
                    componentId = "cmp-" + Guid.NewGuid().ToString("N");
                    using var insert = connection.CreateCommand();
                    insert.CommandText = """
                        INSERT INTO components(
                            id, sku, name, category, package_name, location, description,
                            quantity, min_stock, updated_at, deleted, base_updated_at
                        ) VALUES(
                            $id, $sku, $name, $category, $package, $location, $description,
                            0, 0, $at, 0, NULL
                        )
                        """;
                    insert.Parameters.AddWithValue("$id", componentId);
                    insert.Parameters.AddWithValue("$sku", line.Sku.ToUpperInvariant());
                    insert.Parameters.AddWithValue("$name", line.Name.Trim());
                    insert.Parameters.AddWithValue("$category", line.Category.Trim());
                    insert.Parameters.AddWithValue("$package", line.PackageName.Trim());
                    insert.Parameters.AddWithValue("$location", line.LocationId.Trim());
                    insert.Parameters.AddWithValue("$description", line.Description?.Trim() ?? "");
                    insert.Parameters.AddWithValue("$at", at);
                    insert.ExecuteNonQuery();
                    SetAllocationQuantity(connection, componentId, line.LocationId, 0);
                }
                var costBefore = GetCost(connection, componentId);
                var purchaseAmount = InventoryCost.Input(line.PurchaseAmount);
                var average = InventoryCost.Inbound(total, costBefore, line.Quantity, purchaseAmount);
                var newTotal = checked(total + line.Quantity);
                var allocation = checked(GetAllocationQuantity(connection, componentId, line.LocationId) + line.Quantity);
                SetAllocationQuantity(connection, componentId, line.LocationId, allocation);
                using (var update = connection.CreateCommand())
                {
                    update.CommandText = "UPDATE components SET quantity=$quantity,location=$location,updated_at=$at WHERE id=$id";
                    update.Parameters.AddWithValue("$quantity", newTotal);
                    update.Parameters.AddWithValue("$location", line.LocationId);
                    update.Parameters.AddWithValue("$at", at);
                    update.Parameters.AddWithValue("$id", componentId);
                    update.ExecuteNonQuery();
                }
                var movementId = "mov-" + Guid.NewGuid().ToString("N");
                using (var movement = connection.CreateCommand())
                {
                    movement.CommandText = """
                        INSERT INTO stock_movements(
                            id, component_id, movement_type, quantity, reason, note,
                            happened_at, updated_at, deleted, location_id, destination_location_id
                        ) VALUES($id, $component, 'inbound', $quantity, '批量嘉立创入库', '',
                            $at, $at, 0, $location, NULL)
                        """;
                    movement.Parameters.AddWithValue("$id", movementId);
                    movement.Parameters.AddWithValue("$component", componentId);
                    movement.Parameters.AddWithValue("$quantity", line.Quantity);
                    movement.Parameters.AddWithValue("$at", at);
                    movement.Parameters.AddWithValue("$location", line.LocationId);
                    movement.ExecuteNonQuery();
                }
                SetCost(connection, componentId, average, movementId, purchaseAmount, purchaseAmount);
                using (var receipt = connection.CreateCommand())
                {
                    receipt.CommandText = "INSERT INTO batch_inbound_receipts(receipt_id,committed_at) VALUES($id,$at)";
                    receipt.Parameters.AddWithValue("$id", line.ReceiptId);
                    receipt.Parameters.AddWithValue("$at", at);
                    receipt.ExecuteNonQuery();
                }
            EnqueueEntity(connection, "component", componentId, at);
                EnqueueEntity(connection, "stock_movement", movementId, at);
                committed++;
            }
            foreach (var sku in request.Lines.Select(line => line.Sku).Distinct(StringComparer.OrdinalIgnoreCase))
            {
                using var verify = connection.CreateCommand();
                verify.CommandText = """
                    SELECT c.id, c.quantity, COALESCE(SUM(a.quantity), 0)
                    FROM components c
                    LEFT JOIN component_allocations a ON a.component_id = c.id
                    WHERE UPPER(c.sku) = UPPER($sku) AND c.deleted = 0
                    GROUP BY c.id, c.quantity
                    """;
                verify.Parameters.AddWithValue("$sku", sku);
                using var reader = verify.ExecuteReader();
                while (reader.Read())
                    if (reader.GetInt64(1) != reader.GetInt64(2))
                        throw new InvalidOperationException($"SKU {sku} 的库存总量与库位分配不一致，已回滚。");
            }
            transaction.Commit();
            return OperationResult.Success($"批量入库完成：新增处理 {committed} 个包装，已处理并跳过 {skipped} 个。");
        }
        catch (Exception exception) when (exception is OverflowException or InvalidOperationException or SqliteException)
        {
            return OperationResult.Failure(exception.Message);
        }
    }

    public OperationResult AppendCatalogInbound(
        string sku,
        int quantity,
        string locationId,
        string expectedUpdatedAt,
        string? newLocationName = null, string? purchaseAmount = null)
    {
        if (LcscPublicCatalog.NormalizeSku(sku) is null
            || quantity <= 0 || string.IsNullOrWhiteSpace(locationId))
            return OperationResult.Failure("C 编号、入库数量或库位无效。");

        try
        {
            using var connection = _database.OpenConnection();
            using var transaction = connection.BeginTransaction();
            using var find = connection.CreateCommand();
            find.CommandText =
                "SELECT id,quantity,updated_at FROM components WHERE UPPER(sku)=UPPER($sku) AND deleted=0";
            find.Parameters.AddWithValue("$sku", sku);
            using var reader = find.ExecuteReader();
            if (!reader.Read())
                return OperationResult.Failure("目标 SKU 已不存在，请重新导入。");

            var componentId = reader.GetString(0);
            var currentQuantity = reader.GetInt32(1);
            var updatedAt = reader.GetString(2);
            if (reader.Read())
                return OperationResult.Failure("存在多个活动 SKU，无法安全追加。");
            reader.Close();

            if (updatedAt != expectedUpdatedAt)
                return OperationResult.Failure("确认期间库存已变化，请重新核对数量。");

            if (newLocationName is not null)
            {
                var created = InventoryLocationWriter.InsertNewLocation(connection, locationId, newLocationName);
                if (!created.IsSuccess) return created;
            }
            using (var location = connection.CreateCommand())
            {
                location.CommandText =
                    "SELECT EXISTS(SELECT 1 FROM storage_locations WHERE id=$id AND deleted=0)";
                location.Parameters.AddWithValue("$id", locationId);
                if (Convert.ToInt64(location.ExecuteScalar(), CultureInfo.InvariantCulture) == 0)
                    return OperationResult.Failure("所选库位不存在或已删除。");
            }

            var oldCost = GetCost(connection, componentId);
            purchaseAmount = InventoryCost.Input(purchaseAmount);
            var average = InventoryCost.Inbound(currentQuantity, oldCost, quantity, purchaseAmount);
            var newTotal = checked(currentQuantity + quantity);
            var newAllocation = checked(GetAllocationQuantity(connection, componentId, locationId) + quantity);
            var at = UtcNow();
            SetAllocationQuantity(connection, componentId, locationId, newAllocation);
            using (var update = connection.CreateCommand())
            {
                update.CommandText =
                    "UPDATE components SET quantity=$quantity,location=$location,updated_at=$at WHERE id=$id";
                update.Parameters.AddWithValue("$quantity", newTotal);
                update.Parameters.AddWithValue("$location", locationId);
                update.Parameters.AddWithValue("$at", at);
                update.Parameters.AddWithValue("$id", componentId);
                update.ExecuteNonQuery();
            }

            var movementId = "mov-" + Guid.NewGuid().ToString("N");
            using (var movement = connection.CreateCommand())
            {
                movement.CommandText =
                    "INSERT INTO stock_movements(id,component_id,movement_type,quantity,reason,note,happened_at,updated_at,deleted,location_id,destination_location_id) " +
                    "VALUES($mid,$id,'inbound',$quantity,'嘉立创商城单条入库','',$at,$at,0,$location,NULL)";
                movement.Parameters.AddWithValue("$mid", movementId);
                movement.Parameters.AddWithValue("$id", componentId);
                movement.Parameters.AddWithValue("$quantity", quantity);
                movement.Parameters.AddWithValue("$at", at);
                movement.Parameters.AddWithValue("$location", locationId);
                movement.ExecuteNonQuery();
            }
            SetCost(connection, componentId, average, movementId, purchaseAmount, purchaseAmount);
            EnqueueEntity(connection, "component", componentId, at);
            EnqueueEntity(connection, "stock_movement", movementId, at);
            transaction.Commit();
            return OperationResult.Success($"已追加 {quantity}，当前库存 {newTotal}。");
        }
        catch (OverflowException)
        {
            return OperationResult.Failure("追加后库存超过整数范围。");
        }
        catch (SqliteException exception)
        {
            return OperationResult.Failure(exception.Message);
        }
    }
}
