package com.itsluminous.samaroh.core.google.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * Backup archive format (spec §4.4) — documented in docs/backup-format.md. Pure Kotlin:
 * no Android/database types, fully unit-tested.
 */

/** One exported table: [rowsJson] is a JSON array of row objects keyed by schema column names. */
data class BackupTableExport(
    val table: String,
    val rowCount: Int,
    val rowsJson: String,
)

/** A Drive-hosted attachment referenced by an exported row (§9.1 layout). */
@Serializable
data class BackupAttachmentRef(
    /** Table of the owning row (`expense_attachments` or `master_items`). */
    val table: String,
    @SerialName("row_id") val rowId: String,
    @SerialName("drive_file_id") val driveFileId: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("mime_type") val mimeType: String? = null,
)

@Serializable
data class BackupManifestTable(
    val name: String,
    @SerialName("row_count") val rowCount: Int,
    val file: String,
)

/** `manifest.json` at the archive root — everything a restore tool needs to navigate the ZIP. */
@Serializable
data class BackupManifest(
    @SerialName("format_version") val formatVersion: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("business_name") val businessName: String,
    /** Money convention marker: every money value in the exports is integer paise (ADR-002). */
    @SerialName("money_unit") val moneyUnit: String,
    val tables: List<BackupManifestTable>,
    val attachments: List<BackupAttachmentRef>,
)

object BackupArchive {
    const val FORMAT_VERSION = 1
    const val MANIFEST_ENTRY = "manifest.json"
    const val TABLES_DIR = "tables"
    const val MONEY_UNIT_PAISE = "paise"
    const val MIME_TYPE = "application/zip"

    private val json = Json { prettyPrint = true }
    private val fileNameFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

    /** `backup-YYYY-MM-DD-HHmm.zip` (§4.4). */
    fun fileName(at: LocalDateTime): String = "backup-${fileNameFormatter.format(at)}.zip"

    fun tableEntryName(table: String): String = "$TABLES_DIR/$table.json"

    fun buildManifest(
        businessId: String,
        businessName: String,
        createdAt: String,
        tables: List<BackupTableExport>,
        attachments: List<BackupAttachmentRef>,
    ): BackupManifest =
        BackupManifest(
            formatVersion = FORMAT_VERSION,
            createdAt = createdAt,
            businessId = businessId,
            businessName = businessName,
            moneyUnit = MONEY_UNIT_PAISE,
            tables = tables.map { BackupManifestTable(name = it.table, rowCount = it.rowCount, file = tableEntryName(it.table)) },
            attachments = attachments,
        )

    /** Writes the ZIP: `manifest.json` first, then one `tables/<table>.json` per export. */
    fun write(
        out: OutputStream,
        manifest: BackupManifest,
        tables: List<BackupTableExport>,
    ) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            for (table in tables) {
                zip.putNextEntry(ZipEntry(tableEntryName(table.table)))
                zip.write(table.rowsJson.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
