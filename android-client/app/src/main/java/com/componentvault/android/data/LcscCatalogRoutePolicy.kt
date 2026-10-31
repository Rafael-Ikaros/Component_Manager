package com.componentvault.android.data

import com.componentvault.android.model.AppLanguage
import java.io.IOException
import java.util.Locale

internal enum class LcscCatalogSource(val wireName: String) {
    Domestic("lcsc_domestic_web"),
    International("lcsc_public_web"),
    Eda("lceda_public_library"),
}

internal enum class LcscCatalogFailureKind {
    Blocked,
    RateLimited,
    CoolingDown,
    Unreachable,
    NoMatch,
    InvalidResponse,
}

internal enum class LcscDomesticGateReason { Blocked, Network, RateLimited }
internal class LcscDomesticCoolingDownException(val reason: LcscDomesticGateReason) : IOException("LCSC domestic lookup cooling down")

internal class LcscDomesticCooldownGate(private val now: () -> Long = System::currentTimeMillis) {
    private var until = 0L
    private var reason: LcscDomesticGateReason? = null
    @Synchronized fun current(): LcscDomesticGateReason? = reason?.takeIf { now() < until }.also { if (it == null) { reason = null; until = 0 } }
    @Synchronized fun record(value: LcscDomesticGateReason, retryAfterSeconds: Long? = null) {
        val seconds = when (value) {
            LcscDomesticGateReason.Blocked -> 120L
            LcscDomesticGateReason.Network -> 30L
            LcscDomesticGateReason.RateLimited -> (retryAfterSeconds ?: 30L).coerceIn(1L, 600L)
        }
        reason = value; until = now() + seconds * 1000L
    }
    @Synchronized fun clear() { reason = null; until = 0 }
}
internal val LcscDomesticSessionGate = LcscDomesticCooldownGate()

internal sealed interface LcscDomesticHttpFailure {
    data object Blocked : LcscDomesticHttpFailure
    data class RateLimited(val retryAfterSeconds: Long?) : LcscDomesticHttpFailure
    data class Http(val status: Int) : LcscDomesticHttpFailure
}

internal fun classifyDomesticHttpResponse(status: Int, body: String, retryAfter: String?): LcscDomesticHttpFailure? {
    if (status in 200..299) return null
    val challenge = status in setOf(403, 429, 503) &&
        listOf("_xvasu", "_xvtsc", "_xvpfs", "_xvpts", "Security verification")
            .any { body.contains(it, ignoreCase = true) }
    if (challenge) return LcscDomesticHttpFailure.Blocked
    if (status == 429) return LcscDomesticHttpFailure.RateLimited(retryAfter?.toLongOrNull()?.coerceIn(1, 600))
    return LcscDomesticHttpFailure.Http(status)
}

internal data class LcscCatalogAttempt(
    val source: LcscCatalogSource,
    val failure: LcscCatalogFailureKind,
)

internal data class LcscCatalogLookupResult(
    val metadata: com.componentvault.android.model.ComponentOfficialMetadata?,
    val preferredSource: LcscCatalogSource,
    val resolvedSource: LcscCatalogSource?,
    val attempts: List<LcscCatalogAttempt>,
) {
    val usedFallback: Boolean get() = resolvedSource != null && resolvedSource != preferredSource
}

internal object LcscCatalogRoutePolicy {
    fun preferredSource(language: AppLanguage): LcscCatalogSource = when (language) {
        AppLanguage.ZhCn -> LcscCatalogSource.Eda
        AppLanguage.English -> LcscCatalogSource.International
    }

    fun preferredSource(locale: Locale): LcscCatalogSource =
        if (locale.language.equals("zh", ignoreCase = true)) {
            LcscCatalogSource.Eda
        } else {
            LcscCatalogSource.International
        }

    fun order(preferred: LcscCatalogSource): List<LcscCatalogSource> = when (preferred) {
        LcscCatalogSource.Domestic, LcscCatalogSource.Eda -> listOf(LcscCatalogSource.Eda)
        LcscCatalogSource.International -> listOf(LcscCatalogSource.International)
    }

    fun shouldPersist(result: LcscCatalogLookupResult): Boolean =
        result.metadata != null && result.resolvedSource == result.preferredSource

    fun classify(error: Throwable): LcscCatalogFailureKind = when (error) {
        is LcscDomesticBlockedException -> LcscCatalogFailureKind.Blocked
        is LcscDomesticRateLimitedException -> LcscCatalogFailureKind.RateLimited
        is LcscDomesticCoolingDownException -> LcscCatalogFailureKind.CoolingDown
        is LcscDomesticResponseException -> LcscCatalogFailureKind.InvalidResponse
        is IOException -> LcscCatalogFailureKind.Unreachable
        else -> LcscCatalogFailureKind.InvalidResponse
    }
}
