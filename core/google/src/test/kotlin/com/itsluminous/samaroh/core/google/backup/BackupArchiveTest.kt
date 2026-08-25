package com.itsluminous.samaroh.core.google.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.ZipInputStream

class BackupArchiveTest {
    private val tables =
        listOf(
            BackupTableExport(table = "bookings", rowCount = 2, rowsJson = """[{"id":"b1"},{"id":"b2"}]"""),
            BackupTableExport(table = "parties", rowCount = 0, rowsJson = "[]"),
        )
    private val attachments =
        listOf(
            BackupAttachmentRef(
                table = "expense_attachments",
                rowId = "a1",
                driveFileId = "drive-1",
                fileName = "bill.pdf",
                mimeType = "application/pdf",
            ),
        )
    private val manifest =
        BackupArchive.buildManifest(
            businessId = "biz-1",
            businessName = "Sharma Hall",
            createdAt = "2026-08-25T09:00:00Z",
            tables = tables,
            attachments = attachments,
        )

    @Test
    fun `file name follows backup-YYYY-MM-DD-HHmm pattern`() {
        val name = BackupArchive.fileName(LocalDateTime.of(2026, 8, 25, 9, 0))
        assertThat(name).isEqualTo("backup-2026-08-25-0900.zip")
    }

    @Test
    fun `zip contains manifest first then one entry per table`() {
        val bytes = ByteArrayOutputStream().also { BackupArchive.write(it, manifest, tables) }.toByteArray()
        val entryNames = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entryNames += entry.name
                entry = zip.nextEntry
            }
        }
        assertThat(entryNames)
            .containsExactly("manifest.json", "tables/bookings.json", "tables/parties.json")
            .inOrder()
    }

    @Test
    fun `manifest carries format version, paise marker, tables and attachments`() {
        val bytes = ByteArrayOutputStream().also { BackupArchive.write(it, manifest, tables) }.toByteArray()
        val manifestJson =
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                zip.nextEntry
                zip.readBytes().decodeToString()
            }
        val parsed = Json.decodeFromString(BackupManifest.serializer(), manifestJson)

        assertThat(parsed.formatVersion).isEqualTo(BackupArchive.FORMAT_VERSION)
        assertThat(parsed.businessId).isEqualTo("biz-1")
        assertThat(parsed.businessName).isEqualTo("Sharma Hall")
        assertThat(parsed.moneyUnit).isEqualTo("paise")
        assertThat(parsed.tables.map { it.name }).containsExactly("bookings", "parties").inOrder()
        assertThat(parsed.tables.first().file).isEqualTo("tables/bookings.json")
        assertThat(parsed.tables.first().rowCount).isEqualTo(2)
        assertThat(parsed.attachments).hasSize(1)
        assertThat(parsed.attachments.first().driveFileId).isEqualTo("drive-1")
    }

    @Test
    fun `table entries round-trip their row json`() {
        val bytes = ByteArrayOutputStream().also { BackupArchive.write(it, manifest, tables) }.toByteArray()
        val contents = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                contents[entry.name] = zip.readBytes().decodeToString()
                entry = zip.nextEntry
            }
        }
        assertThat(contents["tables/bookings.json"]).isEqualTo("""[{"id":"b1"},{"id":"b2"}]""")
        assertThat(contents["tables/parties.json"]).isEqualTo("[]")
    }
}
