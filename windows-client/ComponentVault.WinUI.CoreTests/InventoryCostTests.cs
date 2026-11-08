using ComponentVault.WinUI.Services;
using Xunit;
namespace ComponentVault.WinUI.CoreTests;
public sealed class InventoryCostTests
{
    [Fact] public void ActualInboundOutboundAndValuationPersist()
    {
        var root=Path.Combine(Path.GetTempPath(),"cost-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try
        {
            var store=new InventoryStore(Path.Combine(root,"db.sqlite"));store.Initialize();
            var component=store.SaveComponent(new ComponentVault.WinUI.Models.ComponentDraft { Sku="C1",Name="part",Category="cat",PackageName="0603",Location="A",Quantity=10,MinStock=0,Description="",PurchaseAmount="10" });
            Assert.Equal("1",Assert.Single(store.GetComponents()).InventoryUnitCost);
            Assert.Equal("10",Assert.Single(store.GetMovements()).PurchaseAmount);
            Assert.Equal(10,Assert.Single(store.GetComponents()).Quantity);
            Assert.Throws<InvalidOperationException>(()=>store.SaveComponent(new ComponentVault.WinUI.Models.ComponentDraft { Sku="C2",Name="zero",Category="cat",PackageName="0603",Location="A",Quantity=0,MinStock=0,Description="",PurchaseAmount="0" }));
            Assert.True(store.RecordMovement(new ComponentVault.WinUI.Models.MovementEntryDraft { ComponentId=component.Id,MovementType="inbound",Quantity=10,Reason="purchase",LocationId="A",PurchaseAmount="20" }).IsSuccess);
            Assert.Equal("1.5",Assert.Single(store.GetComponents()).InventoryUnitCost);
            Assert.Equal(2,store.GetMovements().Count);
            Assert.Contains(store.GetMovements(),m=>m.PurchaseAmount=="20");
            var backup=new InventoryWorkbookBackup(store);var path=Path.Combine(root,"backup.xlsx");backup.Export(path);Assert.True(backup.Preview(path).CanImport);
            var current=Assert.Single(store.GetComponents());Assert.True(store.SetInventoryCost(current.Id,current.UpdatedAt,"10").IsSuccess);
            Assert.Equal("0.5",Assert.Single(store.GetComponents()).InventoryUnitCost);
            Assert.Contains(store.GetMovements(),m=>m.Quantity==0 && m.CostAmount=="-20");
            Assert.True(store.RecordMovement(new ComponentVault.WinUI.Models.MovementEntryDraft { ComponentId=component.Id,MovementType="outbound",Quantity=20,Reason="consume",LocationId="A" }).IsSuccess);
            Assert.Null(Assert.Single(store.GetComponents()).InventoryUnitCost);
        }
        finally { Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();Directory.Delete(root,true); }
    }
    [Fact] public void WeightedAverageUsesAmountsAndPreservesUnknown()
    {
        Assert.Equal("1.5", InventoryCost.Inbound(10,"1",10,"20"));
        Assert.Null(InventoryCost.Inbound(10,null,10,"20"));
        Assert.Null(InventoryCost.Inbound(10,"1",10,null));
        Assert.Equal("0",InventoryCost.Inbound(0,null,10,"0"));
        Assert.Equal("0.3333333333333333",InventoryCost.Average("1",3));
        Assert.Equal("999999999999999999999999999999999999",InventoryCost.Multiply("999999999999999999999999999999999999",1));
        Assert.Equal("-10",InventoryCost.Difference("5","15"));
        Assert.Throws<InvalidOperationException>(()=>InventoryCost.Input("0.000000001"));
    }
}
