package com.itsluminous.samaroh.core.google.backup

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.database.entity.BookingEntity
import com.itsluminous.samaroh.core.database.entity.BusinessEntity
import com.itsluminous.samaroh.core.database.entity.ExpenseAttachmentEntity
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
                    "bookings",
                    "date_blocks",
                    "booking_payments",
                    "payment_reminders",
                    "parties",
                    "expenses",
                    "expense_attachments",
                    "master_items",
                    "inventory_transactions",
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
}
