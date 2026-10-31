using ComponentVault.WinUI.Services.Catalog;
using Xunit;

namespace ComponentVault.WinUI.CoreTests;
public sealed class CatalogSessionPriceTests
{
    [Theory]
    [InlineData("C70565", "X322512MOB4SI", "0.5803", 5)]
    [InlineData("C393939", "TYPE-C16PIN", "0.4218", 10)]
    public void DomesticFixtureMatchesActualSchema(string sku, string model, string? expectedPrice, int quantity)
    {
        var json = File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Fixtures", "Catalog", sku + ".json"));
        var metadata = LcscPublicCatalog.ParseEda(sku, json)!;
        Assert.NotNull(metadata);
        Assert.Equal(sku, metadata.Sku);
        Assert.Equal(model, metadata.Model);
        {
            Assert.Equal("CNY", metadata.ReferencePrice!.Currency);
            Assert.Equal(expectedPrice, metadata.ReferencePrice.UnitPrice);
            Assert.Equal(quantity, metadata.ReferencePrice.MinimumQuantity);
            Assert.StartsWith("https://alimg.szlcsc.com/", metadata.ImageUrl!);
            Assert.StartsWith("https://atta.szlcsc.com/", metadata.DatasheetUrl!);
        }
        Assert.DoesNotContain(metadata.Parameters!.Keys, key => key == "Symbol" || key.StartsWith("3D"));
        Assert.Equal("lceda_public_library", metadata.Source);
        Assert.Null(LcscPublicCatalog.ParseEda("C705", json));
    }

    [Theory]
    [InlineData("AggregateOffer")]
    [InlineData("https://schema.org/AggregateOffer")]
    public void AggregateOfferIsNeverAUnitPrice(string type)
    {
        var html = "<script type=\"application/ld+json\">{\"@type\":\"Product\",\"sku\":\"C77231\",\"offers\":{\"@type\":\"" + type + "\",\"price\":\"0.123\",\"priceCurrency\":\"USD\"}}</script>";
        Assert.Null(LcscPublicCatalog.ParsePage("C77231", html)!.ReferencePrice);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(500)]
    public void DomesticErrorCodeCannotBeSuccessfulMetadata(int code)
    {
        var json = "{\"code\":" + code + ",\"result\":{\"productList\":[{\"code\":\"C123\",\"model\":\"MODEL\"}]}}";
        Assert.Throws<InvalidDataException>(() => LcscPublicCatalog.ParseEda("C123", json));
        Assert.Throws<InvalidDataException>(() => LcscPublicCatalog.ParseEdaSearch(json));
    }

    [Fact]
    public void HiddenDomesticPriceDoesNotPreventMetadataSuccess()
    {
        const string json = """{"code":200,"result":{"productList":[{"code":"C123","model":"MODEL","isShowPrice":false,"priceList":[{"startNumber":5,"price":0.25}]}]}}""";
        var item = LcscPublicCatalog.ParseEda("C123", json)!;
        Assert.Equal("MODEL", item.Model);
        Assert.Null(item.ReferencePrice);
        Assert.Null(Assert.Single(LcscPublicCatalog.ParseEdaSearch(json)).ReferencePrice);
    }

    [Fact]
    public void PriceDisplayUsesEnglishLabelsWithoutChangingStoredPrefixes()
    {
        var before = System.Globalization.CultureInfo.CurrentUICulture;
        try
        {
            System.Globalization.CultureInfo.CurrentUICulture = System.Globalization.CultureInfo.GetCultureInfo("en-US");
            var quote = ReferencePriceSnapshot.Create("0.1", "CNY", null, "https://so.szlcsc.com/", "2026-11-01T00:00:00Z")!;
            Assert.Contains("Applicable quantity: Not provided", quote.Display);
            Assert.Contains("Queried at (UTC):", quote.Display);
            Assert.Contains("参考单价：", ReferencePriceSnapshot.Merge("", quote));
        }
        finally { System.Globalization.CultureInfo.CurrentUICulture = before; }
    }

    [Fact]
    public void InternationalKeywordSchemaKeepsItsOwnSourceAndDoesNotGuessCurrency()
    {
        const string json = """{"code":0,"result":{"productList":[{"number":"C123","mpn":"INTL","manufacturer":"brand","package":"0603","price":[[1,"0.5"]],"image":[{"224x224":"https://assets.lcsc.com/a.jpg"}]}]}}""";
        var item = Assert.Single(LcscPublicCatalog.ParseInternationalEdaSearch(json));
        Assert.Equal("C123", item.Sku);
        Assert.Equal("INTL", item.Model);
        Assert.Equal("easyeda_public_library", item.Source);
        Assert.Null(item.ReferencePrice);
    }

