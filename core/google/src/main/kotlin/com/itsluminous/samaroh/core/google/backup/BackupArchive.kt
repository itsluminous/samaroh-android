package com.itsluminous.samaroh.core.google.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
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

/**
 * The business logo, the ONE binary allowed inside the archive: unlike bills and item
 * photos (mirrored to Drive, referenced by id in [BackupManifest.attachments]), the logo
 * lives only at `businesses.logo_path` — device files dir on Android, `logos` Storage
 * bucket for web uploads — so a true disaster loses it unless the ZIP carries the bytes.
 * It is a ≤320px WebP (ADR-050), so the cost is a few tens of KB.
 */
class BackupLogoContent(
    /** The `businesses.logo_path` value the bytes were read from. */
    val sourcePath: String,
    val bytes: ByteArray,
) {
    /** Archive entry name, extension carried over from the source (`logo.webp` normally). */
    val entryName: String = "logo." + (sourcePath.substringAfterLast('.', "").ifEmpty { "webp" })
}

/** Manifest record for the embedded logo entry (references-only rule's sole exception). */
@Serializable
data class BackupLogoRef(
    /** ZIP entry holding the logo bytes, e.g. `logo.webp`. */
    val file: String,
    /** The `businesses.logo_path` value at export time (provenance / re-link hint). */
    @SerialName("source_path") val sourcePath: String,
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
    /** Embedded business logo, when the business has one (additive, still format v1). */
    val logo: BackupLogoRef? = null,
)

object BackupArchive {
    const val FORMAT_VERSION = 1
    const val MANIFEST_ENTRY = "manifest.json"
    const val TABLES_DIR = "tables"
    const val MONEY_UNIT_PAISE = "paise"
    const val MIME_TYPE = "application/zip"

    /**
     * Safety valve for the references-only contract: the logo pipeline emits ≤320px WebP
     * (a few tens of KB), but pre-ADR-050 installs stored the raw picked file at
     * `logo_path`. Anything over this cap is NOT packed — the archive must never balloon
     * with image bytes.
     */
    const val MAX_LOGO_BYTES = 1_048_576L // 1 MiB

    private val json = Json { prettyPrint = true }
    private val fileNameFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

    /** `backup-YYYY-MM-DD-HHmm.zip` (§4.4). */
    fun fileName(at: LocalDateTime): String = "backup-${fileNameFormatter.format(at)}.zip"

    fun tableEntryName(table: String): String = "$TABLES_DIR/$table.json"

    /**
     * Reads the logo bytes for embedding, or null when there is nothing to embed:
     * no logo set, the file is gone (e.g. a web-uploaded Storage path that never
     * existed on this device), or it exceeds [MAX_LOGO_BYTES].
     */
    fun loadLogo(logoPath: String?): BackupLogoContent? {
        if (logoPath.isNullOrBlank()) return null
        val file = File(logoPath)
        if (!file.isFile || file.length() == 0L || file.length() > MAX_LOGO_BYTES) return null
        return BackupLogoContent(sourcePath = logoPath, bytes = file.readBytes())
    }

    fun buildManifest(
        businessId: String,
        businessName: String,
        createdAt: String,
        tables: List<BackupTableExport>,
        attachments: List<BackupAttachmentRef>,
        logo: BackupLogoContent? = null,
    ): BackupManifest =
        BackupManifest(
            formatVersion = FORMAT_VERSION,
            createdAt = createdAt,
            businessId = businessId,
            businessName = businessName,
            moneyUnit = MONEY_UNIT_PAISE,
            tables = tables.map { BackupManifestTable(name = it.table, rowCount = it.rowCount, file = tableEntryName(it.table)) },
            attachments = attachments,
            logo = logo?.let { BackupLogoRef(file = it.entryName, sourcePath = it.sourcePath) },
        )

    /**
     * Writes the ZIP: `manifest.json` first, then one `tables/<table>.json` per export,
     * then the logo entry when present — the only binary allowed in the archive (bills
     * and item photos stay Drive-side, referenced by id in the manifest).
     */
    fun write(
        out: OutputStream,
        manifest: BackupManifest,
        tables: List<BackupTableExport>,
        logo: BackupLogoContent? = null,
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
            if (logo != null) {
                zip.putNextEntry(ZipEntry(logo.entryName))
                zip.write(logo.bytes)
                zip.closeEntry()
            }
        }
    }
}
