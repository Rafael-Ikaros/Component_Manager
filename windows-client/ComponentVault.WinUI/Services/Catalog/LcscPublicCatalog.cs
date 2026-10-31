using System.Collections.Concurrent;
using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using ComponentVault.WinUI.Services;

namespace ComponentVault.WinUI.Services.Catalog;

public sealed record LcscProductMetadata(
    string Sku, string Name, string? Model, string? Brand, string? PackageName,
    string Category, string? CategoryPath, string OfficialUrl, string? ImageUrl,
    string? DatasheetUrl = null, IReadOnlyDictionary<string, string>? Parameters = null,
    string? Description = null, string? Source = null, string? LookupNotice = null, ReferencePriceSnapshot? ReferencePrice = null
);

public sealed class LcscDomesticBlockedException : IOException
{
    public LcscDomesticBlockedException() : base("LCSC verification page returned") { }
}
public sealed class LcscDomesticRateLimitedException(int? retryAfterSeconds) : IOException("LCSC rate limited")
{
    public int? RetryAfterSeconds { get; } = retryAfterSeconds;
}
public sealed class LcscDomesticCoolingDownException(LcscDomesticGateReason reason) : IOException("LCSC domestic lookup cooling down")
{
    public LcscDomesticGateReason Reason { get; } = reason;
}

public static partial class LcscPublicCatalog
{
    private static readonly ConcurrentDictionary<string, string> ImageCache = new(StringComparer.OrdinalIgnoreCase);
    private static readonly IReadOnlyDictionary<string, string> KnownCategorySegments = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
    {
        ["Capacitors"] = "电容器", ["Ceramic Capacitors"] = "陶瓷电容器",
        ["Aluminum Electrolytic Capacitors"] = "铝电解电容器", ["Tantalum Capacitors"] = "钽电容器", ["Film Capacitors"] = "薄膜电容器",
        ["Resistors"] = "电阻器", ["Chip Resistor - Surface Mount"] = "贴片电阻", ["Through Hole Resistors"] = "直插电阻", ["Current Sense Resistors"] = "采样电阻",
        ["Inductors, Coils, Chokes"] = "电感器/线圈/扼流圈", ["Fixed Inductors"] = "固定电感器", ["Ferrite Beads and Chips"] = "磁珠",
        ["Integrated Circuits (ICs)"] = "集成电路", ["Embedded"] = "嵌入式处理器及控制器", ["Microcontrollers"] = "微控制器",
        ["Power Management (PMIC)"] = "电源管理芯片", ["Voltage Regulators - Linear"] = "线性稳压器", ["DC DC Switching Regulators"] = "DC-DC开关稳压器",
        ["Voltage Regulators - Linear, Low Drop Out (LDO) Regulators"] = "线性稳压器（LDO）",
        ["LDO Regulators"] = "线性稳压器（LDO）",
        ["Memory"] = "存储器", ["Logic"] = "逻辑器件", ["Amplifiers"] = "放大器", ["Operational Amplifiers"] = "运算放大器",
        ["Diodes"] = "二极管", ["Rectifiers"] = "整流器", ["Transistors"] = "晶体管", ["MOSFETs"] = "MOS管",
        ["Optoelectronics"] = "光电器件", ["LED Indication - Discrete"] = "LED指示器件", ["Sensors"] = "传感器", ["Temperature Sensors"] = "温度传感器",
        ["Connectors, Interconnects"] = "连接器", ["Headers, Male Pins"] = "排针", ["Rectangular Connectors - Housings"] = "矩形连接器外壳", ["Terminal Blocks"] = "接线端子",
        ["Crystals, Oscillators, Resonators"] = "晶体/振荡器/谐振器", ["Crystals"] = "晶体", ["Oscillators"] = "振荡器",
        ["LED Drivers"] = "LED驱动器", ["LED Drivers ICs"] = "LED驱动芯片",
    };
    private static readonly IReadOnlyDictionary<string, string> LegacyExactCategories = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
    {
        ["crystals, oscillators, resonators/crystals"] = "晶体",
        ["capacitors/ceramic capacitors"] = "电容",
        ["resistors/chip resistor - surface mount"] = "电阻",
        ["integrated circuits (ics)/embedded/microcontrollers"] = "微控制器",
        ["LED Drivers/LED Drivers ICs"] = "LED驱动",
    };
    private static readonly HashSet<string> TrustedImageHosts = new(StringComparer.OrdinalIgnoreCase)
    {
        "assets.lcsc.com", "www.lcsc.com", "img.szlcsc.com", "image.szlcsc.com", "static.szlcsc.com", "alimg.szlcsc.com",
    };

