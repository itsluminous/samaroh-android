package com.itsluminous.samaroh.core.sync.engine

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.RemoteChangeListener
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.testing.Fixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR-047: the sync engine reports WHICH business-scoped tables a pull applied
 * (`table → business ids`) to the multibound [RemoteChangeListener]s — the signal
 * `core:google` turns into a prompt calendar push for remote members' edits. Listener
 * failures never fail the run; a run that applied nothing notifies nobody.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineRemoteChangeTest {
    private lateinit var db: SamarohDatabase
    private lateinit var remote: FakeRemoteStore

    private class RecordingListener : RemoteChangeListener {
        val notifications = mutableListOf<Map<String, Set<String>>>()

        override suspend fun onRemoteChangesApplied(appliedTables: Map<String, Set<String>>) {
            notifications += appliedTables
        }
    }

    @Before
    fun setUp() {
        db = newTestDatabase()
        remote = FakeRemoteStore()
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

    private fun remoteBookingRow(
        id: String,
        updatedAt: String,
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("business_id", Fixtures.BUSINESS_ID)
            put("event_type", "wedding")
            put("event_icon", "\uD83D\uDC92")
            put("customer_name", "remote-customer")
            put("customer_phone", JsonNull)
            put("start_date", "2026-09-10")
            put("end_date", "2026-09-10")
            put("start_time", JsonNull)
            put("end_time", JsonNull)
            put("total_amount", "2000.00")
            put("security_deposit", "0")
            put("source", JsonNull)
            put("notes", JsonNull)
            put("status", "confirmed")
            put("gcal_event_id", JsonNull)
            put("invoice_number", JsonNull)
            put("created_by", Fixtures.USER_ID)
            put("updated_by", JsonNull)
            put("created_at", updatedAt)
            put("updated_at", updatedAt)
            put("deleted_at", JsonNull)
        }

    @Test
    fun `applied booking pull notifies listeners with the table and business id`() =
        runTest {
            seedBusiness()
            remote.servePage("bookings", listOf(remoteBookingRow("b-1", "2026-09-06T10:00:00+00:00")))
            val listener = RecordingListener()

            syncEngine(db, remote, remoteChangeListeners = setOf(listener)).runSync()

            assertThat(listener.notifications).hasSize(1)
            assertThat(listener.notifications.single()["bookings"]).containsExactly(Fixtures.BUSINESS_ID)
        }

    @Test
    fun `a pull that applied nothing does not notify`() =
        runTest {
            seedBusiness()
            val listener = RecordingListener()

            syncEngine(db, remote, remoteChangeListeners = setOf(listener)).runSync()

            assertThat(listener.notifications).isEmpty()
        }

    @Test
    fun `a throwing listener never fails the sync run`() =
        runTest {
            seedBusiness()
            remote.servePage("bookings", listOf(remoteBookingRow("b-1", "2026-09-06T10:00:00+00:00")))
            val throwing = RemoteChangeListener { error("listener boom") }
            val listener = RecordingListener()

            val outcome = syncEngine(db, remote, remoteChangeListeners = setOf(throwing, listener)).runSync()

            assertThat(outcome.pulledCount).isEqualTo(1)
            assertThat(outcome.networkFailed).isFalse()
            // Later listeners still notified after an earlier one throws.
            assertThat(listener.notifications).hasSize(1)
        }
}
