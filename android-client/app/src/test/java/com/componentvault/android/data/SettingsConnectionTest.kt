package com.componentvault.android.data

import android.app.Application
import android.content.Context
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SettingsConnectionTest {
    @Test
    fun externalDraftIsUsedWhenPrimaryIsOfflineWithoutChangingSavedSettings(): Unit = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        val repository = InventoryRepository(context)
        repository.saveSyncConfiguration("https://saved.example", "saved-token", true, "https://saved-external.example")
        val saved = repository.loadSyncConfiguration()
        val preferences = context.getSharedPreferences("component_vault_sync", Context.MODE_PRIVATE)
        preferences.edit().putLong("sync_cursor", 42L).commit()
        val offlinePort = ServerSocket(0).use { it.localPort }
        ServerSocket(0).use { server ->
            server.soTimeout = 10_000
            val responder = CompletableFuture.runAsync {
                repeat(2) { index -> server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val reader = socket.getInputStream().bufferedReader()
                    val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    assertEquals(if (index == 0) "POST /auth/ping HTTP/1.1" else "GET /auth/me HTTP/1.1", headers.first())
                    val body = if (index == 0) "{\"inventory_protocol\":1,\"cost_protocol\":1,\"server_time\":\"external\"}"
                        else "{\"server_id\":\"server-1\",\"account_id\":\"admin\"}"
                    val bytes = body.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                } }
            }
            val result = repository.testConnection("http://127.0.0.1:$offlinePort", "draft-token", "http://127.0.0.1:${server.localPort}")
            assertEquals(true, result.isSuccess)
            assertEquals(saved, repository.loadSyncConfiguration())
            assertEquals(42L, preferences.getLong("sync_cursor", -1L))
            assertNull(preferences.getString("bound_account_id", null))
            responder.get(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun successfulAndFailedDraftTestsNeverReplaceSavedConnectionOrCursor(): Unit = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        val repository = InventoryRepository(context)
        repository.saveSyncConfiguration("https://saved.example", "saved-token", true)
        val preferences = context.getSharedPreferences("component_vault_sync", Context.MODE_PRIVATE)
        preferences.edit().putLong("sync_cursor", 42L).commit()
        val saved = repository.loadSyncConfiguration()
        val authorization = AtomicReference<String>()
        val server = ServerSocket().apply {
            bind(InetSocketAddress("127.0.0.1", 0))
            soTimeout = 10_000
        }
        val responder = CompletableFuture.runAsync {
            repeat(3) { index ->
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val reader = socket.getInputStream().bufferedReader()
                    val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    assertEquals(if (index == 1) "GET /auth/me HTTP/1.1" else "POST /auth/ping HTTP/1.1", headers.first())
                    authorization.set(headers.first { it.startsWith("Authorization:", ignoreCase = true) }.substringAfter(':').trim())
                    val success = authorization.get() == "Bearer draft-token"
                    val body = when {
                        !success -> "{\"detail\":\"Invalid token\"}"
                        index == 1 -> "{\"server_id\":\"server-1\",\"account_id\":\"admin\"}"
                        else -> "{\"inventory_protocol\":1,\"cost_protocol\":1,\"server_time\":\"test\"}"
                    }
                    val bytes = body.toByteArray()
                    val status = if (success) "200 OK" else "401 Unauthorized"
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        try {
            val draftUrl = "http://127.0.0.1:${server.localPort}"
            assertEquals(true, repository.testConnection(" $draftUrl/ ", " draft-token ").isSuccess)
            assertEquals("Bearer draft-token", authorization.get())
            assertEquals(saved, repository.loadSyncConfiguration())
            assertEquals(42L, preferences.getLong("sync_cursor", -1L))
            assertNull(preferences.getString("bound_server_id", null))
            assertNull(preferences.getString("bound_account_id", null))
            assertEquals(false, repository.testConnection(draftUrl, "wrong-token").isSuccess)
            assertEquals(saved, repository.loadSyncConfiguration())
            assertEquals(42L, preferences.getLong("sync_cursor", -1L))
            responder.get(10, TimeUnit.SECONDS)
        } finally {
            server.close()
        }
    }

    @Test
    fun identityRouteMissingInvalidJsonAndMissingFieldsAreDistinct(): Unit = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        val repository = InventoryRepository(context)
        val cases = listOf(
            Triple("404 Not Found", "{\"detail\":\"Not Found\"}", "/auth/me"),
            Triple("200 OK", "<html>proxy page</html>", "JSON"),
            Triple("200 OK", "{\"server_id\":\"server-1\"}", "account_id"),
        )
        cases.forEach { (status, identityBody, expected) ->
            ServerSocket(0).use { server ->
                server.soTimeout = 10_000
                val responder = CompletableFuture.runAsync {
                    repeat(2) { index ->
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val reader = socket.getInputStream().bufferedReader()
                            val headers = generateSequence { reader.readLine() }
                                .takeWhile { it.isNotEmpty() }.toList()
                            assertEquals(if (index == 0) "POST /auth/ping HTTP/1.1" else "GET /auth/me HTTP/1.1", headers.first())
                            val body = if (index == 0) "{\"inventory_protocol\":1,\"cost_protocol\":1,\"server_time\":\"now\"}" else identityBody
                            val bytes = body.toByteArray()
                            val responseStatus = if (index == 0) "200 OK" else status
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 $responseStatus\r\nContent-Type: ${if (identityBody.startsWith("<")) "text/html" else "application/json"}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes)
                                flush()
                            }
                        }
                    }
                }
                val result = repository.testConnection("http://127.0.0.1:${server.localPort}", "draft-token")
                assertFalse(result.isSuccess)
                assertTrue(expected in result.message, result.message)
                responder.get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun missingOrUnsupportedCostProtocolStopsBeforeIdentityAndPush(): Unit = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        val repository = InventoryRepository(context)
        listOf(null, 0, 2).forEach { version ->
            listOf(false, true).forEach { sync ->
                ServerSocket(0).use { server ->
                    server.soTimeout = 10_000
                    val responder = CompletableFuture.runAsync {
                        server.accept().use { socket ->
                            val reader = socket.getInputStream().bufferedReader()
                            val headers = generateSequence { reader.readLine() }.takeWhile { !it.isNullOrEmpty() }.toList()
                            assertEquals("POST /auth/ping HTTP/1.1", headers.first())
                            val body = "{\"inventory_protocol\":1" + (version?.let { ",\"cost_protocol\":$it" } ?: "") + "}"
                            val bytes = body.toByteArray()
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush()
                            }
                        }
                    }
                    val url = "http://127.0.0.1:${server.localPort}"
                    val result = if (sync) { repository.saveSyncConfiguration(url,"key",true); repository.runSync() } else repository.testConnection(url,"key")
                    assertFalse(result.isSuccess)
                    assertTrue("升级" in result.message, result.message)
                    responder.get(10, TimeUnit.SECONDS)
                    server.soTimeout = 200
                    kotlin.test.assertFailsWith<java.net.SocketTimeoutException> { server.accept().close() }
                }
            }
        }
    }

    @Test
    fun unsupportedProtocolFailsBeforeIdentityRequest(): Unit = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        val repository = InventoryRepository(context)
        ServerSocket(0).use { server ->
            server.soTimeout = 10_000
            val responder = CompletableFuture.runAsync {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val reader = socket.getInputStream().bufferedReader()
                    val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    assertEquals("POST /auth/ping HTTP/1.1", headers.first())
                    val body = "{\"inventory_protocol\":0,\"cost_protocol\":1,\"server_time\":\"now\"}"
                    val bytes = body.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
            val result = repository.testConnection("http://127.0.0.1:${server.localPort}", "draft-token")
            assertFalse(result.isSuccess)
            assertTrue("inventory_protocol=1" in result.message)
            responder.get(10, TimeUnit.SECONDS)
        }
    }
}