    public static string? NormalizeSku(string? value) =>
        value?.Trim().ToUpperInvariant() is { } sku && SkuPattern().IsMatch(sku) ? sku : null;

    public static Uri? ProductUri(string? sku) => NormalizeSku(sku) is { } normalized
        ? new Uri($"https://www.lcsc.com/product-detail/{normalized}.html") : null;

    public static Uri? ChinaSearchUri(string? keyword) => keyword?.Trim() is { Length: > 0 } normalized
        ? new Uri($"https://so.szlcsc.com/global.html?k={Uri.EscapeDataString(normalized)}") : null;

    public static string? TrustedImageUrl(string? value) => TrustedHttpsUrl(value, TrustedImageHosts);
    public static string? TrustedDatasheetUrl(string? value) => TrustedHttpsUrl(value, new HashSet<string>(StringComparer.OrdinalIgnoreCase) { "atta.szlcsc.com" });

    private static string? TrustedHttpsUrl(string? value, IReadOnlySet<string> hosts)
    {
        if (!Uri.TryCreate(value?.Trim(), UriKind.Absolute, out var uri)) return null;
        return uri.Scheme == Uri.UriSchemeHttps && uri.UserInfo.Length == 0 && uri.IsDefaultPort && hosts.Contains(uri.Host) ? uri.AbsoluteUri : null;
    }

