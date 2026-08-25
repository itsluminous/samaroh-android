package com.itsluminous.samaroh.core.google.rest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Minimal HTTP response view — enough for the Drive/Calendar v3 JSON APIs. */
data class GoogleApiResponse(
    val code: Int,
    val body: String,
) {
    val isSuccess: Boolean get() = code in 200..299
}

/** Signals a non-2xx Google API response; message carries the status + a body snippet. */
class GoogleApiException(
    val code: Int,
    body: String,
) : IOException("google api error $code: ${body.take(300)}")

/**
 * Tiny `HttpURLConnection`-based client for the Google REST v3 APIs — deliberately no
 * third-party HTTP/API-client dependency (spec §1.1 keeps the dependency set lean).
 * All calls run on [Dispatchers.IO].
 */
@Singleton
class GoogleApiHttp
    @Inject
    constructor() {
        suspend fun request(
            method: String,
            url: String,
            accessToken: String,
            contentType: String? = null,
            body: ByteArray? = null,
        ): GoogleApiResponse =
            withContext(Dispatchers.IO) {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = method
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    connection.setRequestProperty("Authorization", "Bearer $accessToken")
                    if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
                    if (body != null) {
                        connection.doOutput = true
                        connection.outputStream.use { it.write(body) }
                    }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    GoogleApiResponse(code, text)
                } finally {
                    connection.disconnect()
                }
            }

        private companion object {
            const val TIMEOUT_MS = 30_000
        }
    }
