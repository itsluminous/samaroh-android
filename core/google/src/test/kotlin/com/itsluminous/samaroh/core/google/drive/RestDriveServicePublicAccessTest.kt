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
 * ADR-059 REST plumbing against a local mock HTTP server (the ADR-052 download-test
 * pattern — raw [ServerSocket], no third-party test dependency):
 *
 * - `permissions.create` posts `{"role":"reader","type":"anyone"}` to
 *   `files/{id}/permissions` with the bearer token;
 * - the PUBLIC link download sends NO credentials and streams binary bytes;
 * - a 2xx HTML answer (interstitial / sign-in page — the file is not link-shared) is a
 *   failure with the partial target removed, never cached as a bill.
 */
class RestDriveServicePublicAccessTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Binary payload with bytes outside ASCII — corrupts if the body were read as text. */
    private val mediaBytes = ByteArray(256) { (it % 251).toByte() }

    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread
    private lateinit var service: RestDriveService

    @Volatile private var requestLine: String? = null

    @Volatile private var authHeader: String? = null

    @Volatile private var requestBody: String? = null

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
                            authHeader = null
                            var contentLength = 0
                            var line = reader.readLine()
                            while (!line.isNullOrEmpty()) {
                                if (line.startsWith("Authorization:")) authHeader = line.removePrefix("Authorization:").trim()
                                if (line.startsWith("Content-Length:")) {
                                    contentLength = line.removePrefix("Content-Length:").trim().toInt()
                                }
                                line = reader.readLine()
                            }
                            requestBody =
                                if (contentLength > 0) {
                                    val chars = CharArray(contentLength)
                                    var read = 0
                                    while (read < contentLength) {
                                        val n = reader.read(chars, read, contentLength - read)
                                        if (n < 0) break
                                        read += n
                                    }
                                    String(chars, 0, read)
                                } else {
                                    null
                                }
                            val out = it.getOutputStream()
                            when {
                                request.startsWith("POST /files/shared-1/permissions") -> {
                                    val body = """{"id":"anyoneWithLink","role":"reader","type":"anyone"}""".toByteArray()
                                    out.write(
                                        "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(),
                                    )
                                    out.write(body)
                                }
                                request.startsWith("GET /public?export=download&id=shared-1") -> {
                                    out.write(
                                        (
                                            "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\n" +
                                                "Content-Length: ${mediaBytes.size}\r\nConnection: close\r\n\r\n"
                                        ).toByteArray(),
                                    )
                                    out.write(mediaBytes)
                                }
                                request.startsWith("GET /public?export=download&id=unshared-1") -> {
                                    val body = "<html>sign in to continue</html>".toByteArray()
                                    out.write(
                                        (
                                            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                        ).toByteArray(),
                                    )
                                    out.write(body)
                                }
                                else -> {
                                    val body = """{"error":"not found"}""".toByteArray()
                                    out.write(
                                        "HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(),
                                    )
                                    out.write(body)
                                }
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
            ).apply {
                filesUrl = "http://localhost:${server.localPort}/files"
                publicDownloadUrl = "http://localhost:${server.localPort}/public?export=download&id="
            }
    }

    @After
    fun tearDown() {
        server.close()
        serverThread.join(2_000)
    }

    @Test
    fun `permission create posts role reader type anyone with the bearer token`() {
        runBlocking { service.ensureAnyoneReaderPermission("shared-1") }

        assertThat(requestLine).startsWith("POST /files/shared-1/permissions")
        assertThat(authHeader).isEqualTo("Bearer test-access-token")
        assertThat(requestBody).contains("\"role\":\"reader\"")
        assertThat(requestBody).contains("\"type\":\"anyone\"")
    }

    @Test
    fun `permission create surfaces non-2xx as GoogleApiException`() {
        val error =
            assertThrows(GoogleApiException::class.java) {
                runBlocking { service.ensureAnyoneReaderPermission("unknown-file") }
            }

        assertThat(error.code).isEqualTo(404)
    }

    @Test
    fun `public download streams binary bytes without any credentials`() {
        val target = tempFolder.newFile("bill.jpg")

        runBlocking { service.downloadPublicFile("shared-1", target) }

        assertThat(target.readBytes()).isEqualTo(mediaBytes)
        assertThat(requestLine).startsWith("GET /public?export=download&id=shared-1")
        assertThat(authHeader).isNull()
    }

    @Test
    fun `public download of a not-link-shared file (html answer) throws and removes the target`() {
        val target = tempFolder.newFile("unshared.jpg")

        val error =
            assertThrows(GoogleApiException::class.java) {
                runBlocking { service.downloadPublicFile("unshared-1", target) }
            }

        assertThat(error.code).isEqualTo(404)
        assertThat(error.message).contains("not link-shared")
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun `public download non-2xx throws and removes the target`() {
        val target = tempFolder.newFile("missing.jpg")

        val error =
            assertThrows(GoogleApiException::class.java) {
                runBlocking { service.downloadPublicFile("gone-1", target) }
            }

        assertThat(error.code).isEqualTo(404)
        assertThat(target.exists()).isFalse()
    }
}
