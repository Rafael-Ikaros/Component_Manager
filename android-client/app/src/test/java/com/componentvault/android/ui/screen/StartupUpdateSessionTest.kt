package com.componentvault.android.ui.screen

import com.componentvault.android.data.GitHubReleaseParser
import com.componentvault.android.data.ReleaseCheckResult
import com.componentvault.android.data.UpdateChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StartupUpdateSessionTest {
    @Test
    fun promptsOnlyWhenPublishedVersionIsNewer() = runBlocking {
        for ((installedVersion, expectedTag) in listOf(
            "0.7.7" to "v0.7.8",
            "0.7.8" to null,
            "0.7.9" to null,
        )) {
            val session = StartupUpdateSession { installed, channel ->
                GitHubReleaseParser.compareWithInstalled(releaseJson(), installed, channel)
            }
            assertEquals(expectedTag, session.checkOnce(installedVersion, UpdateChannel.Stable)?.tagName)
        }
    }

    @Test
    fun usesTheSelectedStableOrDevChannel() = runBlocking {
        for (channel in UpdateChannel.entries) {
            var receivedChannel: UpdateChannel? = null
            val devRelease = releaseJson("v0.7.9-dev.1", prerelease = true)
            val session = StartupUpdateSession { installed, selectedChannel ->
                receivedChannel = selectedChannel
                val body = if (selectedChannel == UpdateChannel.Dev) {
                    JSONArray().put(JSONObject(devRelease)).toString()
                } else {
                    devRelease
                }
                GitHubReleaseParser.compareWithInstalled(body, installed, selectedChannel)
            }
            val release = session.checkOnce("0.7.8", channel)
            assertEquals(channel, receivedChannel)
            assertEquals(if (channel == UpdateChannel.Dev) "v0.7.9-dev.1" else null, release?.tagName)
        }
    }

    @Test
    fun unsuccessfulChecksRemainSilent() = runBlocking {
        val failures = listOf(
            ReleaseCheckResult.NetworkFailure,
            ReleaseCheckResult.RateLimited,
            ReleaseCheckResult.NoRelease,
            ReleaseCheckResult.InvalidReleaseLinks,
            ReleaseCheckResult.CannotDetermineVersion("unknown"),
        )
        for (failure in failures) {
            val session = StartupUpdateSession { _, _ -> failure }
            assertNull(session.checkOnce("0.7.8", UpdateChannel.Stable))
        }
        val session = StartupUpdateSession { _, _ -> error("Network unavailable") }
        assertNull(session.checkOnce("0.7.8", UpdateChannel.Stable))
    }

    @Test
    fun doesNotRepeatTheRequestWhileRunningOrAfterCompletion() = runBlocking {
        var requests = 0
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val session = StartupUpdateSession { installed, channel ->
            requests++
            started.complete(Unit)
            finish.await()
            GitHubReleaseParser.compareWithInstalled(releaseJson(), installed, channel)
        }
        val request = launch { session.checkOnce("0.7.7", UpdateChannel.Stable) }
        started.await()
        assertNull(session.checkOnce("0.7.7", UpdateChannel.Dev))
        finish.complete(Unit)
        request.join()
        assertNull(session.checkOnce("0.7.7", UpdateChannel.Stable))
        assertEquals(1, requests)
    }

    @Test
    fun coroutineCancellationIsPreserved() = runBlocking {
        val session = StartupUpdateSession { _, _ -> throw CancellationException("Activity finished") }
        assertFailsWith<CancellationException> { session.checkOnce("0.7.8", UpdateChannel.Stable) }
        Unit
    }

    private fun releaseJson(tag: String = "v0.7.8", prerelease: Boolean = false): String = JSONObject().apply {
        put("tag_name", tag)
        put("draft", false)
        put("prerelease", prerelease)
        put("html_url", "https://github.com/Rafael-Ikaros/Component_Manager/releases/tag/$tag")
        put("published_at", "2026-10-28T08:00:00Z")
        put("body", "Release notes")
        put("assets", JSONArray())
    }.toString()
}
