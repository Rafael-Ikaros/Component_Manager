using ComponentVault.WinUI.Services.Catalog;
using Xunit;

namespace ComponentVault.WinUI.CoreTests;

public sealed class LcscPublicCatalogTests
{
    private const string InternationalRoutePage = """
        <script type="application/ld+json">{"@type":"Product","sku":"C88002","mpn":"EN-MODEL"}</script>
        """;
    private const string DomesticRoutePage = """
        <script id="__NEXT_DATA__">{"props":{"pageProps":{"soData":{"searchResult":{"productRecordList":[{
          "lightProductModel":"中文型号","productVO":{"productCode":"C88002","productId":"88002"}
        }]}}}}}</script>
        """;

    [Fact]
    public async Task LookupDetailedAsync_EnglishPrefersInternationalWithoutDomesticOverwrite()
    {
        var requestedHosts = new List<string>();
        var lookup = new LcscPublicLookup(
            (uri, _, _) => { requestedHosts.Add(uri.Host); return Task.FromResult(uri.Host == "www.lcsc.com" ? InternationalRoutePage : DomesticRoutePage); },
            () => System.Globalization.CultureInfo.GetCultureInfo("en-US"));

        var result = await lookup.LookupDetailedAsync("C88002");

        Assert.Equal(LcscCatalogSource.International, result.ResolvedSource);
        Assert.Equal("EN-MODEL", result.Metadata?.Model);
        Assert.Equal("lcsc_public_web", result.Metadata?.Source);
        Assert.Contains("international LCSC", result.Metadata!.LookupNotice!);
        Assert.Equal(["www.lcsc.com"], requestedHosts);
        Assert.False(result.UsedFallback);
    }

    [Fact]
    public async Task LookupDetailedAsync_ChineseEdaFailureDoesNotFallback()
    {
        var lookup = new LcscPublicLookup(
            (uri, _, _) => Task.FromResult(uri.Host == "so.szlcsc.com" ? "<script>var _xvasu='challenge';</script>" : InternationalRoutePage),
            () => System.Globalization.CultureInfo.GetCultureInfo("zh-CN"));

        var result = await lookup.LookupDetailedAsync("C88002");

        Assert.Null(result.ResolvedSource);
        Assert.Null(result.Metadata);
        Assert.False(result.UsedFallback);
        Assert.Equal(LcscCatalogSource.Eda, Assert.Single(result.Attempts).Source);
        Assert.Equal(LcscCatalogFailureKind.InvalidResponse, result.Attempts[0].Failure);
        Assert.Contains("站点响应格式异常", result.UserMessage);
        Assert.DoesNotContain("国际 LCSC", result.UserMessage);
    }

    [Fact]
    public async Task SearchPreferredAsync_UsesInternationalEdaForEnglishKeywords()
    {
        var lookup = new LcscPublicLookup((uri, _, _) => { Assert.Equal("pro.easyeda.com", uri.Host); return Task.FromResult("{\"code\":0,\"result\":{\"productList\":[]}}"); }, () => System.Globalization.CultureInfo.GetCultureInfo("en-US"));
        var result = await lookup.SearchPreferredAsync("resistor");
        Assert.Equal(LcscCatalogSource.International, result.Source);
        Assert.Contains("EDA", result.Notice!);
    }

    [Fact]
    public void ParsePage_RequiresExactProductSku()
    {
        const string html = """
            <script type="application/ld+json">
            {"@type":"Product","sku":"C999","description":"Wrong product","image":"https://assets.lcsc.com/a.jpg"}
            </script>
            """;

        Assert.Null(LcscPublicCatalog.ParsePage("C123", html));
    }

    [Fact]
    public void ParsePage_ReadsExactProductAndOfficialCategory()
    {
        const string html = """
            <script type="application/ld+json">
            {"@graph":[{"@type":"Product","sku":"C123","mpn":"RC0603","description":"10k resistor","brand":{"name":"Brand"},"category":"Resistors/Chip Resistor - Surface Mount","image":["https://evil.example/a.jpg",{"contentUrl":"https://assets.lcsc.com/a.jpg"}],"additionalProperty":[{"name":"Package","value":"0603"}]}]}
            </script>
            """;

        var result = LcscPublicCatalog.ParsePage("c123", html);

        Assert.NotNull(result);
        Assert.Equal("C123", result.Sku);
        Assert.Equal("RC0603", result.Name);
        Assert.Equal("10k resistor", result.Description);
        Assert.Equal("电阻", result.Category);
        Assert.Equal("0603", result.PackageName);
        Assert.Equal("https://assets.lcsc.com/a.jpg", result.ImageUrl);
    }

