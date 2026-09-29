package com.itsluminous.samaroh.core.google.backup

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.database.entity.BookingEntity
import com.itsluminous.samaroh.core.database.entity.BusinessEntity
import com.itsluminous.samaroh.core.database.entity.EventTypeEntity
import com.itsluminous.samaroh.core.database.entity.ExpenseAttachmentEntity
import com.itsluminous.samaroh.core.database.entity.FileEntity
import com.itsluminous.samaroh.core.database.entity.FolderAccessEntity
import com.itsluminous.samaroh.core.database.entity.FolderEntity
import com.itsluminous.samaroh.core.database.entity.MasterItemEntity
import com.itsluminous.samaroh.core.database.entity.NoteEntity
import com.itsluminous.samaroh.core.database.entity.NoteTagEntity
import com.itsluminous.samaroh.core.database.entity.NoteTagLinkEntity
import com.itsluminous.samaroh.core.model.NoteChecklistItem
import com.itsluminous.samaroh.core.model.NoteKind
import com.itsluminous.samaroh.core.testing.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class BackupExporterTest {
    private lateinit var db: SamarohDatabase
    private lateinit var exporter: BackupExporter

    private val now = Instant.parse("2026-08-25T09:00:00Z")
    private val businessId = "biz-1"

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        exporter = BackupExporter(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed() {
        db.businessDao().upsert(
            BusinessEntity(
                id = businessId,
                name = "Sharma Hall",
                ownerName = "owner-fixture",
                ownerUserId = "user-1",
                createdAt = now,
                updatedAt = now,
            ),
        )
        db.bookingDao().upsert(
            BookingEntity(
                id = "b-live",
                businessId = businessId,
                eventType = "wedding",
                eventIcon = "💒",
                customerName = "customer-fixture",
                startDate = LocalDate.of(2026, 9, 10),
                endDate = LocalDate.of(2026, 9, 11),
                totalAmountPaise = 2_00_000_00L,
                createdBy = "user-1",
                createdAt = now,
                updatedAt = now,
            ),
        )
        // Tombstoned row — must still be exported (restores need tombstones).
        db.bookingDao().upsert(
            BookingEntity(
                id = "b-deleted",
                businessId = businessId,
                eventType = "birthday",
                eventIcon = "🎂",
                customerName = "customer-2",
                startDate = LocalDate.of(2026, 10, 1),
                endDate = LocalDate.of(2026, 10, 1),
                createdBy = "user-1",
                createdAt = now,
                updatedAt = now,
                deletedAt = now,
            ),
        )
        db.expenseAttachmentDao().upsert(
            ExpenseAttachmentEntity(
                id = "att-1",
                expenseId = "e-1",
                businessId = businessId,
                driveFileId = "drive-file-9",
                mimeType = "application/pdf",
                fileName = "bill.pdf",
                createdAt = now,
            ),
        )
        // Row from ANOTHER business — must not leak into the export.
        db.bookingDao().upsert(
            BookingEntity(
                id = "b-other",
                businessId = "biz-other",
                eventType = "tilak",
                eventIcon = "🪔",
                customerName = "customer-3",
                startDate = LocalDate.of(2026, 9, 20),
                endDate = LocalDate.of(2026, 9, 20),
                createdBy = "user-2",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Test
    fun `exports every expected table`() =
        runTest {
            seed()
            val content = exporter.export(businessId)
            assertThat(content.tables.map { it.table })
                .containsExactly(
                    "businesses",
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
                    "folders",
                    "files",
                    "folder_access",
                ).inOrder()
        }

    @Test
    fun `rows carry schema column names, paise money and tombstones, scoped to the business`() =
        runTest {
            seed()
            val content = exporter.export(businessId)
            val bookings = content.tables.first { it.table == "bookings" }
            assertThat(bookings.rowCount).isEqualTo(2)

            val rows = Json.parseToJsonElement(bookings.rowsJson).jsonArray.map { it.jsonObject }
            val ids = rows.map { it.getValue("id").jsonPrimitive.content }
            assertThat(ids).containsExactly("b-live", "b-deleted")

            val live = rows.first { it.getValue("id").jsonPrimitive.content == "b-live" }
            // Postgres column names, straight from the schema.
            assertThat(live.getValue("customer_name").jsonPrimitive.content).isEqualTo("customer-fixture")
            // Money is integer paise (ADR-002).
            assertThat(live.getValue("total_amount").jsonPrimitive.longOrNull).isEqualTo(2_00_000_00L)
            // Dates are ISO text; instants are epoch millis.
            assertThat(live.getValue("start_date").jsonPrimitive.content).isEqualTo("2026-09-10")
            assertThat(live.getValue("created_at").jsonPrimitive.longOrNull).isEqualTo(now.toEpochMilli())

            val deleted = rows.first { it.getValue("id").jsonPrimitive.content == "b-deleted" }
            assertThat(deleted.getValue("deleted_at").jsonPrimitive.longOrNull).isEqualTo(now.toEpochMilli())
        }

    @Test
    fun `attachments manifest lists drive-hosted files`() =
        runTest {
            seed()
            val content = exporter.export(businessId)
            assertThat(content.attachments).hasSize(1)
            with(content.attachments.first()) {
                assertThat(table).isEqualTo("expense_attachments")
                assertThat(rowId).isEqualTo("att-1")
                assertThat(driveFileId).isEqualTo("drive-file-9")
                assertThat(fileName).isEqualTo("bill.pdf")
                assertThat(mimeType).isEqualTo("application/pdf")
            }
        }

    /**
     * FILES module (ADR-085, design D20): folders/files/folder_access rows export, and
     * every UPLOADED file's `drive_file_id` joins the attachment manifest with its
     * original name + MIME; a staged (not yet uploaded) row contributes no ref.
     */
    @Test
    fun `files module exports rows and manifests uploaded drive files`() =
        runTest {
            seed()
            db.folderDao().upsert(
                FolderEntity(
                    id = "f-1",
                    businessId = businessId,
                    name = "Contracts",
                    createdBy = "user-1",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            db.fileDao().upsert(
                FileEntity(
                    id = "file-1",
                    businessId = businessId,
                    folderId = "f-1",
                    name = "lease.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 1234,
                    driveFileId = "drive-file-42",
                    createdBy = "user-1",
                    createdAt = now,
                    updatedAt = now,
                    localCachePath = "/data/user/0/app/files/files-cache/file-1",
                ),
            )
            db.fileDao().upsert(
                FileEntity(
                    id = "file-staged",
                    businessId = businessId,
                    name = "pending.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = 10,
                    createdBy = "user-1",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            db.folderAccessDao().upsert(
                FolderAccessEntity(folderId = "f-1", memberId = "m-1", businessId = businessId, createdAt = now, updatedAt = now),
            )

            val content = exporter.export(businessId)
            val byName = content.tables.associateBy { it.table }
            assertThat(byName.getValue("folders").rowCount).isEqualTo(1)
            assertThat(byName.getValue("files").rowCount).isEqualTo(2)
            assertThat(byName.getValue("folder_access").rowCount).isEqualTo(1)
            val fileRefs = content.attachments.filter { it.table == "files" }
            assertThat(fileRefs).hasSize(1)
            with(fileRefs.single()) {
                assertThat(rowId).isEqualTo("file-1")
                assertThat(driveFileId).isEqualTo("drive-file-42")
                assertThat(fileName).isEqualTo("lease.pdf")
                assertThat(mimeType).isEqualTo("application/pdf")
            }
        }

    /**
     * Disaster-recovery completeness for the notes domain + event types (backlog fix):
     * notes/tags/links and event_type presets are user data that only exists in the
     * database — losing the backend without them in the archive loses them forever.
     * Notes carry NO file assets (a checklist is a JSON text column), so the
     * attachment manifest must stay untouched by these rows.
     */
    @Test
    fun `notes domain and event types export rows but add no attachment refs`() =
        runTest {
            seed()
            db.eventTypeDao().upsert(
                EventTypeEntity(
                    id = "et-1",
                    businessId = businessId,
                    label = "Wedding",
                    icon = "💒",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            db.noteDao().upsert(
                NoteEntity(
                    id = "note-1",
                    businessId = businessId,
                    kind = NoteKind.CHECKLIST,
                    title = "Shopping",
                    checklist = listOf(NoteChecklistItem(id = "i-1", text = "Milk", done = true)),
                    pinned = true,
                    createdBy = "user-1",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            db.noteTagDao().upsert(
                NoteTagEntity(id = "tag-1", businessId = businessId, name = "Urgent", createdAt = now, updatedAt = now),
            )
            db.noteTagLinkDao().upsert(
                NoteTagLinkEntity(noteId = "note-1", tagId = "tag-1", businessId = businessId, createdAt = now, updatedAt = now),
            )
            // Another business's note must not leak.
            db.noteDao().upsert(
                NoteEntity(id = "note-other", businessId = "biz-other", createdBy = "user-2", createdAt = now, updatedAt = now),
            )

            val content = exporter.export(businessId)
            val byName = content.tables.associateBy { it.table }
            assertThat(byName.getValue("event_types").rowCount).isEqualTo(1)
            assertThat(byName.getValue("note_tags").rowCount).isEqualTo(1)
            assertThat(byName.getValue("note_tag_links").rowCount).isEqualTo(1)

            val notes = Json.parseToJsonElement(byName.getValue("notes").rowsJson).jsonArray.map { it.jsonObject }
            assertThat(notes).hasSize(1)
            with(notes.single()) {
                assertThat(getValue("id").jsonPrimitive.content).isEqualTo("note-1")
                // Booleans export as 0/1 integers; the checklist as its embedded JSON document string.
                assertThat(getValue("pinned").jsonPrimitive.longOrNull).isEqualTo(1L)
                assertThat(getValue("checklist").jsonPrimitive.content).contains("Milk")
            }

            // Only the seed()'s bill attachment — nothing note-related.
            assertThat(content.attachments.map { it.table }).containsExactly("expense_attachments")
        }

    /**
     * References-only contract (spec §4.4, owner requirement): a real archive built from
     * real exporter output must contain ONLY `manifest.json` + per-table JSON + at most
     * one embedded logo — never bill/item-photo bytes, even when rows point at local
     * cache files. Images are restorable from Drive by the manifest's ids.
     */
    @Test
    fun `generated archive is manifest + table json + logo only — no attachment binaries`() =
        runTest {
            seed()
            // Rows that DO have device-local binary paths — the exporter must reference, not pack.
            db.masterItemDao().upsert(
                MasterItemEntity(
                    id = "item-1",
                    businessId = businessId,
                    name = "Chairs",
                    unit = "pcs",
                    imagePath = "/data/user/0/app/files/item-images/item-1.webp",
                    driveImageId = "drive-img-7",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            db.expenseAttachmentDao().upsert(
                ExpenseAttachmentEntity(
                    id = "att-cached",
                    expenseId = "e-2",
                    businessId = businessId,
                    driveFileId = "drive-file-10",
                    mimeType = "image/jpeg",
                    fileName = "receipt.jpg",
                    localCachePath = "/data/user/0/app/cache/attachments/receipt.jpg",
                    createdAt = now,
                ),
            )

            val content = exporter.export(businessId)
            val logo = BackupLogoContent(sourcePath = "/data/user/0/app/files/logos/business-logo-1.webp", bytes = byteArrayOf(1, 2, 3))
            val manifest =
                BackupArchive.buildManifest(
                    businessId = businessId,
                    businessName = "Sharma Hall",
                    createdAt = now.toString(),
                    tables = content.tables,
                    attachments = content.attachments,
                    logo = logo,
                )
            val zipBytes =
                java.io
                    .ByteArrayOutputStream()
                    .also { BackupArchive.write(it, manifest, content.tables, logo) }
                    .toByteArray()

            val entries = mutableListOf<Pair<String, ByteArray>>()
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zipBytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entries += entry.name to zip.readBytes()
                    entry = zip.nextEntry
                }
            }

            // Exact composition: manifest + one JSON per table + the logo. Nothing else.
            assertThat(entries.map { it.first })
                .containsExactly(
                    "manifest.json",
                    "tables/businesses.json",
                    "tables/business_members.json",
                    "tables/business_settings.json",
                    "tables/event_types.json",
                    "tables/bookings.json",
                    "tables/date_blocks.json",
                    "tables/booking_payments.json",
                    "tables/payment_reminders.json",
                    "tables/parties.json",
                    "tables/expenses.json",
                    "tables/expense_attachments.json",
                    "tables/master_items.json",
                    "tables/inventory_transactions.json",
                    "tables/notes.json",
                    "tables/note_tags.json",
                    "tables/note_tag_links.json",
                    "tables/folders.json",
                    "tables/files.json",
                    "tables/folder_access.json",
                    "logo.webp",
                ).inOrder()

            // Every non-logo entry is parseable JSON text — no smuggled binaries.
            for ((name, bytes) in entries.filterNot { it.first == "logo.webp" }) {
                Json.parseToJsonElement(bytes.decodeToString()) // throws if not JSON
                assertThat(name.endsWith(".json")).isTrue()
            }

            // The manifest references the images by Drive id (bill + item photo).
            val parsedManifest =
                Json.decodeFromString(
                    BackupManifest.serializer(),
                    entries
                        .first {
                            it.first == "manifest.json"
                        }.second
                        .decodeToString(),
                )
            assertThat(parsedManifest.attachments.map { it.driveFileId })
                .containsExactly("drive-file-9", "drive-file-10", "drive-img-7")
            assertThat(parsedManifest.logo?.file).isEqualTo("logo.webp")
        }
}