    public static string NormalizeCategory(string? officialPath)
    {
        var path = CleanText(officialPath) ?? string.Empty;
        if (path.Length == 0 || path.Any(character => character is >= '\u4e00' and <= '\u9fff')) return path;
        if (LegacyExactCategories.TryGetValue(path, out var legacyCategory)) return legacyCategory;
        var segments = path.Split('/', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        if (segments.Length == 0) return path;
        return KnownCategorySegments.GetValueOrDefault(segments[^1], path);
    }

    public static string? CachedImageUrl(string? sku) => NormalizeSku(sku) is { } normalized && ImageCache.TryGetValue(normalized, out var value) ? value : null;

    internal static void CacheImage(string sku, string? imageUrl)
    {
        if (NormalizeSku(sku) is { } normalized && TrustedImageUrl(imageUrl) is { } trusted)
        {
            ImageCache[normalized] = trusted;
            if (ImageCache.Count > 64)
                foreach (var key in ImageCache.Keys.Take(ImageCache.Count - 64)) ImageCache.TryRemove(key, out _);
        }
    }

    public static IReadOnlyList<LcscProductMetadata> ParseChinaSearchPage(string html)
    {
        var match = NextDataPattern().Match(html);
        if (!match.Success)
        {
            if (LooksLikeVerificationPage(html)) throw new LcscDomesticBlockedException();
            throw new InvalidDataException("国内商城页面缺少 __NEXT_DATA__ 搜索数据。");
        }
        try
        {
            using var document = JsonDocument.Parse(match.Groups[1].Value, new JsonDocumentOptions { MaxDepth = 64 });
            if (!TryProperty(document.RootElement, out var records, "props", "pageProps", "soData", "searchResult", "productRecordList") || records.ValueKind != JsonValueKind.Array)
                throw new InvalidDataException("国内商城页面不包含预期的搜索结果结构。");
            return records.EnumerateArray().Select(ParseChinaRecord).Where(item => item is not null).Cast<LcscProductMetadata>().Take(20).ToArray();
        }
        catch (JsonException exception) { throw new InvalidDataException("国内商城搜索数据不是有效 JSON。", exception); }
    }

    public static LcscProductMetadata? ExactChinaMatch(string sku, IEnumerable<LcscProductMetadata> products)
    {
        var expected = NormalizeSku(sku);
        return expected is null ? null : products.FirstOrDefault(item => item.Sku.Equals(expected, StringComparison.OrdinalIgnoreCase));
    }

    private static LcscProductMetadata? ParseChinaRecord(JsonElement record)
    {
        if (!record.TryGetProperty("productVO", out var product) || product.ValueKind != JsonValueKind.Object) return null;
        var sku = NormalizeSku(Text(product, "productCode"));
        var productId = Text(product, "productId");
        if (sku is null || productId is null || !productId.All(char.IsAsciiDigit)) return null;
        var parameters = StringMap(record, "paramLinkedMap");
        var model = CleanText(Text(record, "lightProductModel")) ?? CleanText(Text(product, "productModel"));
        var categoryPath = CleanText(Text(record, "lightCatalogName")) ?? CleanText(Text(product, "productType"));
        var description = CleanText(Text(record, "lightProductName")) ?? CleanText(Text(product, "productName"));
        var name = model ?? description ?? sku;
        var brand = CleanText(Text(record, "lightBrandName")) ?? CleanText(Text(product, "productGradePlateName"));
        var packageName = CleanText(Text(record, "lightStandard")) ?? CleanText(Text(product, "encapsulationModel"))
            ?? parameters.FirstOrDefault(pair => pair.Key.Contains("封装", StringComparison.Ordinal) || pair.Key.Equals("Package", StringComparison.OrdinalIgnoreCase)).Value;
        return new(sku, name, model, brand, packageName, NormalizeCategory(categoryPath), categoryPath,
            $"https://item.szlcsc.com/{productId}.html", TrustedImageUrl(Text(product, "breviaryImageUrl")), ProductDatasheet(product), parameters,
            description is not null && !description.Equals(name, StringComparison.OrdinalIgnoreCase) ? description : null);
    }

    private static string? ProductDatasheet(JsonElement product)
    {
        if (!product.TryGetProperty("fileTypeVOList", out var groups) || groups.ValueKind != JsonValueKind.Array) return null;
        foreach (var group in groups.EnumerateArray())
        {
            if (!group.TryGetProperty("detailVOList", out var details) || details.ValueKind != JsonValueKind.Array) continue;
            foreach (var detail in details.EnumerateArray())
            {
                var raw = Text(detail, "fileUrl");
                if (raw is null) continue;
                var candidate = raw.StartsWith("https://", StringComparison.OrdinalIgnoreCase) ? raw : $"https://atta.szlcsc.com/{raw.TrimStart('/')}";
                if (TrustedDatasheetUrl(candidate) is { } trusted) return trusted;
            }
        }
        return null;
    }

    private static Dictionary<string, string> StringMap(JsonElement node, string name)
    {
        var output = new Dictionary<string, string>(StringComparer.Ordinal);
        if (!node.TryGetProperty(name, out var map) || map.ValueKind != JsonValueKind.Object) return output;
        foreach (var property in map.EnumerateObject())
        {
            var key = CleanText(property.Name);
            var value = CleanText(property.Value.ValueKind == JsonValueKind.String ? property.Value.GetString() : property.Value.ToString());
            if (key is not null && value is not null) output[key] = value;
        }
        return output;
    }

    private static bool TryProperty(JsonElement node, out JsonElement value, params string[] path)
    {
        value = node;
        foreach (var name in path)
            if (value.ValueKind != JsonValueKind.Object || !value.TryGetProperty(name, out value)) return false;
        return true;
    }

    private static bool LooksLikeVerificationPage(string html) => new[] { "_xvasu", "_xvtsc", "_xvpfs", "_xvpts", "Security verification" }
        .Any(token => html.Contains(token, StringComparison.OrdinalIgnoreCase));

    private static string? CleanText(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        var cleaned = WhitespacePattern().Replace(WebUtility.HtmlDecode(HtmlTagPattern().Replace(value, " ")).Replace('\u00a0', ' '), " ").Trim();
        return cleaned.Length == 0 ? null : cleaned;
    }

    public static LcscProductMetadata? ParsePage(string sku, string html)
    {
        var expected = NormalizeSku(sku);
        if (expected is null) return null;
        foreach (Match match in JsonLdPattern().Matches(html))
        {
            try
            {
                using var document = JsonDocument.Parse(WebUtility.HtmlDecode(match.Groups[1].Value), new JsonDocumentOptions { MaxDepth = 32 });
                foreach (var product in Products(document.RootElement))
                {
                    if (NormalizeSku(Text(product, "sku")) != expected) continue;
                    var model = Text(product, "mpn");
                    var description = Text(product, "description") ?? Text(product, "name");
                    var name = model ?? Text(product, "name") ?? expected;
                    var brand = product.TryGetProperty("brand", out var brandNode) && brandNode.ValueKind == JsonValueKind.Object ? Text(brandNode, "name") : Text(product, "brand");
                    var categoryPath = Text(product, "category");
                    var parameters = AdditionalProperties(product);
                    return new(expected, name, model, brand, AdditionalPackage(product), NormalizeCategory(categoryPath), categoryPath, ProductUri(expected)!.AbsoluteUri, ProductImage(product),
                        Parameters: parameters, Description: description is not null && !description.Equals(name, StringComparison.OrdinalIgnoreCase) ? description : null,
                        ReferencePrice: ReferencePriceSnapshot.FromProduct(product, ProductUri(expected)!.AbsoluteUri));
                }
            }
            catch (JsonException) { }
        }
        return null;
    }

    private static IEnumerable<JsonElement> Products(JsonElement node)
    {
        if (node.ValueKind == JsonValueKind.Array)
            foreach (var item in node.EnumerateArray()) foreach (var product in Products(item)) yield return product;
        if (node.ValueKind != JsonValueKind.Object) yield break;
        if (IsProduct(node)) yield return node;
        if (node.TryGetProperty("@graph", out var graph)) foreach (var product in Products(graph)) yield return product;
    }

    private static bool IsProduct(JsonElement node)
    {
        if (!node.TryGetProperty("@type", out var type)) return false;
        return type.ValueKind == JsonValueKind.String && type.GetString() == "Product"
            || type.ValueKind == JsonValueKind.Array && type.EnumerateArray().Any(item => item.GetString() == "Product");
    }

    private static string? AdditionalPackage(JsonElement product)
    {
        if (!product.TryGetProperty("additionalProperty", out var properties) || properties.ValueKind != JsonValueKind.Array) return null;
        foreach (var property in properties.EnumerateArray())
            if (Text(property, "name")?.Equals("Package", StringComparison.OrdinalIgnoreCase) == true) return Text(property, "value");
        return null;
    }

    private static IReadOnlyDictionary<string, string> AdditionalProperties(JsonElement product)
    {
        var result = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (!product.TryGetProperty("additionalProperty", out var properties) || properties.ValueKind != JsonValueKind.Array) return result;
        foreach (var property in properties.EnumerateArray())
        {
            var key = CleanText(Text(property, "name"));
            var value = CleanText(Text(property, "value"));
            if (key is not null && value is not null) result[key] = value;
        }
        return result;
    }

    private static string? ProductImage(JsonElement product)
    {
        if (!product.TryGetProperty("image", out var image)) return null;
        if (image.ValueKind == JsonValueKind.String) return TrustedImageUrl(image.GetString());
        if (image.ValueKind == JsonValueKind.Array)
            foreach (var item in image.EnumerateArray()) { var found = item.ValueKind == JsonValueKind.String ? TrustedImageUrl(item.GetString()) : ProductImageObject(item); if (found is not null) return found; }
        return ProductImageObject(image);
    }

    private static string? ProductImageObject(JsonElement image) => image.ValueKind == JsonValueKind.Object ? TrustedImageUrl(Text(image, "contentUrl") ?? Text(image, "url")) : null;
    private static string? Text(JsonElement node, string name)
    {
        if (!node.TryGetProperty(name, out var value)) return null;
        return value.ValueKind switch { JsonValueKind.String => value.GetString()?.Trim(), JsonValueKind.Number => value.GetRawText(), _ => null };
    }

    [GeneratedRegex("^C[0-9]{1,10}$", RegexOptions.CultureInvariant)] private static partial Regex SkuPattern();
    [GeneratedRegex("<script\\b[^>]*\\btype\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script\\s*>", RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.CultureInvariant)] private static partial Regex JsonLdPattern();
    [GeneratedRegex("<script\\b[^>]*\\bid\\s*=\\s*[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script\\s*>", RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.CultureInvariant)] private static partial Regex NextDataPattern();
    [GeneratedRegex("<[^>]+>", RegexOptions.CultureInvariant)] private static partial Regex HtmlTagPattern();
    [GeneratedRegex("\\s+", RegexOptions.CultureInvariant)] private static partial Regex WhitespacePattern();
}

public sealed class LcscPublicLookup
{
    private const int MaxPageBytes = 4 * 1024 * 1024;
    private readonly HttpClient _client;
    private readonly Func<Uri, DiagnosticEvent, CancellationToken, Task<string>> _getPage;
    private readonly Func<System.Globalization.CultureInfo> _culture;
    private readonly LcscDomesticCooldownGate _domesticGate;
    private static readonly LcscDomesticCooldownGate SessionDomesticGate = new();
    private static readonly ConcurrentDictionary<string, (DateTimeOffset At, LcscProductMetadata? Value)> ExactCache = new();

