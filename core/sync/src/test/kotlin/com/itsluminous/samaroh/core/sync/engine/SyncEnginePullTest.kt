package com.itsluminous.samaroh.core.sync.engine

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.ConflictResolution
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.database.entity.SyncCursorEntity
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.testing.Fixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class SyncEnginePullTest {
    private lateinit var db: SamarohDatabase
    private lateinit var remote: FakeRemoteStore
    private lateinit var notifier: RecordingConflictNotifier

    @Before
    fun setUp() {
        db = newTestDatabase()
        remote = FakeRemoteStore()
        notifier = RecordingConflictNotifier()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedBusiness() {
        db.businessDao().upsert(
            com.itsluminous.samaroh.core.database.entity
                .BusinessEntity(
                    id = Fixtures.BUSINESS_ID,
                    name = "fixture-business",
                    ownerName = "fixture-owner",
                    ownerUserId = Fixtures.USER_ID,
                    createdAt = Fixtures.NOW,
                    updatedAt = Fixtures.NOW,
                ),
        )
    }

    @Test
    fun `pulled rows land in room and the cursor advances to the newest updated_at`() =
        runTest {
            seedBusiness()
            remote.servePage(
                "bookings",
                listOf(
                    remoteBookingRow("b-1", updatedAt = "2026-08-25T10:00:00+00:00", totalRupees = "1234.56"),
                    remoteBookingRow("b-2", updatedAt = "2026-08-25T11:00:00+00:00"),
                ),
            )

            syncEngine(db, remote, notifier).runSync()

            val pulled = db.bookingDao().byId("b-1")
            assertThat(pulled).isNotNull()
            assertThat(pulled!!.totalAmountPaise).isEqualTo(123_456L)
            assertThat(db.syncCursorDao().cursor(Fixtures.BUSINESS_ID, "bookings"))
                .isEqualTo(Instant.parse("2026-08-25T11:00:00Z"))
        }

    @Test
    fun `second run pulls incrementally from the stored cursor`() =
        runTest {
            seedBusiness()
            db.syncCursorDao().upsert(
                SyncCursorEntity(Fixtures.BUSINESS_ID, "bookings", Instant.parse("2026-08-20T00:00:00Z")),
            )

            syncEngine(db, remote, notifier).runSync()

            val bookingsPull = remote.pullCalls.single { it.first == "bookings" }
            assertThat(bookingsPull.second).isEqualTo(Fixtures.BUSINESS_ID)
            assertThat(bookingsPull.third).isEqualTo(Instant.parse("2026-08-20T00:00:00Z"))
        }

    @Test
    fun `remote tombstones propagate as local soft deletes`() =
        runTest {
            seedBusiness()
            db.bookingDao().upsert(Fixtures.booking(id = "b-del").toEntity())
            remote.servePage(
                "bookings",
                listOf(
                    remoteBookingRow(
                        "b-del",
                        updatedAt = "2026-08-25T11:00:00+00:00",
                        deletedAt = "2026-08-25T11:00:00+00:00",
                    ),
                ),
            )

            syncEngine(db, remote, notifier).runSync()

            assertThat(db.bookingDao().byId("b-del")!!.deletedAt).isEqualTo(Instant.parse("2026-08-25T11:00:00Z"))
        }

    @Test
    fun `local pending op newer than pulled row wins - remote row is skipped`() =
        runTest {
            seedBusiness()
            val local = Fixtures.booking(id = "b-lww").copy(customerName = "local-edit", updatedAt = Instant.parse("2026-08-25T11:30:00Z"))
            db.bookingDao().upsert(local.toEntity())
            db.outboxDao().enqueue(bookingOutboxEntry(local))
            // Hold the op in the queue (push runs before pull, §8 pipeline order).
            remote.onUpsert = { _, _ ->
                com.itsluminous.samaroh.core.sync.remote
                    .RemoteRejectedException("rls")
            }
            remote.servePage(
                "bookings",
                listOf(remoteBookingRow("b-lww", updatedAt = "2026-08-25T10:00:00+00:00", customerName = "remote-edit")),
            )

            val outcome = syncEngine(db, remote, notifier).runSync()

            // Local (newer) wins: Room untouched, op still queued, no conflict raised.
            assertThat(db.bookingDao().byId("b-lww")!!.customerName).isEqualTo("local-edit")
            assertThat(db.outboxDao().pendingForEntity("bookings", "b-lww")).hasSize(1)
            assertThat(outcome.conflictCount).isEqualTo(0)
            assertThat(notifier.events).isEmpty()
        }

    @Test
    fun `pulled row newer with no pending op simply applies`() =
        runTest {
            seedBusiness()
            db.bookingDao().upsert(Fixtures.booking(id = "b-plain").toEntity())
            remote.servePage(
                "bookings",
                listOf(remoteBookingRow("b-plain", updatedAt = "2026-08-25T11:00:00+00:00", customerName = "web-edit")),
            )

            val outcome = syncEngine(db, remote, notifier).runSync()

            assertThat(db.bookingDao().byId("b-plain")!!.customerName).isEqualTo("web-edit")
            assertThat(outcome.conflictCount).isEqualTo(0)
        }

    @Test
    fun `pending upsert older than pulled row is REBASED with conflict log and notification`() =
        runTest {
            seedBusiness()
            // Local pending edit (older): customer name change.
            val local =
                Fixtures
                    .booking(id = "b-rebase")
                    .copy(customerName = "local-name", notes = null, updatedAt = Instant.parse("2026-08-25T09:30:00Z"))
            db.bookingDao().upsert(local.toEntity())
            db.outboxDao().enqueue(bookingOutboxEntry(local))
            // Hold the op in the queue (push runs before pull, §8 pipeline order).
            remote.onUpsert = { _, _ ->
                com.itsluminous.samaroh.core.sync.remote
                    .RemoteRejectedException("rls")
            }
            // Remote (newer): a different customer name was saved on the web app.
            remote.servePage(
                "bookings",
                listOf(
                    remoteBookingRow(
                        "b-rebase",
                        updatedAt = "2026-08-25T10:00:00+00:00",
                        customerName = "remote-name",
                        totalRupees = "200000",
                    ),
                ),
            )

            val outcome = syncEngine(db, remote, notifier).runSync()

            assertThat(outcome.conflictCount).isEqualTo(1)
            // The local edit is re-applied on top of the newer remote row and stays visible.
            val merged = db.bookingDao().byId("b-rebase")!!
            assertThat(merged.customerName).isEqualTo("local-name")
            // The consolidated op was requeued with a fresh updated_at (newer than remote) so
            // the next push carries the rebased edit up.
            val requeued = db.outboxDao().pendingForEntity("bookings", "b-rebase").single()
            val payload = testJson.parseToJsonElement(requeued.payloadJson).jsonObject
            assertThat(payload.getValue("customer_name").jsonPrimitive.content).isEqualTo("local-name")
            assertThat(Instant.parse(payload.getValue("updated_at").jsonPrimitive.content))
                .isEqualTo(FIXED_NOW)
            assertThat(requeued.lastError).isNull()
            // Conflict persisted + notified, never silent.
            val conflict =
                db
                    .syncConflictDao()
                    .conflictLog()
                    .first()
                    .single()
            assertThat(conflict.resolution).isEqualTo(ConflictResolution.REBASED.wire)
            assertThat(conflict.overriddenFields).contains("customer_name")
            assertThat(notifier.events.single().resolution).isEqualTo(ConflictResolution.REBASED)
        }

    @Test
    fun `pending delete older than pulled row is DROPPED with conflict log`() =
        runTest {
            seedBusiness()
            val local = Fixtures.booking(id = "b-drop")
            db.bookingDao().upsert(local.toEntity().copy(deletedAt = Instant.parse("2026-08-25T09:00:00Z")))
            db.outboxDao().enqueue(
                com.itsluminous.samaroh.core.database.entity.OutboxEntity(
                    entityType = "bookings",
                    entityId = "b-drop",
                    operation = "delete",
                    payloadJson = """{"id":"b-drop","deleted_at":"2026-08-25T09:00:00Z"}""",
                    createdAt = Fixtures.NOW,
                ),
            )
            // Hold the delete in the queue (push runs before pull, §8 pipeline order).
            remote.onTombstone = { _, _ ->
                com.itsluminous.samaroh.core.sync.remote
                    .RemoteRejectedException("rls")
            }
            remote.servePage(
                "bookings",
                listOf(remoteBookingRow("b-drop", updatedAt = "2026-08-25T10:00:00+00:00", customerName = "still-alive")),
            )

            val outcome = syncEngine(db, remote, notifier).runSync()

            // Delete op dropped; the remote (newer, non-deleted) row stands.
            assertThat(db.outboxDao().pendingForEntity("bookings", "b-drop")).isEmpty()
            val row = db.bookingDao().byId("b-drop")!!
            assertThat(row.deletedAt).isNull()
            assertThat(row.customerName).isEqualTo("still-alive")
            val conflict =
                db
                    .syncConflictDao()
                    .conflictLog()
                    .first()
                    .single()
            assertThat(conflict.resolution).isEqualTo(ConflictResolution.DROPPED.wire)
            assertThat(notifier.events.single().resolution).isEqualTo(ConflictResolution.DROPPED)
            assertThat(outcome.conflictCount).isEqualTo(1)
        }

    @Test
    fun `remote tombstone newer than pending upsert drops the local edit`() =
        runTest {
            seedBusiness()
            val local = Fixtures.booking(id = "b-gone").copy(customerName = "local-edit", updatedAt = Instant.parse("2026-08-25T09:00:00Z"))
            db.bookingDao().upsert(local.toEntity())
            db.outboxDao().enqueue(bookingOutboxEntry(local))
            // Push must fail so the op is still pending when the pull sees the tombstone.
            remote.onUpsert = { _, _ ->
                com.itsluminous.samaroh.core.sync.remote
                    .RemoteRejectedException("rls")
            }
            remote.servePage(
                "bookings",
                listOf(
                    remoteBookingRow(
                        "b-gone",
                        updatedAt = "2026-08-25T10:00:00+00:00",
                        deletedAt = "2026-08-25T10:00:00+00:00",
                    ),
                ),
            )

            syncEngine(db, remote, notifier).runSync()

            assertThat(db.outboxDao().pendingForEntity("bookings", "b-gone")).isEmpty()
            assertThat(db.bookingDao().byId("b-gone")!!.deletedAt).isNotNull()
            assertThat(
                db
                    .syncConflictDao()
                    .conflictLog()
                    .first()
                    .single()
                    .resolution,
            ).isEqualTo(ConflictResolution.DROPPED.wire)
        }

    @Test
    fun `unacknowledged conflicts drive the banner state until acknowledged`() =
        runTest {
            seedBusiness()
            val local = Fixtures.booking(id = "b-x").copy(customerName = "local", updatedAt = Instant.parse("2026-08-25T09:00:00Z"))
            db.bookingDao().upsert(local.toEntity())
            db.outboxDao().enqueue(bookingOutboxEntry(local))
            remote.onUpsert = { _, _ ->
                com.itsluminous.samaroh.core.sync.remote
                    .RemoteRejectedException("rls")
            }
            remote.servePage(
                "bookings",
                listOf(remoteBookingRow("b-x", updatedAt = "2026-08-25T10:00:00+00:00", customerName = "remote")),
            )

            syncEngine(db, remote, notifier).runSync()

            assertThat(db.syncConflictDao().unacknowledgedCount().first()).isEqualTo(1)
            val id =
                db
                    .syncConflictDao()
                    .conflictLog()
                    .first()
                    .single()
                    .id
            db.syncConflictDao().acknowledge(id)
            assertThat(db.syncConflictDao().unacknowledgedCount().first()).isEqualTo(0)
        }

    @Test
    fun `successful run records the last sync time`() =
        runTest {
            val meta = InMemorySyncMetaStore()

            syncEngine(db, remote, notifier, metaStore = meta).runSync()

            assertThat(meta.lastSyncTime.first()).isEqualTo(FIXED_NOW)
        }

    @Test
    fun `expense_attachments pull uses created_at - the table has no updated_at column`() =
        runTest {
            seedBusiness()
            remote.servePage(
                "expense_attachments",
                listOf(
                    buildJsonObject {
                        put("id", "att-1")
                        put("expense_id", "exp-1")
                        put("business_id", Fixtures.BUSINESS_ID)
                        put("drive_file_id", "drive-1")
                        put("mime_type", "application/pdf")
                        put("file_name", "invoice.pdf")
                        put("created_at", "2026-08-25T10:00:00+00:00")
                        put("deleted_at", JsonNull)
                    },
                ),
            )

            syncEngine(db, remote, notifier).runSync()

            assertThat(remote.pullCursorColumns["expense_attachments"]).isEqualTo("created_at")
            assertThat(remote.pullCursorColumns["bookings"]).isEqualTo("updated_at")
            assertThat(db.expenseAttachmentDao().byId("att-1")).isNotNull()
            assertThat(db.syncCursorDao().cursor(Fixtures.BUSINESS_ID, "expense_attachments"))
                .isEqualTo(Instant.parse("2026-08-25T10:00:00Z"))
        }

    // ---- one-run coverage: businesses discovered mid-run (§8 "calendar empty after sign-in" bug) ----

    @Test
    fun `fresh sign-in - a business pulled this run gets its bookings in the SAME run`() =
        runTest {
            // No local business at all (fresh install, first authenticated sync): the
            // business arrives via the global `businesses` pull, and its bookings must
            // land in Room within this single runSync() — no second run, no app restart.
            remote.servePage("businesses", listOf(remoteBusinessRow("biz-new", updatedAt = "2026-08-25T10:00:00+00:00")))
            remote.servePage(
                "bookings",
                listOf(remoteBookingRow("b-fresh", updatedAt = "2026-08-25T10:30:00+00:00", businessId = "biz-new")),
            )

            val outcome = syncEngine(db, remote, notifier).runSync()

            assertThat(db.bookingDao().byId("b-fresh")).isNotNull()
            assertThat(outcome.pulledCount).isEqualTo(2)
            assertThat(remote.pullCalls.single { it.first == "bookings" }.second).isEqualTo("biz-new")
        }

    @Test
    fun `business landing in room MID-RUN still gets its per-business tables pulled this run`() =
        runTest {
            // Reproduces the empty-first-pass bug: a single up-front enumeration would
            // fix the business list BEFORE `MembershipRefresher` (sign-in path) upserts
            // a business into Room concurrently — its bookings would only arrive on the
            // NEXT run. The bounded re-enumeration loop must catch it in THIS run.
            seedBusiness()
            var inserted = false
            remote.onPull = { table, _ ->
                if (table == "bookings" && !inserted) {
                    inserted = true
                    db.businessDao().upsert(
                        com.itsluminous.samaroh.core.database.entity
                            .BusinessEntity(
                                id = "biz-late",
                                name = "late-arrival",
                                ownerName = "owner",
                                ownerUserId = Fixtures.USER_ID,
                                createdAt = Fixtures.NOW,
                                updatedAt = Fixtures.NOW,
                            ),
                    )
                }
            }
            // Page 1: the seeded business's (empty) bookings pull — the hook fires here.
            remote.servePage("bookings", emptyList())
            // Page 2: the late business's bookings, only reachable via re-enumeration.
            remote.servePage(
                "bookings",
                listOf(remoteBookingRow("b-late", updatedAt = "2026-08-25T11:00:00+00:00", businessId = "biz-late")),
            )

            syncEngine(db, remote, notifier).runSync()

            assertThat(db.bookingDao().byId("b-late")).isNotNull()
            assertThat(remote.pullCalls.filter { it.first == "bookings" }.map { it.second })
                .containsExactly(Fixtures.BUSINESS_ID, "biz-late")
                .inOrder()
        }

    @Test
    fun `re-enumeration is bounded - an endless stream of new businesses cannot spin the pull forever`() =
        runTest {
            var counter = 0
            remote.onPull = { table, _ ->
                if (table == "businesses") {
                    counter++
                    db.businessDao().upsert(
                        com.itsluminous.samaroh.core.database.entity
                            .BusinessEntity(
                                id = "biz-$counter",
                                name = "spawn-$counter",
                                ownerName = "owner",
                                ownerUserId = Fixtures.USER_ID,
                                createdAt = Fixtures.NOW,
                                updatedAt = Fixtures.NOW,
                            ),
                    )
                }
            }

            syncEngine(db, remote, notifier).runSync()

            // One global `businesses` pull per pass — the run stops at the pass cap.
            assertThat(remote.pullCalls.count { it.first == "businesses" }).isEqualTo(3)
        }

    private fun remoteBusinessRow(
        id: String,
        updatedAt: String,
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("name", "remote-business")
            put("business_type", "Marriage Hall")
            put("address", JsonNull)
            put("owner_name", "remote-owner")
            put("logo_path", JsonNull)
            put("currency", "INR")
            put("invoice_prefix", "INV")
            put("invoice_counter", 0)
            put("owner_user_id", Fixtures.USER_ID)
            put("created_at", "2026-08-01T09:00:00+00:00")
            put("updated_at", updatedAt)
            put("deleted_at", JsonNull)
        }

    private fun remoteBookingRow(
        id: String,
        updatedAt: String,
        customerName: String = "remote-customer",
        totalRupees: String = "2000.00",
        deletedAt: String? = null,
        notes: String? = null,
        businessId: String = Fixtures.BUSINESS_ID,
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("business_id", businessId)
            put("event_type", "wedding")
            put("event_icon", "\uD83D\uDC92")
            put("customer_name", customerName)
            put("customer_phone", JsonNull)
            put("start_date", "2026-09-10")
            put("end_date", "2026-09-10")
            put("start_time", JsonNull)
            put("end_time", JsonNull)
            put("total_amount", totalRupees)
            put("security_deposit", "0")
            put("source", JsonNull)
            if (notes != null) put("notes", notes) else put("notes", JsonNull)
            put("status", "confirmed")
            put("gcal_event_id", JsonNull)
            put("invoice_number", JsonNull)
            put("created_by", Fixtures.USER_ID)
            put("updated_by", JsonNull)
            put("created_at", "2026-08-01T09:00:00+00:00")
            put("updated_at", updatedAt)
            if (deletedAt != null) put("deleted_at", deletedAt) else put("deleted_at", JsonNull)
        }
}

private fun Booking.toEntity(): com.itsluminous.samaroh.core.database.entity.BookingEntity =
    com.itsluminous.samaroh.core.database.entity.BookingEntity(
        id = id,
        businessId = businessId,
        eventType = eventType,
        eventIcon = eventIcon,
        customerName = customerName,
        customerPhone = customerPhone,
        startDate = startDate,
        endDate = endDate,
        startTime = startTime,
        endTime = endTime,
        totalAmountPaise = totalAmountPaise,
        securityDepositPaise = securityDepositPaise,
        source = source,
        notes = notes,
        status = status,
        gcalEventId = gcalEventId,
        invoiceNumber = invoiceNumber,
        createdBy = createdBy,
        updatedBy = updatedBy,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )
