using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using ExcelDataReader;

namespace ComponentVault.WinUI.Services.BatchInbound;

public sealed record JlcOrderSpreadsheetImport(
    IReadOnlyList<BatchScanItem> Rows,
    int MergedLines,
    int SkippedFees,
    int SkippedSummaries,
    int InvalidRows);

/// <summary>Reads mall order statements and order details, never EDA BOM quantities.</summary>
public static partial class JlcOrderSpreadsheetParser
{
    public const int MaxFileBytes = 10 * 1024 * 1024;
    public const int MaxRows = 500;

    public static JlcOrderSpreadsheetImport ParseFile(string path, CancellationToken cancellationToken = default)
    {
        // Mall exports are often still open in Excel when the user selects them.
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        return Parse(stream, cancellationToken);
    }

    /// <summary>Leaves the caller's stream open; invalid product quantities remain in Rows for review.</summary>
    public static JlcOrderSpreadsheetImport Parse(Stream input, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(input);
        cancellationToken.ThrowIfCancellationRequested();
        using var buffer = new MemoryStream();
        var chunk = new byte[8192];
        int count;
        while ((count = input.Read(chunk, 0, chunk.Length)) != 0)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (buffer.Length + count > MaxFileBytes) throw new InvalidDataException("订单表超过 10 MiB，请分批导入。");
            buffer.Write(chunk, 0, count);
        }
        var bytes = buffer.ToArray();
        if (bytes.Length < 8 || !bytes.AsSpan(0, 8).SequenceEqual(new byte[] { 0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1 }))
            throw new InvalidDataException("请选择立创商城导出的旧版 .xls 订单表。");

        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        buffer.Position = 0;
        using var reader = ExcelReaderFactory.CreateBinaryReader(buffer, new ExcelReaderConfiguration { LeaveOpen = true, SinglePassMode = true });
        var fileHash = Convert.ToHexStringLower(SHA256.HashData(bytes));
        var grouped = new Dictionary<string, OrderLine>(StringComparer.Ordinal);
        var mergedLines = 0;
        var skippedFees = 0;
        var skippedSummaries = 0;
        var recognized = false;
        var sheetIndex = 0;
        var scannedRows = 0;
        do
        {
            sheetIndex++;
            if (sheetIndex > 64) throw new InvalidDataException("订单表工作表数量过多，请仅保留需要导入的订单工作表。");
            Header? header = null;
            var orderNumber = "";
            var rowIndex = 0;
            while (reader.Read())
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (++scannedRows > 100_000 || reader.FieldCount > 256)
                    throw new InvalidDataException("订单表扫描范围过大，请删除多余空行或拆分文件后导入。");
                rowIndex++;
                var cells = Enumerable.Range(0, reader.FieldCount).Select(reader.GetValue).ToArray();
                if (header is null)
                {
                    if (rowIndex > 40) break;
                    FindOrderNumber(cells, ref orderNumber);
                    header = FindHeader(cells);
                    recognized |= header is not null;
                    continue;
                }

                // Repeated printed headers are not product data.
                if (FindHeader(cells) is not null) continue;
                string Value(int column) => column >= 0 && column < cells.Length ? CellText(cells[column]) : "";
                var skuText = Value(header.Sku);
                var name = Value(header.Name);
                var model = Value(header.Model);
                var brand = Value(header.Brand);
                var category = Value(header.Category);
                var packageName = Value(header.Package);
                var quantityText = Value(header.Quantity);
                var order = header.Order >= 0 ? Value(header.Order) : orderNumber;
                var content = string.Join(" ", cells.Select(CellText));
                var productData = skuText.Length > 0 || model.Length > 0 || brand.Length > 0 || category.Length > 0 || packageName.Length > 0;
                if (!productData && FeePattern().IsMatch(name.Length > 0 ? name : content)) { skippedFees++; continue; }
                if (!productData && name.Length == 0 && quantityText.Length == 0 &&
                    (SummaryPattern().IsMatch(content.Trim()) || content.TrimStart().StartsWith("说明", StringComparison.Ordinal)))
                { skippedSummaries++; continue; }
                if (cells.All(cell => CellText(cell).Length == 0)) continue;
                // Decorative/footer rows lack all product fields and ordered quantities.
                if (!productData && name.Length == 0 && quantityText.Length == 0) { skippedSummaries++; continue; }

                var errors = new List<string>();
                var sku = skuText.ToUpperInvariant();
                if (!SkuPattern().IsMatch(sku)) { sku = ""; errors.Add("商品编号不是合法的 C 料号，请手动补充。"); }
                if (order.Length == 0 || !OrderPattern().IsMatch(order))
                { order = ""; errors.Add("缺少有效订单号，无法进行订单去重，请核对来源后手动确认。"); }
                var quantity = ReadQuantity(header.Quantity < cells.Length ? cells[header.Quantity] : null, Value(header.Unit));
                if (quantity is null) errors.Add("订购数量缺失、不是正整数或超过允许范围，请手动确认。");
                var status = Value(header.Status);
                if (status.Contains("取消", StringComparison.Ordinal) || status.Contains("退款", StringComparison.Ordinal))
                    errors.Add("该明细属于取消或退款订单，请核对实际收货后手动确认。");
                var withheld = Value(header.Withheld);
                if (withheld.Length > 0 && withheld is not "否" and not "0" and not "-" && !withheld.Equals("false", StringComparison.OrdinalIgnoreCase))
                    errors.Add("该明细标记为不发此货，请核对实际收货后手动确认。");

                // Android uses the full order- hash. Windows drafts require a GUID;
                // use the same digest for both identities, without a filename dependency.
                var identified = order.Length > 0 && sku.Length > 0;
                var identity = identified ? order + '\u001f' + sku : $"unidentified\u001f{fileHash}\u001f{sheetIndex}\u001f{rowIndex}";
                var digest = SHA256.HashData(Encoding.UTF8.GetBytes(identity));
                var raw = "lcsc-order:" + (identified ? "order-" : "unidentified:") + Convert.ToHexStringLower(digest);
                var line = new OrderLine(new Guid(digest.AsSpan(0, 16)).ToString("D"), raw, sku, name, model, brand, category,
                    packageName, quantity, quantity?.ToString(CultureInfo.InvariantCulture) ?? quantityText, errors);
                if (grouped.TryGetValue(raw, out var prior))
                {
                    mergedLines++;
                    if (prior.Name != name || prior.Model != model || prior.Brand != brand || prior.Category != category || prior.Package != packageName)
                        prior.Errors.Add("同订单同料号的商品资料不一致，请核对合并明细。");
                    prior.Errors.UnionWith(errors);
                    if (prior.Quantity is int oldQuantity && quantity is int newQuantity && (long)oldQuantity + newQuantity <= int.MaxValue)
                    {
                        prior.Quantity = oldQuantity + newQuantity;
                        prior.QuantityText = prior.Quantity.Value.ToString(CultureInfo.InvariantCulture);
                    }
                    else
                    {
                        prior.Quantity = null;
                        prior.QuantityText = "";
                        prior.Errors.Add("合并明细含非法数量或数量合计溢出，请手动确认订购总量。");
                    }
                }
                else
                {
                    if (grouped.Count >= MaxRows) throw new InvalidDataException("订单表超过 500 个不同订单商品，请分批导入。");
                    grouped.Add(raw, line);
                }
            }
        } while (reader.NextResult());
        if (!recognized) throw new InvalidDataException("未识别立创商城对账单或订单详情表头，请确认选择了订单 .xls 文件。");
        if (grouped.Count == 0) throw new InvalidDataException("订单表中没有可导入的商品明细。");
        var rows = grouped.Values.Select(ToBatchItem).ToArray();
        return new(rows, mergedLines, skippedFees, skippedSummaries, rows.Count(row => row.Error.Length > 0));
    }

    private static BatchScanItem ToBatchItem(OrderLine line)
    {
        var displayName = line.Model.Length == 0 ? line.Name : line.Brand.Length == 0 || line.Model.Contains(line.Brand, StringComparison.OrdinalIgnoreCase)
            ? line.Model : line.Brand + " " + line.Model;
        var details = new List<string>();
        if (line.Model.Length > 0) details.Add("型号：" + line.Model);
        if (line.Brand.Length > 0) details.Add("品牌：" + line.Brand);
        if (line.Name.Length > 0) details.Add("商品名称：" + line.Name);
        var error = string.Join("\n", line.Errors.Distinct(StringComparer.Ordinal));
        return new(line.ReceiptId, line.Raw)
        {
            Status = "captured", Sku = line.Sku, QuantityText = line.QuantityText,
            Name = displayName, Category = line.Category, PackageName = line.Package,
            Description = string.Join("\n", details), Error = error, Selected = error.Length == 0,
        };
    }

    private static void FindOrderNumber(object?[] cells, ref string orderNumber)
    {
        for (var index = 0; index < cells.Length; index++)
        {
            var text = CellText(cells[index]);
            var match = OrderLabelPattern().Match(text);
            if (!match.Success) continue;
            var value = match.Groups[1].Value.Trim();
            if (value.Length == 0 && index + 1 < cells.Length) value = CellText(cells[index + 1]);
            if (OrderPattern().IsMatch(value)) orderNumber = value;
        }
    }

    private static Header? FindHeader(object?[] cells)
    {
        var columns = new Dictionary<string, int>(StringComparer.Ordinal);
        for (var index = 0; index < cells.Length; index++) columns.TryAdd(NormalizeHeader(CellText(cells[index])), index);
        int Index(params string[] names) => names.Select(name => columns.GetValueOrDefault(name, -1)).FirstOrDefault(index => index >= 0, -1);
        var sku = Index("商品编号");
        var quantity = Index("订购数量(修改后)", "订购数量");
        var name = Index("商品名称");
        var model = Index("商品型号", "厂家型号");
        var order = Index("订单编号", "订单号");
        var brand = Index("品牌");
        if (sku < 0 || quantity < 0 || name < 0 || model < 0 || brand < 0) return null;
        // Order details may have a missing order label; retain their rows for manual review.
        if (order < 0 && Index("厂家型号") < 0) return null;
        return new Header(sku, quantity, name, model, brand, Index("商品类型", "分类", "商品分类"), Index("封装格式", "封装"),
            order, Index("订单状态"), Index("是否不发此货"), Index("商品单位", "单位"));
    }

    private static int? ReadQuantity(object? cell, string unit)
    {
        if (cell is double number)
            return double.IsFinite(number) && number > 0 && number <= int.MaxValue && number == Math.Truncate(number) ? (int)number : null;
        if (cell is int integer) return integer > 0 ? integer : null;
        var text = CellText(cell);
        var match = QuantityPattern().Match(text);
        if (!match.Success) return null;
        var suffix = match.Groups[2].Value;
        if (suffix.Length > 0 && !suffix.Equals(unit, StringComparison.OrdinalIgnoreCase) && !UnitPattern().IsMatch(suffix)) return null;
        return decimal.TryParse(match.Groups[1].Value, NumberStyles.AllowThousands | NumberStyles.AllowDecimalPoint, CultureInfo.InvariantCulture, out var value)
            && value > 0 && value <= int.MaxValue && value == decimal.Truncate(value) ? (int)value : null;
    }

    private static string NormalizeHeader(string value) => Regex.Replace(value, @"\s+", "").Replace('（', '(').Replace('）', ')');
    private static string CellText(object? value) => value is null or DBNull ? "" : Convert.ToString(value, CultureInfo.InvariantCulture)?.Trim() ?? "";

    private sealed record Header(int Sku, int Quantity, int Name, int Model, int Brand, int Category, int Package, int Order, int Status, int Withheld, int Unit);
    private sealed class OrderLine(string receiptId, string raw, string sku, string name, string model, string brand, string category,
        string package, int? quantity, string quantityText, List<string> errors)
    {
        public string ReceiptId { get; } = receiptId;
        public string Raw { get; } = raw;
        public string Sku { get; } = sku;
        public string Name { get; } = name;
        public string Model { get; } = model;
        public string Brand { get; } = brand;
        public string Category { get; } = category;
        public string Package { get; } = package;
        public int? Quantity { get; set; } = quantity;
        public string QuantityText { get; set; } = quantityText;
        public HashSet<string> Errors { get; } = errors.ToHashSet(StringComparer.Ordinal);
    }

    [GeneratedRegex("^C[0-9]{1,10}$", RegexOptions.CultureInvariant)] private static partial Regex SkuPattern();
    [GeneratedRegex("^SO[0-9]+$", RegexOptions.CultureInvariant)] private static partial Regex OrderPattern();
    [GeneratedRegex(@"^(?:订单编号|订单号)\s*[:：]\s*(.*)$", RegexOptions.CultureInvariant)] private static partial Regex OrderLabelPattern();
    [GeneratedRegex(@"^(\d{1,3}(?:,\d{3})+(?:\.0+)?|\d+(?:\.0+)?)\s*([^\d\s.,+-]*)$", RegexOptions.CultureInvariant)] private static partial Regex QuantityPattern();
    [GeneratedRegex("^(?:个|只|片|颗|件|套|支|卷|包|PCS|PC|PIECES)$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)] private static partial Regex UnitPattern();
    [GeneratedRegex("配送费|运费|运输费|快递费", RegexOptions.CultureInvariant)] private static partial Regex FeePattern();
    [GeneratedRegex("^(?:商品合计金额|订单合计金额|退款合计金额|合计|总计|商品总额|订单总额)", RegexOptions.CultureInvariant)] private static partial Regex SummaryPattern();
}