    [Theory]
    [InlineData("http://assets.lcsc.com/a.jpg")]
    [InlineData("https://assets.lcsc.com.evil.example/a.jpg")]
    [InlineData("https://user@assets.lcsc.com/a.jpg")]
    [InlineData("https://assets.lcsc.com:444/a.jpg")]
    public void TrustedImageUrl_RejectsUntrustedUrls(string url) =>
        Assert.Null(LcscPublicCatalog.TrustedImageUrl(url));

    [Fact]
    public void NormalizeCategory_PreservesOfficialChineseName()
    {
        Assert.Equal("贴片电阻", LcscPublicCatalog.NormalizeCategory("贴片电阻"));
    }

    [Theory]
    [InlineData("Integrated Circuits (ICs)/Power Management (PMIC)/Voltage Regulators - Linear", "线性稳压器")]
    [InlineData("Changed Parent/FERRITE BEADS AND CHIPS", "磁珠")]
    [InlineData("Power Management (PMIC)/Voltage Regulators - Linear, Low Drop Out (LDO) Regulators", "线性稳压器（LDO）")]
    [InlineData("Power Management ICs/LDO Regulators", "线性稳压器（LDO）")]
    [InlineData("User Parent/Custom Leaf", "User Parent/Custom Leaf")]
    public void NormalizeCategory_MapsOnlyKnownOfficialLeafNames(string path, string expected)
    {
        Assert.Equal(expected, LcscPublicCatalog.NormalizeCategory(path));
    }

    [Theory]
    [InlineData("Resistors/Chip Resistor - Surface Mount", "电阻")]
    [InlineData("LED Drivers/LED Drivers ICs", "LED驱动")]
    public void NormalizeCategory_PreservesLegacyMappings(string path, string expected)
    {
        Assert.Equal(expected, LcscPublicCatalog.NormalizeCategory(path));
    }

    [Fact]
    public void ParseChinaSearchPage_ReadsNextDataParametersAndActualProductId()
    {
        const string html = """
            <html><script id="__NEXT_DATA__" type="application/json">
            {"props":{"pageProps":{"soData":{"searchResult":{"productRecordList":[{
              "lightCatalogName":"电阻器 &amp; 电位器","lightProductName":"<b>贴片电阻</b> 10kΩ",
              "lightProductModel":"RC0603FR-0710KL","lightBrandName":"YAGEO","lightStandard":"0603",
              "paramLinkedMap":{"阻值":"10kΩ","精度":"±1%","封装":"0603"},
              "productVO":{"productCode":"c25804","productId":123456,"stockNumber":99999,
                "breviaryImageUrl":"https://img.szlcsc.com/a.jpg",
                "fileTypeVOList":[{"detailVOList":[{"fileUrl":"upload/public/pdf/a.pdf"}]}]}
            }]}}}}}
            </script></html>
            """;

        var result = Assert.Single(LcscPublicCatalog.ParseChinaSearchPage(html));

        Assert.Equal("C25804", result.Sku);
        Assert.Equal("RC0603FR-0710KL", result.Name);
        Assert.Equal("贴片电阻 10kΩ", result.Description);
        Assert.Equal("电阻器 & 电位器", result.Category);
        Assert.Equal("https://item.szlcsc.com/123456.html", result.OfficialUrl);
        Assert.Equal("https://atta.szlcsc.com/upload/public/pdf/a.pdf", result.DatasheetUrl);
        Assert.Equal("±1%", result.Parameters!["精度"]);
    }