    public LcscPublicLookup() : this(null, null) { }

    public LcscPublicLookup(
        Func<Uri, DiagnosticEvent, CancellationToken, Task<string>>? getPage,
        Func<System.Globalization.CultureInfo>? culture,
        Func<DateTimeOffset>? now = null)
    {
        _client = new HttpClient(new HttpClientHandler { AllowAutoRedirect = true }) { Timeout = TimeSpan.FromSeconds(8) };
        _client.DefaultRequestHeaders.UserAgent.ParseAdd("ComponentVault-Windows/0.7");
        _client.DefaultRequestHeaders.AcceptLanguage.ParseAdd("zh-CN,zh;q=0.9");
        _getPage = getPage ?? GetPageAsync;
        _culture = culture ?? (() => System.Globalization.CultureInfo.CurrentUICulture);
        _domesticGate = getPage is null && now is null ? SessionDomesticGate : new LcscDomesticCooldownGate(now);
    }

    public static void ClearDomesticCooldown() => SessionDomesticGate.Clear();

    public void RetryDomesticNow() => _domesticGate.Clear();

    public async Task<IReadOnlyList<LcscProductMetadata>> SearchChinaAsync(string keyword, CancellationToken cancellationToken = default)
    {
        return (await SearchPreferredAsync(keyword, cancellationToken)).Items;
    }

