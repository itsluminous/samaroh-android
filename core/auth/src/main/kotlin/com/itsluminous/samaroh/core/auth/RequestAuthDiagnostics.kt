package com.itsluminous.samaroh.core.auth

import android.util.Base64
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Debug-build request diagnostic (ADR-088): logs, for every Supabase HTTP call, WHICH
 * Postgres role the request will execute as — derived from the `role` claim of the
 * bearer JWT (`anon` = no user session attached; `authenticated` = a user token). Never
 * logs the token itself, only the role and a 8-char `sub` prefix.
 *
 * Exists because a silently dropped session makes every push fail with RLS `42501` while
 * the app keeps working locally; `adb logcat -s SamarohAuthz` shows the cause in one line.
 */
object RequestAuthDiagnostics {
    const val TAG = "SamarohAuthz"

    private val json = Json { ignoreUnknownKeys = true }

    /** Installs the interceptor on the Ktor client supabase-kt uses. Call only in debug builds. */
    fun install(httpClient: HttpClient) {
        httpClient.plugin(HttpSend).intercept { request ->
            val authorization = request.headers[HttpHeaders.Authorization]
            Log.d(TAG, "${request.method.value} ${request.url.build().encodedPath} ${describe(authorization)}")
            execute(request)
        }
    }

    /** `role=<role> sub=<8 chars>` for a bearer JWT, or `no-authorization` when absent. Never the token. */
    fun describe(authorizationHeader: String?): String {
        val token = authorizationHeader?.removePrefix("Bearer ")?.trim()
        if (token.isNullOrEmpty()) return "no-authorization"
        val claims = decodeClaims(token) ?: return "role=unparseable"
        val role = claims["role"] ?: "unknown"
        val sub = claims["sub"]?.take(SUB_PREFIX)
        return if (sub != null) "role=$role sub=$sub" else "role=$role"
    }

    /** Best-effort JWT payload decode — diagnostics only, no signature check. */
    fun decodeClaims(token: String): Map<String, String>? {
        val parts = token.split('.')
        if (parts.size < 2) return null
        return try {
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            json
                .parseToJsonElement(payload)
                .jsonObject
                .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to it.content } }
                .toMap()
        } catch (_: Exception) {
            null
        }
    }

    private const val SUB_PREFIX = 8
}
