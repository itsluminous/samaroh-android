package com.itsluminous.samaroh.core.google.backup

import android.database.Cursor
import android.util.Base64
import androidx.sqlite.db.SimpleSQLiteQuery
import com.itsluminous.samaroh.core.database.SamarohDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the archive writer needs for one business. */
data class BackupContent(
    val tables: List<BackupTableExport>,
    val attachments: List<BackupAttachmentRef>,
)

/**
 * Exports every business-scoped table as a JSON array of row objects. Rows are read with
 * raw cursors so the export mechanically mirrors the canonical schema (column names =
 * Postgres names) with zero mapping code — see docs/backup-format.md for the value
 * conventions (paise money, epoch-millis instants, ISO dates, tombstones included).
 */
@Singleton
class BackupExporter
    @Inject
    constructor(
        private val database: SamarohDatabase,
    ) {
        suspend fun export(businessId: String): BackupContent =
            withContext(Dispatchers.IO) {
                val tables =
                    buildList {
                        add(exportTable("businesses", "SELECT * FROM businesses WHERE id = ?", businessId))
                        for (table in BUSINESS_SCOPED_TABLES) {
                            add(exportTable(table, "SELECT * FROM $table WHERE business_id = ?", businessId))
                        }
                    }
                BackupContent(tables = tables, attachments = collectAttachmentRefs(tables))
            }

        private fun exportTable(
            table: String,
            sql: String,
            businessId: String,
        ): BackupTableExport {
            val rows = mutableListOf<JsonObject>()
            database.query(SimpleSQLiteQuery(sql, arrayOf(businessId))).use { cursor ->
                while (cursor.moveToNext()) {
                    rows += cursor.rowToJson()
                }
            }
            return BackupTableExport(table = table, rowCount = rows.size, rowsJson = JsonArray(rows).toString())
        }

        private fun Cursor.rowToJson(): JsonObject {
            val fields = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
            for (i in 0 until columnCount) {
                fields[getColumnName(i)] =
                    when (getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> JsonNull
                        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(getDouble(i))
                        Cursor.FIELD_TYPE_BLOB -> JsonPrimitive(Base64.encodeToString(getBlob(i), Base64.NO_WRAP))
                        else -> JsonPrimitive(getString(i))
                    }
            }
            return JsonObject(fields)
        }

        /**
         * Attachment manifest: Drive file ids referenced by exported rows (§4.4, §9.1).
         * Only `expense_attachments` and `master_items` carry file references — notes,
         * tags, links and event types are pure row data (a checklist is a JSON text
         * column, not a file), so they contribute nothing here.
         */
        private fun collectAttachmentRefs(tables: List<BackupTableExport>): List<BackupAttachmentRef> {
            val refs = mutableListOf<BackupAttachmentRef>()
            val byName = tables.associateBy { it.table }

            byName["expense_attachments"]?.let { export ->
                for (row in parseRows(export.rowsJson)) {
                    val driveId = row.stringOrNull("drive_file_id") ?: continue
                    refs +=
                        BackupAttachmentRef(
                            table = "expense_attachments",
                            rowId = row.stringOrNull("id").orEmpty(),
                            driveFileId = driveId,
                            fileName = row.stringOrNull("file_name").orEmpty(),
                            mimeType = row.stringOrNull("mime_type"),
                        )
                }
            }
            byName["master_items"]?.let { export ->
                for (row in parseRows(export.rowsJson)) {
                    val driveId = row.stringOrNull("drive_image_id") ?: continue
                    refs +=
                        BackupAttachmentRef(
                            table = "master_items",
                            rowId = row.stringOrNull("id").orEmpty(),
                            driveFileId = driveId,
                            fileName = row.stringOrNull("name").orEmpty(),
                            mimeType = null,
                        )
                }
            }
            return refs
        }

        private fun parseRows(rowsJson: String): List<JsonObject> =
            (
                kotlinx.serialization.json.Json
                    .parseToJsonElement(rowsJson) as JsonArray
            ).map { it.jsonObject }

        private fun JsonObject.stringOrNull(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.jsonPrimitive?.content

        internal companion object {
            /** Every synced, business-scoped table (see [EXCLUDED_PER_USER_TABLES]/[EXCLUDED_LOCAL_TABLES] for what stays out). */
            val BUSINESS_SCOPED_TABLES =
                listOf(
                    "business_members",
                    "business_settings",
                    "event_types",
                    "bookings",
                    "date_blocks",
                    "booking_payments",
                    "payment_reminders",
                    "parties",
                    "expenses",
                    "expense_attachments",
                    "master_items",
                    "inventory_transactions",
                    "notes",
                    "note_tags",
                    "note_tag_links",
                )

            /** The complete archive table list: the business row + every business-scoped table. */
            val EXPORTED_TABLES: List<String> = listOf("businesses") + BUSINESS_SCOPED_TABLES

            /**
             * `google_accounts` holds NO business data: it is keyed by the auth user (not
             * business_id), never stores tokens client-side (ADR-003), and its remaining
             * columns (email, scopes, drive_root_folder_id, calendar_id) are Google-account
             * identifiers that a post-disaster restore re-derives when the user re-links —
             * after a backend loss the auth user ids themselves are recreated, so an
             * exported row would only dangle. Excluded by design (docs/backup-format.md).
             */
            val EXCLUDED_PER_USER_TABLES = setOf("google_accounts")

            /** Sync-machinery tables that are meaningless off-device (§8): never exported. */
            val EXCLUDED_LOCAL_TABLES = setOf("outbox", "sync_cursors", "sync_conflicts")
        }
    }
