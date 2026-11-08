package com.componentvault.android.data

import android.app.Application
import android.content.Context
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SyncAccountIdentityTest {
    @Test
    fun mismatchedAccountStopsBeforePushAndKeepsCursor(): Unit = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        val repository = InventoryRepository(context)
        val preferences = context.getSharedPreferences("component_vault_sync", Context.MODE_PRIVATE)
        preferences.edit()
            .putString("bound_server_id", "server-1")
            .putString("bound_account_id", "account-1")
            .putLong("sync_cursor", 37)
            .commit()

        ServerSocket(0).use { server ->
            server.soTimeout = 10_000
            val requests = mutableListOf<String>()
            val responder = CompletableFuture.runAsync {
                repeat(2) { index ->
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val reader = socket.getInputStream().bufferedReader()
                        val lines = generateSequence { reader.readLine() }
                            .takeWhile { it.isNotEmpty() }.toList()
                        requests.add(lines.first())
                        val body = if (index == 0)
                            "{\"inventory_protocol\":1,\"cost_protocol\":1,\"server_time\":\"now\"}"
                        else
                            "{\"server_id\":\"server-1\",\"account_id\":\"account-2\",\"name\":\"Other\",\"role\":\"user\"}"
                        val bytes = body.toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
            repository.saveSyncConfiguration("http://127.0.0.1:${server.localPort}", "new-key", true)
            val result = repository.runSync()
            responder.get(10, TimeUnit.SECONDS)
            assertFalse(result.isSuccess)
            assertTrue(result.message.contains("account") || result.message.contains("账户"))
            assertEquals(listOf("POST /auth/ping HTTP/1.1", "GET /auth/me HTTP/1.1"), requests)
            assertEquals(37L, preferences.getLong("sync_cursor", -1))
            assertEquals("account-1", preferences.getString("bound_account_id", null))
        }
    }
}
