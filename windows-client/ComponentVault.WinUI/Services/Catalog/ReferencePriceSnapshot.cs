using System.Globalization;
using System.Text.Json;

namespace ComponentVault.WinUI.Services.Catalog;

public sealed record ReferencePriceSnapshot(string UnitPrice, string Currency, int? MinimumQuantity, string SourceUrl, string QueriedAt)
{
    private static readonly string[] Prefixes = ["参考单价：", "参考币种：", "参考起订量：", "价格来源：", "价格查询时间："];
    public static ReferencePriceSnapshot? Create(string? price, string? currency, int? minimum, string? source, string? at)
    {
        if (!decimal.TryParse(price, NumberStyles.AllowDecimalPoint, CultureInfo.InvariantCulture, out var amount) || amount <= 0
            || currency is not { Length: 3 } || !currency.All(char.IsAsciiLetter)
            || !Uri.TryCreate(source, UriKind.Absolute, out var uri) || uri.Scheme != "https"
            || !DateTimeOffset.TryParse(at, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var time)) return null;
        return new(amount.ToString(CultureInfo.InvariantCulture), currency.ToUpperInvariant(), minimum > 0 ? minimum : null, uri.AbsoluteUri, time.ToUniversalTime().ToString("O"));
    }
    public static ReferencePriceSnapshot? Read(string? description)
    {
        var lines = (description ?? "").Split('\n');
        string? Value(int i) => lines.LastOrDefault(x => x.StartsWith(Prefixes[i], StringComparison.Ordinal))?[Prefixes[i].Length..].Trim();
        return Create(Value(0), Value(1), int.TryParse(Value(2), out var quantity) ? quantity : null, Value(3), Value(4));
    }
    public static string Merge(string description, ReferencePriceSnapshot? price)
    {
        if (price is null) return description;
        var lines = description.Split('\n').Where(x => !Prefixes.Any(p => x.StartsWith(p, StringComparison.Ordinal))).ToList();
        if (lines.Count == 1 && lines[0].Length == 0) lines.Clear();
        lines.AddRange([Prefixes[0] + price.UnitPrice, Prefixes[1] + price.Currency, Prefixes[2] + (price.MinimumQuantity?.ToString(CultureInfo.InvariantCulture) ?? "未提供"), Prefixes[3] + price.SourceUrl, Prefixes[4] + price.QueriedAt]);
        return string.Join("\n", lines);
    }
    internal static ReferencePriceSnapshot? FromProduct(JsonElement product, string source)
    {
        if (!product.TryGetProperty("offers", out var offers)) return null;
        var items = offers.ValueKind == JsonValueKind.Array ? offers.EnumerateArray().ToArray() : [offers];
        foreach (var offer in items)
        {
            if (offer.ValueKind != JsonValueKind.Object) continue;
            if (offer.TryGetProperty("@type", out var type))
            {
                static bool Aggregate(JsonElement value) => value.ValueKind == JsonValueKind.String
                    && (value.GetString() == "AggregateOffer" || value.GetString()?.EndsWith("/AggregateOffer", StringComparison.Ordinal) == true);
                if (Aggregate(type) || type.ValueKind == JsonValueKind.Array && type.EnumerateArray().Any(Aggregate)) continue;
            }
            string? Text(string key) => offer.TryGetProperty(key, out var v) && v.ValueKind is JsonValueKind.String or JsonValueKind.Number ? v.ToString() : null;
            int? minimum = null;
            if (offer.TryGetProperty("eligibleQuantity", out var quantity) && quantity.ValueKind == JsonValueKind.Object
                && quantity.TryGetProperty("minValue", out var min) && int.TryParse(min.ToString(), out var value) && value > 0) minimum = value;
            if (Create(Text("price"), Text("priceCurrency"), minimum, source, DateTimeOffset.UtcNow.ToString("O")) is { } price) return price;
        }
        return null;
    }
    public string Display => CultureInfo.CurrentUICulture.TwoLetterISOLanguageName.Equals("zh", StringComparison.OrdinalIgnoreCase)
        ? $"{Currency} {UnitPrice} / 件 · 适用数量：{MinimumQuantity?.ToString() ?? "未提供"}\n查询时间（UTC）：{QueriedAt}\n来源：{SourceUrl}"
        : $"{Currency} {UnitPrice} / piece · Applicable quantity: {MinimumQuantity?.ToString() ?? "Not provided"}\nQueried at (UTC): {QueriedAt}\nSource: {SourceUrl}";
}

public static partial class LcscPublicCatalog
{
    public static IReadOnlyList<LcscProductMetadata> ParseInternationalEdaSearch(string json)
    {
        try
        {
            using var document = JsonDocument.Parse(json);
            if (Text(document.RootElement, "code") != "0") throw new InvalidDataException("International EDA request failed.");
            if (!TryProperty(document.RootElement, out var list, "result", "productList") || list.ValueKind != JsonValueKind.Array) throw new InvalidDataException("International EDA product list missing.");
            var output = new List<LcscProductMetadata>();
            foreach (var item in list.EnumerateArray())
            {
                var sku = NormalizeSku(Text(item, "number")); if (sku is null) continue;
                string? image = null;
                if (item.TryGetProperty("image", out var images) && images.ValueKind == JsonValueKind.Array)
                    foreach (var entry in images.EnumerateArray()) if (TrustedImageUrl(Text(entry, "224x224")) is { } found) { image = found; break; }
                // Share metadata filtering while keeping the international schema and source explicit.
                var normalized = new Dictionary<string, object?> { ["code"] = sku, ["model"] = Text(item, "mpn"), ["brandName"] = Text(item, "manufacturer"), ["standard"] = Text(item, "package"), ["image"] = image };
                if (item.TryGetProperty("device_info", out var device)) normalized["device_info"] = device;
                var mapped = JsonSerializer.Serialize(new { code = 200, result = new { productList = new[] { normalized } } });
                if (ParseEda(sku, mapped) is { } metadata) output.Add(metadata with { Source = "easyeda_public_library", OfficialUrl = ProductUri(sku)!.AbsoluteUri });
                if (output.Count == 20) break;
            }
            return output;
        }
        catch (JsonException error) { throw new InvalidDataException("Invalid international EDA response.", error); }
    }