    [Fact]
    public void ParsePage_RetainsOfficialAdditionalPropertiesForRclNaming()
    {
        const string html = """
            <script type="application/ld+json">
            {"@type":"Product","sku":"C25804","mpn":"RC0603FR-0710KL",
             "additionalProperty":[{"name":"Package","value":"0603"},
               {"name":"Resistance","value":"10kΩ"},{"name":"Tolerance","value":"±1%"}]}
            </script>
            """;

        var metadata = LcscPublicCatalog.ParsePage("C25804", html);

        Assert.NotNull(metadata);
        Assert.Equal("0603", metadata.PackageName);
        Assert.Equal("RC0603FR-0710KL", OfficialSpecifications.AutoName(metadata));
    }

    [Theory]
    [InlineData("C178304", "1206X107M6R3NT", "100uF ±20% 6.3V Ceramic Capacitor X5R 1206", "Capacitors/Ceramic Capacitors", "Capacitance", "100uF", "±20%")]
    [InlineData("C275673", "SLO252012F1R5MTT", "2.39A 1.5uH ±20% 78mΩ 3.17A Molded inductor 1008 Fixed Inductors", "Inductors, Coils, Chokes/Fixed Inductors", "Inductance", "1.5uH", "±20%")]
    [InlineData("C2907005", "FRC0603F2201TS", "2.2kΩ ±1% 100mW 0603 Thick Film Resistor", "Resistors/Chip Resistor - Surface Mount", "Resistance", "2.2kΩ", "±1%")]
    [InlineData("C5126214", "FRH0603B1002TS", "10kΩ ±0.1% 100mW 0603 Thick Film Resistor", "Resistors/Chip Resistor - Surface Mount", "Resistance", "10kΩ", "±0.1%")]
    [InlineData("C5137569", "FCC0402N220J500AT", "22pF ±5% 50V Ceramic Capacitor C0G 0402", "Capacitors/Ceramic Capacitors", "Capacitance", "22pF", "±5%")]
    public void ParsePage_ActualPublicRclValuesRemainSeparateFromName(
        string sku, string model, string description, string category, string key, string value, string tolerance)
    {
        var payload = System.Text.Json.JsonSerializer.Serialize(new Dictionary<string, object>
        {
            ["@context"] = "http://schema.org", ["@type"] = "Product", ["sku"] = sku,
            ["mpn"] = model, ["description"] = description, ["category"] = category,
            ["additionalProperty"] = new[]
            {
                new { name = key, value }, new { name = "Tolerance", value = tolerance },
            },
        });

        var metadata = LcscPublicCatalog.ParsePage(sku, $"<script type=\"application/ld+json\">{payload}</script>");

        Assert.NotNull(metadata);
        Assert.Equal(value, metadata.Parameters![key]);
        Assert.Equal(model, OfficialSpecifications.AutoName(metadata));
        var notes = $"官方描述：{metadata.Description}\n参数·{key}：{value}\n参数·Tolerance：{tolerance}";
        Assert.Contains(value, OfficialSpecifications.FromDescription(notes, metadata.Category));
    }

    [Fact]
    public void ParseChinaSearchPage_PreservesJsonEscapingBeforeCleaningFields()
    {
        const string html = """
            <script id="__NEXT_DATA__">{"props":{"pageProps":{"soData":{"searchResult":{"productRecordList":[{
              "lightProductName":"Comparator &quot;precision&quot;","productVO":{"productCode":"C7","productId":"77"}
            }]}}}}}</script>
            """;
        var item = Assert.Single(LcscPublicCatalog.ParseChinaSearchPage(html));
        Assert.Equal("Comparator \"precision\"", item.Name);
    }

    [Fact]
    public void ParseChinaSearchPage_DistinguishesUnexpectedPageFromEmptyResults()
    {
        Assert.Throws<InvalidDataException>(() => LcscPublicCatalog.ParseChinaSearchPage("<html>ordinary page</html>"));
        const string empty = """<script id="__NEXT_DATA__">{"props":{"pageProps":{"soData":{"searchResult":{"productRecordList":[]}}}}}</script>""";
        Assert.Empty(LcscPublicCatalog.ParseChinaSearchPage(empty));
    }

