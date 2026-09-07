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

    // ---- Business logo: the ONE binary allowed in the archive ----

    private val logoBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x2A, 0x00) // fake WebP header
    private val logo = BackupLogoContent(sourcePath = "/data/user/0/app/files/logos/business-logo-1.webp", bytes = logoBytes)

    @Test
    fun `logo entry is written last and manifest references it`() {
        val withLogo =
            BackupArchive.buildManifest(
                businessId = "biz-1",
                businessName = "Sharma Hall",
                createdAt = "2026-08-25T09:00:00Z",
                tables = tables,
                attachments = attachments,
                logo = logo,
            )
        val bytes = ByteArrayOutputStream().also { BackupArchive.write(it, withLogo, tables, logo) }.toByteArray()

        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        assertThat(entries.keys)
            .containsExactly("manifest.json", "tables/bookings.json", "tables/parties.json", "logo.webp")
            .inOrder()
        assertThat(entries["logo.webp"]).isEqualTo(logoBytes)

        val parsed = Json.decodeFromString(BackupManifest.serializer(), entries["manifest.json"]!!.decodeToString())
        assertThat(parsed.logo?.file).isEqualTo("logo.webp")
        assertThat(parsed.logo?.sourcePath).isEqualTo(logo.sourcePath)
    }

    @Test
    fun `without a logo the zip has no binary entries and manifest logo is null`() {
        val bytes = ByteArrayOutputStream().also { BackupArchive.write(it, manifest, tables) }.toByteArray()
        val entryNames = mutableListOf<String>()
        var manifestJson = ""
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entryNames += entry.name
                if (entry.name == "manifest.json") manifestJson = zip.readBytes().decodeToString()
                entry = zip.nextEntry
            }
        }
        // References-only contract: every entry is the manifest or a table JSON.
        assertThat(entryNames.all { it == "manifest.json" || (it.startsWith("tables/") && it.endsWith(".json")) }).isTrue()
        assertThat(Json.decodeFromString(BackupManifest.serializer(), manifestJson).logo).isNull()
    }

    @Test
    fun `logo entry name carries the source extension, defaulting to webp`() {
        assertThat(BackupLogoContent("/x/logos/business-logo-9.webp", logoBytes).entryName).isEqualTo("logo.webp")
        assertThat(BackupLogoContent("/x/logos/legacy-logo.png", logoBytes).entryName).isEqualTo("logo.png")
        assertThat(BackupLogoContent("/x/logos/no-extension", logoBytes).entryName).isEqualTo("logo.webp")
    }

    @Test
    fun `loadLogo reads small files, skips missing blank and oversized paths`() {
        val dir =
            java.nio.file.Files
                .createTempDirectory("backup-logo-test")
                .toFile()
        try {
            val small = java.io.File(dir, "logo.webp").apply { writeBytes(logoBytes) }
            val loaded = BackupArchive.loadLogo(small.absolutePath)
            assertThat(loaded).isNotNull()
            assertThat(loaded!!.bytes).isEqualTo(logoBytes)
            assertThat(loaded.sourcePath).isEqualTo(small.absolutePath)

            assertThat(BackupArchive.loadLogo(null)).isNull()
            assertThat(BackupArchive.loadLogo("")).isNull()
            assertThat(BackupArchive.loadLogo(java.io.File(dir, "missing.webp").absolutePath)).isNull()
            // Storage-bucket path from a web upload — not a device file, must be skipped.
            assertThat(BackupArchive.loadLogo("biz-1/logo.png")).isNull()

            val oversized = java.io.File(dir, "raw-legacy.jpg")
            oversized.writeBytes(ByteArray((BackupArchive.MAX_LOGO_BYTES + 1).toInt()))
            assertThat(BackupArchive.loadLogo(oversized.absolutePath)).isNull()
        } finally {
            dir.deleteRecursively()
        }
    }
}