    private async Task<IReadOnlyList<LcscProductMetadata>> LegacySearchChinaAsync(string keyword, CancellationToken cancellationToken)
    {
        if (_domesticGate.Current() is { } coolingReason) throw new LcscDomesticCoolingDownException(coolingReason);
        var uri = LcscPublicCatalog.ChinaSearchUri(keyword) ?? throw new ArgumentException("Search keyword is empty.", nameof(keyword));
        AppDiagnostics.Record(DiagnosticEvent.CatalogDomestic,DiagnosticOutcome.Started);
        IReadOnlyList<LcscProductMetadata> results;
        try { results=LcscPublicCatalog.ParseChinaSearchPage(await _getPage(uri,DiagnosticEvent.CatalogDomestic,cancellationToken)); }
        catch(LcscDomesticBlockedException exception){_domesticGate.Record(exception);AppDiagnostics.Record(DiagnosticEvent.CatalogDomestic,DiagnosticOutcome.Blocked,exceptionType:exception.GetType());throw;}
        catch(LcscDomesticRateLimitedException exception){_domesticGate.Record(exception);throw;}
        catch(HttpRequestException){_domesticGate.RecordNetwork();throw;}
        catch(TaskCanceledException) when (!cancellationToken.IsCancellationRequested){_domesticGate.RecordNetwork();throw;}
        catch(InvalidDataException exception){AppDiagnostics.Record(DiagnosticEvent.CatalogDomestic,DiagnosticOutcome.Parser,exceptionType:exception.GetType());throw;}
        AppDiagnostics.Record(DiagnosticEvent.CatalogDomestic,DiagnosticOutcome.Success);
        foreach (var item in results) LcscPublicCatalog.CacheImage(item.Sku, item.ImageUrl);
        return results;
    }

