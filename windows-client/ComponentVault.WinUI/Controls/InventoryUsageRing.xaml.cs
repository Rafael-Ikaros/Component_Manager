using ComponentVault.WinUI.Localization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace ComponentVault.WinUI.Controls;

public sealed partial class InventoryUsageRing : UserControl
{
    public static readonly DependencyProperty CurrentQuantityProperty = DependencyProperty.Register(
        nameof(CurrentQuantity), typeof(long), typeof(InventoryUsageRing), new PropertyMetadata(0L, OnValueChanged));
    public static readonly DependencyProperty OutboundQuantityProperty = DependencyProperty.Register(
        nameof(OutboundQuantity), typeof(long), typeof(InventoryUsageRing), new PropertyMetadata(0L, OnValueChanged));

    public InventoryUsageRing() { InitializeComponent(); UpdateVisual(); }

    public long CurrentQuantity { get => (long)GetValue(CurrentQuantityProperty); set => SetValue(CurrentQuantityProperty, value); }
    public long OutboundQuantity { get => (long)GetValue(OutboundQuantityProperty); set => SetValue(OutboundQuantityProperty, value); }

    private static void OnValueChanged(DependencyObject sender, DependencyPropertyChangedEventArgs args) => ((InventoryUsageRing)sender).UpdateVisual();

    private void UpdateVisual()
    {
        if (UsedArc is null) return;
        var total = checked(Math.Max(0, CurrentQuantity) + Math.Max(0, OutboundQuantity));
        var ratio = total == 0 ? 0 : Math.Clamp((double)Math.Max(0, OutboundQuantity) / total, 0, 1);
        PercentageText.Text = $"{ratio:P0}";
        SummaryText.Text = AppStrings.Format("InventoryUsageRing_TotalIssued", Compact(OutboundQuantity));
        FullUsedRing.Visibility = ratio >= 0.999999 ? Visibility.Visible : Visibility.Collapsed;
        UsedArc.Visibility = ratio > 0 && ratio < 0.999999 ? Visibility.Visible : Visibility.Collapsed;
        if (UsedArc.Visibility == Visibility.Visible)
        {
            const double center = 24;
            const double radius = 20;
            var angle = ratio * Math.PI * 2 - Math.PI / 2;
            var end = new Windows.Foundation.Point(center + radius * Math.Cos(angle), center + radius * Math.Sin(angle));
            UsedArc.Data = new PathGeometry
            {
                Figures =
                {
                    new PathFigure
                    {
                        StartPoint = new Windows.Foundation.Point(center, center - radius),
                        Segments = { new ArcSegment { Point = end, Size = new Windows.Foundation.Size(radius, radius), SweepDirection = SweepDirection.Clockwise, IsLargeArc = ratio > 0.5 } },
                    },
                },
            };
        }
        var label = AppStrings.Format("InventoryUsageRing_Summary", CurrentQuantity, OutboundQuantity);
        ToolTipService.SetToolTip(this, label);
        AutomationProperties.SetName(this, label);
    }

    private static string Compact(long value)
    {
        var absolute = Math.Abs((double)value);
        return absolute switch
        {
            >= 100_000_000 => $"{value / 100_000_000d:0.#}亿",
            >= 10_000 => $"{value / 10_000d:0.#}万",
            _ => value.ToString(),
        };
    }
}