    [Fact]
    public async Task ChineseEdaMissingQuoteNeverFetchesInternational()
    {
        var calls = new List<string>();
        var lookup = new LcscPublicLookup((uri, _, _) =>
        {
            calls.Add(uri.Host);
            return Task.FromResult(uri.Host == "pro.lceda.cn"
                ? "{\"code\":200,\"result\":{\"productList\":[{\"code\":\"C773913\",\"model\":\"EDA-MODEL\",\"standard\":\"0603\",\"device_info\":{\"tags\":{\"child_tag\":{\"name_cn\":\"贴片电阻\"}}}}]}}"
                : "<script type=\"application/ld+json\">{\"@type\":\"Product\",\"sku\":\"C773913\",\"mpn\":\"EN-MODEL\",\"offers\":{\"price\":\"0.12\",\"priceCurrency\":\"USD\"}}</script>");
        }, () => System.Globalization.CultureInfo.GetCultureInfo("zh-CN"));
        var result = await lookup.LookupDetailedAsync("C773913");
        Assert.Equal(LcscCatalogSource.Eda, result.ResolvedSource);
        Assert.Equal("EDA-MODEL", result.Metadata!.Model);
        Assert.Equal("贴片电阻", result.Metadata.Category);
        Assert.Null(result.Metadata.ReferencePrice);
        Assert.Equal(new[] { "pro.lceda.cn" }, calls);
    }

    [Fact]
    public void EdaRequiresExactCodeAndFiltersDesignFieldsWithoutGuessingPrice()
    {
        const string json = """{"code":200,"result":{"productList":[{"code":"C1234","mpn":"wrong"},{"code":"C123","model":"right","brandName":"brand","standard":"0603","price":[[1,"0.5"]],"device_info":{"attributes":{"Symbol":"uuid","3D Model":"uuid","Resistance":"10kΩ"},"tags":{"parent_tag":{"name_cn":"电阻"},"child_tag":{"name_cn":"贴片电阻"}}},"image":[{"224x224":"https://assets.lcsc.com/a.jpg"}]}]}}""";
        var metadata = LcscPublicCatalog.ParseEda("c123", json)!;
        Assert.Equal("right", metadata.Model);
        Assert.Equal("0603", metadata.PackageName);
        Assert.Equal("电阻/贴片电阻", metadata.CategoryPath);
        Assert.Equal("10kΩ", Assert.Single(metadata.Parameters!).Value);
        Assert.Null(metadata.ReferencePrice);
        Assert.Null(LcscPublicCatalog.ParseEda("C12", json));
    }
    [Fact]
    public void PriceRefreshReplacesOnlyPriceRowsAndMissingPriceKeepsSnapshot()
    {
        var old = ReferencePriceSnapshot.Create("0.10", "USD", 1, "https://www.lcsc.com/a", "2026-11-01T00:00:00Z")!;
        var fresh = ReferencePriceSnapshot.Create("0.20", "USD", null, "https://www.lcsc.com/b", "2026-11-01T01:00:00Z")!;
        var notes = ReferencePriceSnapshot.Merge("自由备注\n参数·阻值：10kΩ", old);
        var updated = ReferencePriceSnapshot.Merge(notes, fresh);
        Assert.Equal(fresh, ReferencePriceSnapshot.Read(updated));
        Assert.StartsWith("自由备注\n参数·阻值：10kΩ\n", updated);
        Assert.Single(updated.Split('\n'), x => x.StartsWith("参考单价："));
        Assert.Equal(updated, ReferencePriceSnapshot.Merge(updated, null));
    }
    [Theory]
    [InlineData("USD", "0.123", true)]
    [InlineData("", "0.123", false)]
    [InlineData("USD", "0", false)]
    public void InternationalPriceRequiresPositiveDecimalAndExplicitCurrency(string currency, string amount, bool valid)
    {
        var html = "<script type=\"application/ld+json\">{\"@type\":\"Product\",\"sku\":\"C77231\",\"offers\":{\"price\":\"" + amount + "\",\"priceCurrency\":\"" + currency + "\"}}</script>";
        Assert.Equal(valid, LcscPublicCatalog.ParsePage("C77231", html)!.ReferencePrice is not null);
    }
}
