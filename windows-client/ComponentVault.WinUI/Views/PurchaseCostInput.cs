using ComponentVault.WinUI.Services;
using Microsoft.UI.Xaml.Controls;

namespace ComponentVault.WinUI.Views;

/// <summary>Purchase amount entry shared by the existing native inventory dialogs.</summary>
public sealed class PurchaseCostInput : StackPanel
{
    private readonly ComboBox _mode = new() { ItemsSource = new[] { "采购总金额", "购买单价" }, SelectedIndex = 0 };
    private readonly TextBox _amount = new() { PlaceholderText = "留空未知；0为赠品，最多8位小数" };
    private readonly TextBlock _preview = new() { TextWrapping = Microsoft.UI.Xaml.TextWrapping.Wrap };
    private int _quantity;
    public PurchaseCostInput()
    {
        Spacing = 6;
        Children.Add(new TextBlock { Text = "CNY 商品成本，不含运费、PCB和加工" });
        Children.Add(_mode);Children.Add(_amount);Children.Add(_preview);
        _mode.SelectionChanged += (_, _) => UpdatePreview();
        _amount.TextChanged += (_, _) => UpdatePreview();
    }
    public int Quantity { set { _quantity=value;UpdatePreview(); } }
    public string? InitialAmount { set => _amount.Text=value ?? ""; }
    public string? GetPurchaseAmount()
    {
        var amount=InventoryCost.Input(_amount.Text);
        return _mode.SelectedIndex==1 ? InventoryCost.Multiply(amount,_quantity) : amount;
    }
    private void UpdatePreview()
    {
        try
        {
            var total=GetPurchaseAmount();
            _preview.Text=total is null ? "本次采购成本未知" : $"本次总额 ¥{total} · 单价 ¥{InventoryCost.Average(total,_quantity) ?? "—"} / 件";
        }
        catch(InvalidOperationException ex) { _preview.Text=ex.Message; }
    }
}
