package com.itsluminous.samaroh.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncErrorSanitizerTest {
    @Test
    fun `plain message passes through`() {
        assertEquals("HTTP 409 duplicate key", SyncErrorSanitizer.sanitize("HTTP 409 duplicate key"))
    }

    @Test
    fun `apikey header line is stripped`() {
        val raw = "HTTP 401 Unauthorized\napikey: eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoiYW5vbiJ9.c2ln\nbody: denied"
        val out = SyncErrorSanitizer.sanitize(raw)
        assertFalse(out.contains("apikey", ignoreCase = true))
        assertFalse(out.contains("eyJ"))
        assertTrue(out.contains("HTTP 401 Unauthorized"))
    }

    @Test
    fun `authorization bearer inline is stripped`() {
        val out = SyncErrorSanitizer.sanitize("request failed Authorization=Bearer abc.def.ghi retry later")
        assertFalse(out.contains("Bearer", ignoreCase = true))
        assertFalse(out.contains("abc.def.ghi"))
        assertTrue(out.contains("request failed"))
    }

    @Test
    fun `bare jwt token is stripped`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIn0.abc123_sig-XYZ"
        val out = SyncErrorSanitizer.sanitize("upload rejected token $jwt expired")
        assertFalse(out.contains("eyJ"))
        assertTrue(out.contains("upload rejected"))
    }

    @Test
    fun `long detail is truncated with ellipsis`() {
        val out = SyncErrorSanitizer.sanitize("x".repeat(500))
        assertTrue(out.length <= 201)
        assertTrue(out.endsWith("\u2026"))
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals("", SyncErrorSanitizer.sanitize(""))
    }
}
