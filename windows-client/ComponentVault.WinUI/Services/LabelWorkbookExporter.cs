using System.Globalization;
using System.Net;
using System.Text.Encodings.Web;
using System.Text.Json;
using ComponentVault.WinUI.Models;

namespace ComponentVault.WinUI.Services;

public enum LabelWorkbookColumn
{
    Name,
    Sku,
    Model,
    PackageName,
    Category,
    Location,
    Quantity,
    LongQrText,
    ShortQrText,
}

public sealed record LabelWorkbookRow(ComponentRecord Component, string Location, int Quantity);

public static class LabelWorkbookExporter
{
    public static IReadOnlySet<LabelWorkbookColumn> DefaultColumns { get; } = new HashSet<LabelWorkbookColumn>
    {
        LabelWorkbookColumn.Name,
        LabelWorkbookColumn.Sku,
        LabelWorkbookColumn.LongQrText,
        LabelWorkbookColumn.ShortQrText,
    };

    public static void Export(string path, IEnumerable<ComponentRecord> components, IReadOnlySet<LabelWorkbookColumn> selectedColumns)
        => ExportRows(path, CreateRows(components, [], null), selectedColumns);

    public static IReadOnlyList<LabelWorkbookRow> CreateRows(
        IEnumerable<ComponentRecord> components,
        IEnumerable<StorageLocationRecord> locations,
        IReadOnlySet<string>? selectedLocationIds)
    {
        var deletedLocations = locations.Where(location => location.Deleted)
            .Select(location => location.Id).ToHashSet(StringComparer.Ordinal);
        var rows = new List<LabelWorkbookRow>();
        foreach (var source in components.Where(component => !component.Deleted))
        {
            var component = Snapshot(source);
            if (selectedLocationIds is null)
            {
                rows.Add(new(component, component.Location, component.Quantity));
                continue;
            }

            if (component.Allocations.Count == 0)
            {
                if (!deletedLocations.Contains(component.Location) && selectedLocationIds.Contains(component.Location))
                    rows.Add(new(component, component.Location, component.Quantity));
                continue;
            }

            foreach (var allocation in component.Allocations)
            {
                if (allocation.Quantity >= 0 && allocation.ComponentId == component.Id &&
                    !deletedLocations.Contains(allocation.LocationId) && selectedLocationIds.Contains(allocation.LocationId))
                    rows.Add(new(component, allocation.LocationId, allocation.Quantity));
            }
        }
        return rows;
    }