    public async Task<LcscCatalogSearchResult> SearchPreferredAsync(string keyword, CancellationToken cancellationToken = default)
    {
        var preferred = LcscCatalogRoutePolicy.PreferredSource(_culture());
        var international = preferred == LcscCatalogSource.International;
        var items = international ? await SearchInternationalEdaAsync(keyword, cancellationToken) : await SearchEdaAsync(keyword, cancellationToken);
        var notice = international
            ? "Source: EasyEDA international catalog https://easyeda.com/"
            : "资料来源：嘉立创EDA国内库 / EasyEDA 官方库 https://lceda.cn/ · https://easyeda.com/";
        return new LcscCatalogSearchResult(items, preferred, notice);
    }

    public async Task<LcscProductMetadata?> LookupAsync(string sku, CancellationToken cancellationToken = default)
        => (await LookupDetailedAsync(sku, null, cancellationToken)).Metadata;

    public async Task<LcscCatalogLookupResult> LookupDetailedAsync(
        string sku,
        LcscCatalogSource? preferredSource = null,
        CancellationToken cancellationToken = default)
    {
        var normalized = LcscPublicCatalog.NormalizeSku(sku);
        var preferred = preferredSource == LcscCatalogSource.Domestic ? LcscCatalogSource.Eda : preferredSource ?? LcscCatalogRoutePolicy.PreferredSource(_culture());
        if (normalized is null) return new LcscCatalogLookupResult(null, preferred, null, []);
        var attempts = new List<LcscCatalogAttempt>();
        foreach (var source in LcscCatalogRoutePolicy.Order(preferred))
        {
            if (source == LcscCatalogSource.Domestic && _domesticGate.Current() is not null)
            {
                attempts.Add(new LcscCatalogAttempt(source, LcscCatalogFailureKind.CoolingDown));
                continue;
            }
            try
            {
                LcscProductMetadata? metadata = source == LcscCatalogSource.Domestic
                    ? LcscPublicCatalog.ExactChinaMatch(normalized, await SearchChinaAsync(normalized, cancellationToken))
                    : source == LcscCatalogSource.Eda
                        ? await LookupEdaAsync(normalized, cancellationToken)
                        : await LookupInternationalAsync(normalized, cancellationToken);
                if (metadata is not null)
                {
                    var result = new LcscCatalogLookupResult(metadata, preferred, source, attempts);
                    var annotated = metadata with
                    {
                        Source = source switch { LcscCatalogSource.Domestic => "lcsc_domestic_web", LcscCatalogSource.Eda => "lceda_public_library", _ => "lcsc_public_web" },
                        LookupNotice = result.UserMessage + (source == LcscCatalogSource.Eda ? " 资料来源：嘉立创EDA国内库 / EasyEDA 官方库 https://lceda.cn/ · https://easyeda.com/" : ""),
                    };
                    return result with { Metadata = annotated };
                }
                attempts.Add(new LcscCatalogAttempt(source, LcscCatalogFailureKind.NoMatch));
            }
            catch (Exception exception) when (exception is LcscDomesticBlockedException or LcscDomesticRateLimitedException or LcscDomesticCoolingDownException or InvalidDataException or HttpRequestException or TaskCanceledException)
            {
                cancellationToken.ThrowIfCancellationRequested();
                attempts.Add(new LcscCatalogAttempt(source, LcscCatalogRoutePolicy.Classify(exception)));
            }
        }
        return new LcscCatalogLookupResult(null, preferred, null, attempts);
    }