    [Fact]
    public void ParseChinaSearchPage_RequiresRealProductIdAndValidCNumber()
    {
        const string html = """
            <script id="__NEXT_DATA__">{"props":{"pageProps":{"soData":{"searchResult":{"productRecordList":[
              {"productVO":{"productCode":"C123","productId":"C123","productName":"wrong id"}},
              {"productVO":{"productCode":"SKU-1","productId":"456","productName":"wrong sku"}}
            ]}}}}}</script>
            """;

        Assert.Empty(LcscPublicCatalog.ParseChinaSearchPage(html));
    }

    [Fact]
    public void ExactChinaMatch_DoesNotAcceptApproximateSearchResult()
    {
        var products = new[]
        {
            new LcscProductMetadata("C1234", "approximate", null, null, null, "电阻", null, "https://item.szlcsc.com/1.html", null),
            new LcscProductMetadata("C123", "exact", null, null, null, "电阻", null, "https://item.szlcsc.com/2.html", null),
        };

        Assert.Equal("exact", LcscPublicCatalog.ExactChinaMatch("c123", products)?.Name);
    }

    [Fact]
    public void ParseChinaSearchPage_ReportsVerificationInsteadOfParsingChallenge()
    {
        Assert.Throws<LcscDomesticBlockedException>(() =>
            LcscPublicCatalog.ParseChinaSearchPage("<script>var _xvasu='token';</script>"));
    }

    [Fact]
    public void ParseChinaSearchPage_ValidProductWinsOverSecurityWords()
    {
        Assert.Single(LcscPublicCatalog.ParseChinaSearchPage(DomesticRoutePage.Replace("中文型号", "Security verification component")));
    }

    [Fact]
    public void DomesticCooldown_IsBoundedAndCanBeCleared()
    {
        var now = DateTimeOffset.UnixEpoch;
        var gate = new LcscDomesticCooldownGate(() => now);
        gate.Record(new LcscDomesticBlockedException());
        Assert.Equal(LcscDomesticGateReason.Blocked, gate.Current());
        now = now.AddSeconds(120);
        Assert.Null(gate.Current());
        gate.Record(new LcscDomesticRateLimitedException(999));
        now = now.AddSeconds(599);
        Assert.Equal(LcscDomesticGateReason.RateLimited, gate.Current());
        gate.Clear();
        Assert.Null(gate.Current());
        gate.Record(new OperationCanceledException());
        Assert.Null(gate.Current());
    }

    [Fact]
    public void DomesticHttpClassification_Covers203ChallengePlain403And429()
    {
        Assert.Null(LcscCatalogRoutePolicy.ClassifyDomesticHttpResponse(203, "normal", null));
        Assert.Equal(LcscCatalogFailureKind.Blocked, LcscCatalogRoutePolicy.ClassifyDomesticHttpResponse(403, "_xvasu", null)!.Kind);
        Assert.Equal(LcscCatalogFailureKind.Unreachable, LcscCatalogRoutePolicy.ClassifyDomesticHttpResponse(403, "Forbidden", null)!.Kind);
        var limited = LcscCatalogRoutePolicy.ClassifyDomesticHttpResponse(429, "busy", 999)!;
        Assert.Equal(LcscCatalogFailureKind.RateLimited, limited.Kind);
        Assert.Equal(600, limited.RetryAfterSeconds);
    }

    [Fact]
    public async Task LookupDetailedAsync_NeverRequestsDomesticWafEndpoint()
    {
        var hosts = new List<string>();
        var lookup = new LcscPublicLookup((uri, _, _) => { hosts.Add(uri.Host); return Task.FromResult(uri.Host == "pro.lceda.cn" ? "{\"code\":200,\"result\":{\"productList\":[]}}" : "<html></html>"); }, () => System.Globalization.CultureInfo.GetCultureInfo("zh-CN"));
        await lookup.LookupDetailedAsync("C582276");
        Assert.Equal(new[] { "pro.lceda.cn" }, hosts);
    }

    [Theory]
    [InlineData("http://atta.szlcsc.com/a.pdf")]
    [InlineData("https://atta.szlcsc.com.evil.example/a.pdf")]
    [InlineData("https://user@atta.szlcsc.com/a.pdf")]
    public void TrustedDatasheetUrl_RejectsUnsafeUrls(string url) =>
        Assert.Null(LcscPublicCatalog.TrustedDatasheetUrl(url));
}
