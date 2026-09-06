package com.itsluminous.samaroh.core.google.drive

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.google.auth.GoogleAccessTokenProvider
import com.itsluminous.samaroh.core.google.rest.GoogleApiException
import com.itsluminous.samaroh.core.google.rest.GoogleApiHttp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Drive media download (`files.get?alt=media`, ADR-052) against a local mock HTTP server —
 * exercises the REAL [GoogleApiHttp.downloadToFile] streaming path, including auth header,
 * binary integrity and non-2xx cleanup. The server is a raw [ServerSocket] speaking just
 * enough HTTP/1.1 (no third-party test dependency, and `com.sun.net.httpserver` is not on
 * the Android unit-test compile classpath).
 */
class RestDriveServiceDownloadTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Binary payload with bytes outside ASCII — corrupts if the body were read as text. */
    private val mediaBytes = ByteArray(512) { (it % 251).toByte() }

    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread
    private lateinit var service: RestDriveService

    @Volatile private var requestLine: String? = null

    @Volatile private var authHeader: String? = null

    @Before
    fun setUp() {
        server = ServerSocket(0)
        serverThread =
            thread(isDaemon = true) {
                runCatching {
                    while (!server.isClosed) {
                        val socket = server.accept()
                        socket.use {
                            val reader = it.getInputStream().bufferedReader()
                            val request = reader.readLine() ?: return@use
                            requestLine = request
                            var line = reader.readLine()
                            while (!line.isNullOrEmpty()) {
                                if (line.startsWith("Authorization:")) authHeader = line.removePrefix("Authorization:").trim()
                                line = reader.readLine()
                            }
                            val out = it.getOutputStream()
                            if (request.startsWith("GET /files/media-file-1?alt=media")) {
                                out.write(
                                    "HTTP/1.1 200 OK\r\nContent-Length: ${mediaBytes.size}\r\nConnection: close\r\n\r\n".toByteArray(),
                                )
                                out.write(mediaBytes)
                            } else {
                                val body = """{"error":"not found"}""".toByteArray()
                                out.write(
                                    "HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(),
                                )
                                out.write(body)
                            }
                            out.flush()
                        }
                    }
                }
            }
        service =
            RestDriveService(
                http = GoogleApiHttp(),
                tokenProvider =
                    object : GoogleAccessTokenProvider {
                        override suspend fun accessToken(): String = "test-access-token"
                    },
            ).apply { filesUrl = "http://localhost:${server.localPort}/files" }
    }

    @After
    fun tearDown() {
        server.close()
        serverThread.join(2_000)
    }

    @Test
    fun `download streams the binary body to the target with the bearer token`() {
        val target = tempFolder.newFile("bill.jpg")

        runBlocking { service.downloadFile("media-file-1", target) }

        assertThat(target.readBytes()).isEqualTo(mediaBytes)
        assertThat(requestLine).startsWith("GET /files/media-file-1?alt=media")
        assertThat(authHeader).isEqualTo("Bearer test-access-token")
    }

    @Test
    fun `non-2xx response throws and removes the partial target`() {
        val target = tempFolder.newFile("missing.jpg")

        val error =
            assertThrows(GoogleApiException::class.java) {
                runBlocking { service.downloadFile("unknown-file", target) }
            }

        assertThat(error.code).isEqualTo(404)
        assertThat(target.exists()).isFalse()
    }
}