    private async Task<IReadOnlyList<LcscProductMetadata>> SearchInternationalEdaAsync(string keyword, CancellationToken token)
    {
        if (string.IsNullOrWhiteSpace(keyword)) throw new ArgumentException("Search keyword is empty.", nameof(keyword));
        AppDiagnostics.Record(DiagnosticEvent.CatalogInternational, DiagnosticOutcome.Started);
        var items = LcscPublicCatalog.ParseInternationalEdaSearch(await _getPage(new Uri($"https://pro.easyeda.com/api/eda/product/search?keyword={Uri.EscapeDataString(keyword.Trim())}"), DiagnosticEvent.CatalogInternational, token));
        foreach (var item in items) LcscPublicCatalog.CacheImage(item.Sku, item.ImageUrl);
        AppDiagnostics.Record(DiagnosticEvent.CatalogInternational, DiagnosticOutcome.Success);
        return items;
    }

    public async Task<IReadOnlyList<LcscProductMetadata>> SearchEdaAsync(string keyword, CancellationToken token = default)
    {
        if (string.IsNullOrWhiteSpace(keyword)) throw new ArgumentException("Search keyword is empty.", nameof(keyword));
        AppDiagnostics.Record(DiagnosticEvent.CatalogEda, DiagnosticOutcome.Started);
        var items = LcscPublicCatalog.ParseEdaSearch(await _getPage(new Uri($"https://pro.lceda.cn/api/eda/product/search?keyword={Uri.EscapeDataString(keyword.Trim())}&currPage=1&pageSize=20"), DiagnosticEvent.CatalogEda, token));
        foreach (var item in items) LcscPublicCatalog.CacheImage(item.Sku, item.ImageUrl);
        AppDiagnostics.Record(DiagnosticEvent.CatalogEda, DiagnosticOutcome.Success);
        return items;
    }

    private async Task<LcscProductMetadata?> LookupEdaAsync(string sku, CancellationToken token)
    {
        var key = $"eda:{sku}";
        if (ExactCache.TryGetValue(key, out var cached) && DateTimeOffset.UtcNow - cached.At < (cached.Value is null ? TimeSpan.FromSeconds(30) : TimeSpan.FromDays(7))) return cached.Value;
        AppDiagnostics.Record(DiagnosticEvent.CatalogEda, DiagnosticOutcome.Started);
        var result = LcscPublicCatalog.ParseEda(sku, await _getPage(new Uri($"https://pro.lceda.cn/api/eda/product/search?keyword={sku}&currPage=1&pageSize=20"), DiagnosticEvent.CatalogEda, token));
        AppDiagnostics.Record(DiagnosticEvent.CatalogEda, result is null ? DiagnosticOutcome.Parser : DiagnosticOutcome.Success);
        LcscPublicCatalog.CacheImage(sku, result?.ImageUrl);
        ExactCache[key] = (DateTimeOffset.UtcNow, result);
        if (ExactCache.Count > 64) ExactCache.TryRemove(ExactCache.OrderBy(pair => pair.Value.At).First().Key, out _);
        return result;
    }

    private async Task<LcscProductMetadata?> LookupInternationalAsync(string normalized, CancellationToken cancellationToken)
    {
        var cacheKey = $"international:{normalized}";
        if (ExactCache.TryGetValue(cacheKey, out var cached) && DateTimeOffset.UtcNow - cached.At < (cached.Value is null ? TimeSpan.FromSeconds(30) : TimeSpan.FromDays(7))) return cached.Value;
        AppDiagnostics.Record(DiagnosticEvent.CatalogInternational,DiagnosticOutcome.Started);
        var uri = LcscPublicCatalog.ProductUri(normalized)!;
        LcscProductMetadata? metadata;
        try { metadata=LcscPublicCatalog.ParsePage(normalized,await _getPage(uri,DiagnosticEvent.CatalogInternational,cancellationToken)); }
        catch(InvalidDataException exception){AppDiagnostics.Record(DiagnosticEvent.CatalogInternational,DiagnosticOutcome.Parser,exceptionType:exception.GetType());throw;}
        AppDiagnostics.Record(DiagnosticEvent.CatalogInternational,metadata is null?DiagnosticOutcome.Parser:DiagnosticOutcome.Success);
        LcscPublicCatalog.CacheImage(normalized, metadata?.ImageUrl);
        ExactCache[cacheKey] = (DateTimeOffset.UtcNow, metadata);
        if (ExactCache.Count > 64) ExactCache.TryRemove(ExactCache.OrderBy(pair => pair.Value.At).First().Key, out _);
        return metadata;
    }

