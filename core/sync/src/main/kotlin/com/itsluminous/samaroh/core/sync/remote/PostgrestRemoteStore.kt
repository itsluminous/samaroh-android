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
    ) {
        guard {
            postgrest.from(table).update(
                {
                    set("deleted_at", deletedAt)
                    set("updated_at", deletedAt)
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
        limit: Int,
        columns: String?,
    ): List<JsonObject> =
        guard {
            postgrest
                .from(table)
                .select(columns = columns?.let { Columns.raw(it) } ?: Columns.ALL) {
                    filter {
                        gt("updated_at", after.toString())
                        if (businessId != null) eq("business_id", businessId)
                    }
                    order("updated_at", Order.ASCENDING)
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
