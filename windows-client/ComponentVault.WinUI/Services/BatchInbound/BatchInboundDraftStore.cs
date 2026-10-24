using System.Text.Json;

namespace ComponentVault.WinUI.Services.BatchInbound;

public sealed class BatchInboundDraftStore
{
    public const int MaxItems = 500; private readonly string _path;
    public BatchInboundDraftStore(string? path = null) => _path = path ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ComponentVault", "batch-jlc-draft.json");
    public IReadOnlyList<BatchScanItem> Load() { if (!File.Exists(_path)) return []; try { return Normalize(JsonSerializer.Deserialize<List<BatchScanItem>>(File.ReadAllText(_path)) ?? throw new InvalidDataException("草稿内容为空。")); } catch (Exception exception) when (exception is JsonException or InvalidDataException or IOException or UnauthorizedAccessException) { throw new InvalidDataException("批量入库草稿无法读取；原文件已保留，请先备份或修复。", exception); } }
    public (IReadOnlyList<BatchScanItem> Items, int Duplicates) Merge(IEnumerable<string> raws, IEnumerable<BatchScanItem>? current = null)
    { var list = Normalize(current ?? Load()).ToList(); var seen = list.Select(x => x.Raw).ToHashSet(StringComparer.Ordinal); var duplicates = 0; foreach (var candidate in raws) { var raw = candidate.Trim(); if (raw.Length == 0) continue; if (!seen.Add(raw)) { duplicates++; continue; } if (list.Count >= MaxItems) throw new InvalidOperationException("批次最多收集 500 个包装。"); list.Add(new(Guid.NewGuid().ToString("D"), raw)); } Save(list); return (list, duplicates); }
    public void Save(IReadOnlyList<BatchScanItem> items) { var normalized = Normalize(items); Directory.CreateDirectory(Path.GetDirectoryName(_path)!); var temp = _path + "." + Guid.NewGuid().ToString("N") + ".tmp"; try { File.WriteAllText(temp, JsonSerializer.Serialize(normalized)); File.Move(temp, _path, true); } finally { if (File.Exists(temp)) File.Delete(temp); } }
    public (IReadOnlyList<BatchScanItem> Items, int Added, int Duplicates) MergeImported(
        IEnumerable<BatchScanItem> rows, Func<string, bool>? isCommitted = null)
    {
        var items = Load().ToList();
        var receipts = items.Select(item => item.ReceiptId).ToHashSet(StringComparer.Ordinal);
        var raws = items.Select(item => item.Raw).ToHashSet(StringComparer.Ordinal);
        var added = 0;
        var duplicates = 0;
        foreach (var row in rows)
        {
            if (receipts.Contains(row.ReceiptId) || raws.Contains(row.Raw.Trim()) || isCommitted?.Invoke(row.ReceiptId) == true)
            {
                duplicates++;
                continue;
            }
            if (items.Count >= MaxItems)
                throw new InvalidOperationException("导入后草稿将超过 500 条；请先提交或清理当前草稿，再重新导入。未添加本次表格。");
            receipts.Add(row.ReceiptId);
            raws.Add(row.Raw.Trim());
            items.Add(row);
            added++;
        }
        Save(items);
        return (items, added, duplicates);
    }
    public void Clear() { if (File.Exists(_path)) File.Delete(_path); }
    private static IReadOnlyList<BatchScanItem> Normalize(IEnumerable<BatchScanItem> items) { var source = items.ToArray(); if (source.Length > MaxItems) throw new InvalidDataException("草稿超过 500 个包装，未执行截断。"); if (source.Any(x => string.IsNullOrWhiteSpace(x.Raw) || !Guid.TryParse(x.ReceiptId, out _) || System.Text.Encoding.UTF8.GetByteCount(x.Raw.Trim()) > 16 * 1024)) throw new InvalidDataException("草稿包含空 raw、无效 receipt 或超过 16 KiB 的单条 raw。"); var normalized = source.Select(x => x with { Raw = x.Raw.Trim() }).ToArray(); if (normalized.Select(x => x.Raw).Distinct(StringComparer.Ordinal).Count() != normalized.Length) throw new InvalidDataException("草稿包含重复 raw，未静默删除。"); return normalized; }
}