    private async Task<string> GetPageAsync(Uri uri,DiagnosticEvent activity,CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(8));
        HttpResponseMessage response;
        try { response=await _client.GetAsync(uri,HttpCompletionOption.ResponseHeadersRead,timeout.Token); }
        catch(TaskCanceledException exception){AppDiagnostics.Record(activity,DiagnosticOutcome.Timeout,exceptionType:exception.GetType());throw;}
        catch(HttpRequestException exception) when(exception.InnerException is System.Net.Sockets.SocketException){AppDiagnostics.Record(activity,DiagnosticOutcome.Dns,exceptionType:exception.GetType());throw;}
        catch(HttpRequestException exception) when(exception.InnerException is System.Security.Authentication.AuthenticationException){AppDiagnostics.Record(activity,DiagnosticOutcome.Tls,exceptionType:exception.GetType());throw;}
        catch(HttpRequestException exception){AppDiagnostics.Record(activity,DiagnosticOutcome.Failed,exceptionType:exception.GetType());throw;}
        using(response){if(!response.IsSuccessStatusCode){
        AppDiagnostics.Record(activity,DiagnosticOutcome.Http,(int)response.StatusCode);
        var errorBody = await ReadLimitedAsync(response.Content, 64 * 1024, timeout.Token);
        var domestic = uri.Host.Equals("so.szlcsc.com", StringComparison.OrdinalIgnoreCase);
        int? retryAfter = response.Headers.RetryAfter?.Delta is { } delta
            ? (int)delta.TotalSeconds
            : response.Headers.RetryAfter?.Date is { } date
                ? (int)Math.Ceiling((date - DateTimeOffset.UtcNow).TotalSeconds)
                : null;
        var failure = domestic ? LcscCatalogRoutePolicy.ClassifyDomesticHttpResponse((int)response.StatusCode, errorBody, retryAfter) : null;
        if (failure?.Kind == LcscCatalogFailureKind.Blocked) throw new LcscDomesticBlockedException();
        if (failure?.Kind == LcscCatalogFailureKind.RateLimited) throw new LcscDomesticRateLimitedException(failure.RetryAfterSeconds);
        response.EnsureSuccessStatusCode();}
        if (response.Content.Headers.ContentLength is > MaxPageBytes) throw new InvalidDataException("商品页面超过 4 MiB 限制。");
        using var body = await response.Content.ReadAsStreamAsync(timeout.Token);
        using var output = new MemoryStream();
        var buffer = new byte[8192];
        int count;
        while ((count = await body.ReadAsync(buffer.AsMemory(), timeout.Token)) > 0)
        {
            if (output.Length + count > MaxPageBytes) throw new InvalidDataException("商品页面超过 4 MiB 限制。");
            output.Write(buffer, 0, count);
        }
        return Encoding.UTF8.GetString(output.ToArray());}
    }

    private static async Task<string> ReadLimitedAsync(HttpContent content, int limit, CancellationToken token)
    {
        using var input = await content.ReadAsStreamAsync(token); using var output = new MemoryStream(); var buffer = new byte[4096];
        int count; while ((count = await input.ReadAsync(buffer.AsMemory(0, Math.Min(buffer.Length, limit - (int)output.Length)), token)) > 0) { output.Write(buffer, 0, count); if (output.Length >= limit) break; }
        return Encoding.UTF8.GetString(output.ToArray());
    }
}
