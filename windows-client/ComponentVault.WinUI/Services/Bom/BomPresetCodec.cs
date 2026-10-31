namespace ComponentVault.WinUI.Services.Bom;

public sealed record BomPresetSheet(string Name, int Index, bool Hidden);
public sealed record BomPresetSourceRow(string SheetName, int RowNumber, int QuantityPerSet, Dictionary<string, string> Fields);
public sealed record BomPresetLine(string Id, string? Sku, string? Name, string? Model, string? PackageName,
    int QuantityPerSet, string? Selection, bool Excluded, IReadOnlyList<BomPresetSourceRow> SourceRows);
public sealed record BomPresetContent(int SchemaVersion, string ProjectName, int ProductionSets, string SourceName,
    string FileSha256, IReadOnlyList<BomPresetSheet> AvailableSheets, BomPresetSheet SelectedSheet,
    IReadOnlyList<string> Warnings, IReadOnlyList<BomPresetLine> Lines);
public sealed record BomPresetRecord(string Id, string Name, string UpdatedAt, bool Deleted, BomPresetContent? Content);
public sealed record BomPresetEnvelope(int Version, IReadOnlyList<BomPresetRecord> Presets);
public sealed record BomPresetSyncRequest(string DeviceId, IReadOnlyList<BomPresetRecord> Presets);
public sealed record BomPresetSyncResponse(string ServerTime, int BomPresetProtocol, IReadOnlyList<BomPresetRecord> Presets);

public static class BomPresetCodec
{
    public static BomPresetRecord Encode(BomPreset preset, string id, string updatedAt)
    {
        var metadata = preset.Document.PresetContent;
        var sheet = metadata?.SelectedSheet ?? new BomPresetSheet(preset.Document.WorksheetName ?? "", 0, false);
        var lines = preset.Document.Rows.Select((row, index) =>
        {
            var previous = metadata?.Lines.ElementAtOrDefault(index);
            var sources = previous?.SourceRows ?? [new BomPresetSourceRow(sheet.Name, row.RowNumber, row.Quantity ?? 0,
                new Dictionary<string, string>(StringComparer.Ordinal) { ["reference"] = row.Reference ?? "" })];
            return new BomPresetLine(row.PresetLineId ?? previous?.Id ?? $"row-{row.RowNumber}", row.Sku, row.Name, row.SupplierPartNumber,
                row.PackageName, row.Quantity ?? 0, preset.SelectedComponents.GetValueOrDefault(row.RowNumber),
                preset.SkippedRows.Contains(row.RowNumber), sources);
        }).ToArray();
        var content = new BomPresetContent(1, preset.ProjectName, preset.BatchQuantity, preset.Document.SourceName,
            metadata?.FileSha256 ?? "", metadata?.AvailableSheets ?? [sheet], sheet, preset.Document.Warnings, lines);
        Validate(new(id, preset.Name, updatedAt, false, content));
        return new(id, preset.Name, updatedAt, false, content);
    }

    public static BomPreset Decode(BomPresetRecord record)
    {
        Validate(record);
        var content = record.Content ?? throw new InvalidDataException("已删除的预设没有可加载内容。");
        var originalNumbers = content.Lines.Select(line => line.SourceRows.FirstOrDefault()?.RowNumber ?? 0).ToArray();
        var rowNumbers = originalNumbers.All(number => number > 0) && originalNumbers.Distinct().Count() == content.Lines.Count ? originalNumbers : Enumerable.Range(1, content.Lines.Count).ToArray();
        var rows = content.Lines.Select((line, index) => new BomSourceRow(rowNumbers[index], line.Sku, line.Model,
            line.PackageName, line.QuantityPerSet, line.Name,
            string.Join(", ", line.SourceRows.SelectMany(source => source.Fields).Where(pair => new[] { "reference", "designator", "位号" }.Contains(pair.Key, StringComparer.OrdinalIgnoreCase)).Select(pair => pair.Value).Where(value => !string.IsNullOrWhiteSpace(value))), line.Id)).ToArray();
        return new(record.Name, new(content.SourceName, content.SelectedSheet.Name, rows, content.Warnings, content),
            content.ProjectName, content.ProductionSets,
            content.Lines.Select((line, index) => (line, index)).Where(pair => pair.line.Selection is not null)
                .ToDictionary(pair => rowNumbers[pair.index], pair => pair.line.Selection!),
            content.Lines.Select((line, index) => (line, index)).Where(pair => pair.line.Excluded).Select(pair => rowNumbers[pair.index]).ToList(), record.Id, record.UpdatedAt);
    }

    public static void Validate(BomPresetRecord record)
    {
        if (string.IsNullOrWhiteSpace(record.Id) || string.IsNullOrWhiteSpace(record.Name) || !DateTimeOffset.TryParse(record.UpdatedAt, out _))
            throw new InvalidDataException("预设 ID、名称或时间无效。");
        if (record.Deleted) return;
        var content = record.Content;
        if (content is null || content.SchemaVersion != 1 || content.ProductionSets < 1 || content.Lines.Count is < 0 or > 5000 || content.SelectedSheet is null || content.AvailableSheets is null || content.Warnings is null)
            throw new InvalidDataException("预设内容版本或元数据无效。");
        if (content.Lines.Any(line => string.IsNullOrWhiteSpace(line.Id) || line.QuantityPerSet < 1 || line.SourceRows is null)
            || content.Lines.Select(line => line.Id).Distinct(StringComparer.Ordinal).Count() != content.Lines.Count)
            throw new InvalidDataException("预设行 ID 或每套用量无效。");
        foreach (var line in content.Lines) _ = checked(line.QuantityPerSet * content.ProductionSets);
    }
}
