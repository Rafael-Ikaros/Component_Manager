using System.Globalization;

namespace ComponentVault.WinUI.Services.Catalog;

public enum LcscCatalogSource { Domestic, International, Eda }
public enum LcscCatalogFailureKind { Blocked, RateLimited, CoolingDown, Unreachable, NoMatch, InvalidResponse }
public enum LcscDomesticGateReason { Blocked, Network, RateLimited }
public sealed record LcscDomesticHttpFailure(LcscCatalogFailureKind Kind, int Status, int? RetryAfterSeconds = null);

public sealed class LcscDomesticCooldownGate(Func<DateTimeOffset>? now = null)
{
    private readonly Func<DateTimeOffset> _now = now ?? (() => DateTimeOffset.UtcNow);
    private readonly object _sync = new();
    private DateTimeOffset _until; private LcscDomesticGateReason? _reason;
    public LcscDomesticGateReason? Current() { lock (_sync) { if (_reason is not null && _now() < _until) return _reason; _reason = null; _until = default; return null; } }
    public void Record(Exception error)
    {
        if (error is OperationCanceledException) return;
        var seconds = error switch { LcscDomesticBlockedException => 120, LcscDomesticRateLimitedException rate => Math.Clamp(rate.RetryAfterSeconds ?? 30, 1, 600), _ => 30 };
        lock (_sync) { _reason = error switch { LcscDomesticBlockedException => LcscDomesticGateReason.Blocked, LcscDomesticRateLimitedException => LcscDomesticGateReason.RateLimited, _ => LcscDomesticGateReason.Network }; _until = _now().AddSeconds(seconds); }
    }
    public void RecordNetwork() { lock (_sync) { _reason = LcscDomesticGateReason.Network; _until = _now().AddSeconds(30); } }
    public void Clear() { lock (_sync) { _reason = null; _until = default; } }
}

public sealed record LcscCatalogAttempt(LcscCatalogSource Source, LcscCatalogFailureKind Failure);

public sealed record LcscCatalogLookupResult(
    LcscProductMetadata? Metadata,
    LcscCatalogSource PreferredSource,
    LcscCatalogSource? ResolvedSource,
    IReadOnlyList<LcscCatalogAttempt> Attempts)
{
    public bool UsedFallback => ResolvedSource is not null && ResolvedSource != PreferredSource;

    public string UserMessage
    {
        get
        {
            var english = PreferredSource == LcscCatalogSource.International;
            string Source(LcscCatalogSource source) => source == LcscCatalogSource.Domestic
                ? (english ? "LCSC China" : "国内立创") : source == LcscCatalogSource.Eda ? "嘉立创EDA国内库" : (english ? "international LCSC" : "国际 LCSC");
            string Failure(LcscCatalogFailureKind failure) => failure switch
            {
                LcscCatalogFailureKind.Blocked => english ? "site verification blocked the request" : "站点验证阻断",
                LcscCatalogFailureKind.RateLimited => english ? "site rate limit" : "站点限流",
                LcscCatalogFailureKind.CoolingDown => english ? "domestic lookup cooling down" : "国内查询冷却中",
                LcscCatalogFailureKind.Unreachable => english ? "network or site unavailable" : "网络或站点不可达",
                LcscCatalogFailureKind.NoMatch => english ? "no exact match" : "未找到精确匹配",
                _ => english ? "unexpected site response" : "站点响应格式异常",
            };
            if (ResolvedSource is { } resolved && !UsedFallback)
                return english ? $"Exact metadata loaded from {Source(resolved)}." : $"已从{Source(resolved)}取得精确资料。";
            if (ResolvedSource is { } fallback)
                return english
                    ? $"Preferred source {Source(PreferredSource)} failed ({Failure(Attempts[0].Failure)}); using {Source(fallback)}."
                    : $"首选{Source(PreferredSource)}查询失败（{Failure(Attempts[0].Failure)}），已改用{Source(fallback)}。";
            return string.Join(english ? "; " : "；", Attempts.Select(x => $"{Source(x.Source)}{(english ? ": " : "：")}{Failure(x.Failure)}"));
        }
    }
}

public sealed record LcscCatalogSearchResult(
    IReadOnlyList<LcscProductMetadata> Items,
    LcscCatalogSource Source,
    string? Notice);

public static class LcscCatalogRoutePolicy
{
    public static LcscDomesticHttpFailure? ClassifyDomesticHttpResponse(int status, string body, int? retryAfterSeconds)
    {
        if (status is >= 200 and <= 299) return null;
        var challenge = status is 403 or 429 or 503 && new[] { "_xvasu", "_xvtsc", "_xvpfs", "_xvpts", "Security verification" }
            .Any(token => body.Contains(token, StringComparison.OrdinalIgnoreCase));
        if (challenge) return new(LcscCatalogFailureKind.Blocked, status);
        if (status == 429) return new(LcscCatalogFailureKind.RateLimited, status, Math.Clamp(retryAfterSeconds ?? 30, 1, 600));
        return new(LcscCatalogFailureKind.Unreachable, status);
    }
    public static LcscCatalogSource PreferredSource(CultureInfo culture) =>
        culture.TwoLetterISOLanguageName.Equals("zh", StringComparison.OrdinalIgnoreCase)
            ? LcscCatalogSource.Eda : LcscCatalogSource.International;

    public static IReadOnlyList<LcscCatalogSource> Order(LcscCatalogSource preferred) =>
        preferred != LcscCatalogSource.International
            ? [LcscCatalogSource.Eda]
            : [LcscCatalogSource.International];

    public static LcscCatalogFailureKind Classify(Exception exception) => exception switch
    {
        LcscDomesticBlockedException => LcscCatalogFailureKind.Blocked,
        LcscDomesticRateLimitedException => LcscCatalogFailureKind.RateLimited,
        LcscDomesticCoolingDownException => LcscCatalogFailureKind.CoolingDown,
        InvalidDataException => LcscCatalogFailureKind.InvalidResponse,
        HttpRequestException or TaskCanceledException => LcscCatalogFailureKind.Unreachable,
        _ => LcscCatalogFailureKind.InvalidResponse,
    };
}