    public static void ExportRows(string path, IReadOnlyList<LabelWorkbookRow> labelRows, IReadOnlySet<LabelWorkbookColumn> selectedColumns)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        if (selectedColumns.Count == 0) throw new InvalidOperationException("请至少选择一个导出字段。");
        var columns = Enum.GetValues<LabelWorkbookColumn>().Where(selectedColumns.Contains).ToArray();
        var rows = new List<object?[]> { columns.Select(Header).Cast<object?>().ToArray() };
        foreach (var row in labelRows)
        {
            var metadata = ParseDescription(row.Component.Description);
            rows.Add(columns.Select(column => Value(column, row, metadata)).ToArray());
        }
        SimpleXlsx.Write(path, new Dictionary<string, IReadOnlyList<object?[]>> { ["标签打印数据"] = rows });
    }

    internal static string LongQrText(ComponentRecord component) => LongQrText(component, component.Location, component.Quantity);

    private static string LongQrText(ComponentRecord component, string location, int quantity)
    {
        var metadata = ParseDescription(component.Description);
        if (metadata.SourceLabel?.Contains("JLC", StringComparison.OrdinalIgnoreCase) == true ||
            metadata.RawPayload?.TrimStart().StartsWith("{on:", StringComparison.OrdinalIgnoreCase) == true)
        {
            return BuildJlcCompatiblePayload(component, metadata, location, quantity);
        }
        var payload = new Dictionary<string, object?>
        {
            ["fmt"] = "component-vault-label", ["v"] = 1, ["sku"] = component.Sku,
            ["name"] = component.Name, ["cat"] = component.Category, ["pkg"] = component.PackageName,
            ["loc"] = location, ["qty"] = quantity, ["min"] = component.MinStock,
            ["model"] = metadata.Model, ["brand"] = metadata.Brand,
        };
        return JsonSerializer.Serialize(payload, new JsonSerializerOptions { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping });
    }

    internal static string ShortQrText(ComponentRecord component) => ShortQrText(component, component.Quantity);

    private static string ShortQrText(ComponentRecord component, int quantity) =>
        string.Join('|', "cvl3", EncodeCompact(component.Sku), quantity.ToString(CultureInfo.InvariantCulture));

    private static object Value(LabelWorkbookColumn column, LabelWorkbookRow row, LabelMetadata metadata) => column switch
    {
        LabelWorkbookColumn.Name => row.Component.Name,
        LabelWorkbookColumn.Sku => row.Component.Sku,
        LabelWorkbookColumn.Model => metadata.Model ?? string.Empty,
        LabelWorkbookColumn.PackageName => row.Component.PackageName,
        LabelWorkbookColumn.Category => row.Component.Category,
        LabelWorkbookColumn.Location => row.Location,
        LabelWorkbookColumn.Quantity => row.Quantity,
        LabelWorkbookColumn.LongQrText => LongQrText(row.Component, row.Location, row.Quantity),
        LabelWorkbookColumn.ShortQrText => ShortQrText(row.Component, row.Quantity),
        _ => throw new ArgumentOutOfRangeException(nameof(column)),
    };

    private static string Header(LabelWorkbookColumn column) => column switch
    {
        LabelWorkbookColumn.Name => "名称", LabelWorkbookColumn.Sku => "SKU", LabelWorkbookColumn.Model => "型号",
        LabelWorkbookColumn.PackageName => "封装", LabelWorkbookColumn.Category => "分类", LabelWorkbookColumn.Location => "库位",
        LabelWorkbookColumn.Quantity => "当前数量", LabelWorkbookColumn.LongQrText => "长二维码文本",
        LabelWorkbookColumn.ShortQrText => "短二维码文本", _ => throw new ArgumentOutOfRangeException(nameof(column)),
    };

    private static LabelMetadata ParseDescription(string description)
    {
        string? model = null, brand = null, sourceLabel = null, rawPayload = null;
        foreach (var rawLine in description.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries))
        {
            var line = rawLine.Trim();
            if (line.StartsWith("型号：", StringComparison.Ordinal)) model = BlankToNull(line[3..].Trim());
            else if (line.StartsWith("Model: ", StringComparison.Ordinal)) model = BlankToNull(line[7..].Trim());
            else if (line.StartsWith("品牌：", StringComparison.Ordinal)) brand = BlankToNull(line[3..].Trim());
            else if (line.StartsWith("Brand: ", StringComparison.Ordinal)) brand = BlankToNull(line[7..].Trim());
            else if (line.StartsWith("导入来源：", StringComparison.Ordinal)) sourceLabel = BlankToNull(line[5..].Trim());
            else if (line.StartsWith("Import source: ", StringComparison.Ordinal)) sourceLabel = BlankToNull(line[15..].Trim());
            else if (line.StartsWith("原始载荷：", StringComparison.Ordinal)) rawPayload = BlankToNull(line[5..].Trim());
            else if (line.StartsWith("Raw payload: ", StringComparison.Ordinal)) rawPayload = BlankToNull(line[13..].Trim());
        }
        return new(model, brand, sourceLabel, rawPayload);
    }

    private static string BuildJlcCompatiblePayload(ComponentRecord component, LabelMetadata metadata, string location, int quantity)
    {
        var values = new (string Key, string? Value)[]
        {
            ("on", $"local-{component.Sku}"), ("pc", component.Sku), ("pm", metadata.Model ?? component.Name),
            ("qty", quantity.ToString(CultureInfo.InvariantCulture)), ("mc", null), ("cc", "1"),
            ("pdi", component.Sku), ("hp", null), ("pkg", component.PackageName), ("nm", component.Name),
            ("br", metadata.Brand), ("cat", component.Category), ("loc", location),
        };
        return "{" + string.Join(',', values.Select(pair => $"{pair.Key}:{EncodeJlcValue(pair.Value)}")) + "}";
    }

    private static string EncodeJlcValue(string? value)
    {
        var normalized = value?.Trim() ?? string.Empty;
        return normalized.Length == 0 ? "null" : WebUtility.UrlEncode(normalized);
    }

    private static string EncodeCompact(string value)
    {
        var normalized = value.Trim();
        return normalized.Length == 0 ? "-" : WebUtility.UrlEncode(normalized);
    }

    private static string? BlankToNull(string value) => value.Length == 0 ? null : value;

    private static ComponentRecord Snapshot(ComponentRecord source) => new()
    {
        Id = source.Id, Sku = source.Sku, Name = source.Name, Category = source.Category,
        PackageName = source.PackageName, Location = source.Location, Description = source.Description,
        Quantity = source.Quantity, MinStock = source.MinStock, UpdatedAt = source.UpdatedAt,
        Deleted = source.Deleted, InventoryUnitCost = source.InventoryUnitCost, Allocations = source.Allocations.ToArray(),
    };
    private sealed record LabelMetadata(string? Model, string? Brand, string? SourceLabel, string? RawPayload);
}
