package com.itsluminous.samaroh.core.sync.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.Properties
import java.util.UUID

/**
 * Integration-style checks against the Supabase DEV project (spec §12). Guarded: every
 * test is SKIPPED (not failed) when `local.properties` has no credentials — CI and fresh
 * checkouts stay green offline. Any write targets clearly-marked test rows
 * (`samaroh-w1e-test-*`) and is cleaned up.
 */
class SupabaseDevProjectIntegrationTest {
    private var store: PostgrestRemoteStore? = null

    @Before
    fun setUp() {
        val props = Properties()
        // Module dir is core/sync — local.properties lives at the repo root.
        val file = File("../../local.properties")
        if (file.exists()) file.inputStream().use(props::load)
        val url = props.getProperty("SUPABASE_URL")?.trim().orEmpty()
        val key = props.getProperty("SUPABASE_ANON_KEY")?.trim().orEmpty()
        assumeTrue("Supabase dev credentials absent — skipping integration tests", url.isNotBlank() && key.isNotBlank())
        store = PostgrestRemoteStore.createOrNull(url, key)
        assumeTrue(store != null)
    }

    @Test
    fun `incremental pull executes against the dev project`() =
        runTest {
            val result =
                runCatching {
                    store!!.pull(table = "bookings", businessId = null, after = Instant.EPOCH, afterId = null, limit = 1)
                }
            // Anonymous reads are either RLS-filtered (empty list) or rejected outright —
            // both prove the wire path and the error taxonomy; only a transport failure fails.
            result.fold(
                onSuccess = { rows -> assertThat(rows.size).isAtMost(1) },
                onFailure = { e -> assertThat(e).isInstanceOf(RemoteRejectedException::class.java) },
            )
        }

    @Test
    fun `anonymous write is classified as a rejected item, not a transport failure`() =
        runTest {
            val id = UUID.randomUUID().toString()
            val row =
                buildJsonObject {
                    put("id", id)
                    put("name", "samaroh-w1e-test-$id (safe to delete)")
                    put("owner_name", "samaroh-w1e-test")
                    put("owner_user_id", UUID.randomUUID().toString())
                }
            val result = runCatching { store!!.upsert("businesses", row) }
            result.fold(
                onSuccess = {
                    // Dev project allowed the anonymous write — remove the clearly-marked test row.
                    runCatching { store!!.updateTombstone("businesses", "id", id, Instant.now().toString()) }
                },
                onFailure = { e ->
                    // RLS/permission rejections must surface as per-item errors (§8), never as
                    // a transport failure that would put the whole queue into retry.
                    assertThat(e).isInstanceOf(RemoteRejectedException::class.java)
                },
            )
        }
}
