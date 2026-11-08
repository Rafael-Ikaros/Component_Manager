using ComponentVault.WinUI.Models;

namespace ComponentVault.WinUI.Services;

public enum ComponentEditSource { Manual, Catalog }

public sealed record ComponentEditRequest(
    ComponentDraft Draft,
    ComponentEditSource Source,
    string? NewLocationName = null);

public sealed record ComponentEditPlan(ComponentEditRequest Request, ComponentRecord? AppendTarget);

public sealed class InventoryEditingService(InventoryStore store)
{
    public ComponentRecord? FindActiveSku(string sku) => store.FindActiveComponentBySku(sku);

    public ComponentEditPlan Prepare(ComponentEditRequest request)
    {
        var target = request.Draft.Id is null && request.Source == ComponentEditSource.Catalog
            && request.Draft.Quantity > 0
            ? FindActiveSku(request.Draft.Sku) : null;
        return new ComponentEditPlan(request, target);
    }

    public ComponentRecord? GetCatalogAppendTarget(ComponentEditRequest request) =>
        Prepare(request).AppendTarget;

    public OperationResult Commit(ComponentEditPlan plan)
    {
        if (plan.AppendTarget is not null)
            return Append(plan.Request, plan.AppendTarget.UpdatedAt);
        try
        {
            Save(plan.Request);
            return OperationResult.Success(
                plan.Request.Draft.Id is null ? "元器件已新增。" : "元器件已更新。");
        }
        catch (Exception exception)
        {
            return OperationResult.Failure(exception.Message);
        }
    }

    public ComponentRecord Save(ComponentEditRequest request) =>
        store.SaveComponent(request.Draft, request.NewLocationName);

    public OperationResult Append(ComponentEditRequest request, string expectedUpdatedAt) =>
        store.AppendCatalogInbound(request.Draft.Sku, request.Draft.Quantity,
            request.Draft.Location, expectedUpdatedAt, request.NewLocationName, request.Draft.PurchaseAmount);
}

public static class InventoryEditWriteCoordinator
{
    public static OperationResult AfterCommit(
        OperationResult committed,
        Action refresh,
        Action scheduleAutoSync)
    {
        if (!committed.IsSuccess) return committed;

        var warnings = new List<string>();
        try { refresh(); }
        catch (Exception exception) { warnings.Add($"刷新失败：{exception.Message}"); }

        try { scheduleAutoSync(); }
        catch (Exception exception) { warnings.Add($"自动同步未能安排：{exception.Message}"); }

        return warnings.Count == 0
            ? committed
            : OperationResult.Success(
                $"{committed.Message} 数据已保存。{string.Join("；", warnings)}。请重新进入库存页检查状态。");
    }
}
