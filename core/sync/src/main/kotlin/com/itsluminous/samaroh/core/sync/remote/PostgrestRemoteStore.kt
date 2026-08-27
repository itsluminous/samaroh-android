package com.itsluminous.samaroh.core.sync.remote

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.time.Instant

/**
 * supabase-kt Postgrest implementation of [RemoteStore]. Error taxonomy (§8):
 * REST-level rejections (RLS `42501`, constraint violations, bad requests) become
 * [RemoteRejectedException] (per-item error, retriable after fix); transport failures
 * become [RemoteUnavailableException] (whole run retries with exponential backoff).
 */
class PostgrestRemoteStore(
    private val postgrest: Postgrest,
) : RemoteStore {
    override suspend fun upsert(
        table: String,
        row: JsonObject,
    ) {
        guard { postgrest.from(table).upsert(row) }
    }

    override suspend fun updateTombstone(
        table: String,
        idColumn: String,
        id: String,
        deletedAt: String,
        touchUpdatedAt: Boolean,
    ) {
        guard {
            postgrest.from(table).update(
                {
                    set("deleted_at", deletedAt)
                    if (touchUpdatedAt) set("updated_at", deletedAt)
                },
            ) {
                filter { eq(idColumn, id) }
            }
        }
    }

    override suspend fun pull(
        table: String,
        businessId: String?,
        after: Instant,
        afterId: String?,
        limit: Int,
        columns: String?,
        cursorColumn: String,
        idColumn: String,
    ): List<JsonObject> =
        guard {
            postgrest
                .from(table)
                .select(columns = columns?.let { Columns.raw(it) } ?: Columns.ALL) {
                    filter {
                        if (afterId == null) {
                            // Legacy/fresh cursor: include rows AT the timestamp so ties
                            // lost by the old `>`-only cursor are recovered (ADR-024).
                            gte(cursorColumn, after.toString())
                        } else {
                            // Keyset: strictly after (after, afterId) in (ts, id) order.
                            or {
                                gt(cursorColumn, after.toString())
                                and {
                                    eq(cursorColumn, after.toString())
                                    gt(idColumn, afterId)
                                }
                            }
                        }
                        if (businessId != null) eq("business_id", businessId)
                    }
                    order(cursorColumn, Order.ASCENDING)
                    order(idColumn, Order.ASCENDING)
                    limit(limit.toLong())
                }.decodeList<JsonObject>()
        }

    private suspend fun <T> guard(block: suspend () -> T): T =
        try {
            block()
        } catch (e: RestException) {
            throw RemoteRejectedException(e.message ?: "rejected", e)
        } catch (e: HttpRequestTimeoutException) {
            throw RemoteUnavailableException(e.message ?: "timeout", e)
        } catch (e: HttpRequestException) {
            throw RemoteUnavailableException(e.message ?: "network failure", e)
        } catch (e: IOException) {
            throw RemoteUnavailableException(e.message ?: "network failure", e)
        }

    companion object {
        /** Builds a store from project credentials, or null when they are absent (offline-only build). */
        fun createOrNull(
            supabaseUrl: String,
            supabaseKey: String,
        ): PostgrestRemoteStore? {
            if (supabaseUrl.isBlank() || supabaseKey.isBlank()) return null
            val client =
                createSupabaseClient(supabaseUrl, supabaseKey) {
                    install(Postgrest)
                }
            return PostgrestRemoteStore(client.postgrest)
        }
    }
}