    public static IReadOnlyList<LcscProductMetadata> ParseEdaSearch(string json)
    {
        try
        {
            using var document = JsonDocument.Parse(json);
            if (Text(document.RootElement, "code") != "200") throw new InvalidDataException("Domestic EDA request failed.");
            if (!TryProperty(document.RootElement, out var list, "result", "productList") || list.ValueKind != JsonValueKind.Array) throw new InvalidDataException("EDA product list missing.");
            return list.EnumerateArray().Select(item => NormalizeSku(Text(item, "code"))).Where(sku => sku is not null).Distinct()
                .Select(sku => ParseEda(sku!, json)).Where(item => item is not null).Cast<LcscProductMetadata>().Take(20).ToArray();
        }
        catch (JsonException error) { throw new InvalidDataException("Invalid EDA response.", error); }
    }

    public static LcscProductMetadata? ParseEda(string sku, string json)
    {
        var expected = NormalizeSku(sku); if (expected is null) return null;
        try
        {
            using var document = JsonDocument.Parse(json);
            if (Text(document.RootElement, "code") != "200") throw new InvalidDataException("Domestic EDA request failed.");
            if (!TryProperty(document.RootElement, out var list, "result", "productList") || list.ValueKind != JsonValueKind.Array) throw new InvalidDataException("EDA product list missing.");
            foreach (var item in list.EnumerateArray())
            {
                if (!string.Equals(Text(item, "code"), expected, StringComparison.OrdinalIgnoreCase)) continue;
                var parameters = new Dictionary<string, string>();
                string? category = null, description = null;
                if (item.TryGetProperty("device_info", out var device) && device.ValueKind == JsonValueKind.Object)
                {
                    if (TryProperty(device, out var attributes, "attributes") && attributes.ValueKind == JsonValueKind.Object)
                    {
                        var internalKeys = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { "Symbol", "Footprint", "Designator", "Add into BOM", "Convert to PCB", "Name", "Supplier", "Supplier Part", "Supplier Footprint", "Manufacturer", "Manufacturer Part", "Datasheet", "LCSC Part Name", "JLCPCB Part Class" };
                        foreach (var attribute in attributes.EnumerateObject())
                            if (!internalKeys.Contains(attribute.Name) && !attribute.Name.StartsWith("3D", StringComparison.OrdinalIgnoreCase) && attribute.Value.ValueKind is JsonValueKind.String or JsonValueKind.Number)
                                parameters[attribute.Name] = attribute.Value.ToString();
                        description = Text(attributes, "LCSC Part Name");
                    }
                    var categories = new List<string>();
                    foreach (var name in new[] { "parent_tag", "child_tag" })
                        if (TryProperty(device, out var tag, "tags", name) && tag.ValueKind == JsonValueKind.Object && (Text(tag, "name_cn") ?? Text(tag, "name")) is { Length: > 0 } text) categories.Add(text);
                    category = string.Join("/", categories);
                }
                var image = TrustedImageUrl(Text(item, "image")?.Split("<$>", StringSplitOptions.None).FirstOrDefault());
                var model = Text(item, "model");
                var source = ChinaSearchUri(expected)!.AbsoluteUri;
                var pdf = Text(item, "pdfFileUrl");
                var datasheet = pdf is null ? null : TrustedDatasheetUrl(pdf.StartsWith("/") ? "https://atta.szlcsc.com" + pdf : pdf);
                ReferencePriceSnapshot? quote = null;
                if ((!item.TryGetProperty("isShowPrice", out var showPrice) || showPrice.ValueKind != JsonValueKind.False)
                    && item.TryGetProperty("priceList", out var prices) && prices.ValueKind == JsonValueKind.Array)
                {
                    foreach (var tier in prices.EnumerateArray())
                    {
                        if (!int.TryParse(Text(tier, "startNumber"), out var start) || start <= 0) continue;
                        var candidate = ReferencePriceSnapshot.Create(Text(tier, "price"), "CNY", start, source, DateTimeOffset.UtcNow.ToString("O"));
                        if (candidate is not null && (quote is null || start < quote.MinimumQuantity)) quote = candidate;
                    }
                }
                return new(expected, model ?? expected, model, Text(item, "brandName"), Text(item, "standard"),
                    Text(item, "catalogName") ?? NormalizeCategory(category), category, source, image, datasheet,
                    Parameters: parameters, Description: description ?? Text(item, "name"), Source: "lceda_public_library", ReferencePrice: quote);
            }
            return null;
        }
        catch (JsonException error) { throw new InvalidDataException("Invalid EDA response.", error); }
    }
}
