package com.itsluminous.samaroh.core.sync.engine

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.testing.Fixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR-027 regression: a pulled `parties` row must carry `business_related` into Room.
 * The sync-side `Party.toEntity()` originally dropped the flag (defaulting personal
 * parties back to business-related on every pull) — caught on-device: the server row
 * held `false` while the pulled local row flipped to `true`.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEnginePartyFlagTest {
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

    private fun remotePartyRow(
        id: String,
        businessRelated: Boolean?,
    ) = buildJsonObject {
        put("id", id)
        put("business_id", Fixtures.BUSINESS_ID)
        put("name", "party-$id")
        put("phone", JsonNull)
        put("notes", JsonNull)
        put("created_at", "2026-08-27T10:00:00+00:00")
        put("updated_at", "2026-08-27T10:00:00+00:00")
        put("deleted_at", JsonNull)
        if (businessRelated != null) put("business_related", businessRelated)
    }

    @Test
    fun `pulled personal party stays personal in room`() =
        runTest {
            seedBusiness()
            remote.servePage("parties", listOf(remotePartyRow("p-personal", businessRelated = false)))

            syncEngine(db, remote, notifier).runSync()

            val party = db.partyDao().byId("p-personal")
            assertThat(party).isNotNull()
            assertThat(party!!.businessRelated).isFalse()
        }

    @Test
    fun `pulled business party stays business-related`() =
        runTest {
            seedBusiness()
            remote.servePage("parties", listOf(remotePartyRow("p-business", businessRelated = true)))

            syncEngine(db, remote, notifier).runSync()

            assertThat(db.partyDao().byId("p-business")!!.businessRelated).isTrue()
        }

    @Test
    fun `row from a pre-migration server without the column defaults to business-related`() =
        runTest {
            seedBusiness()
            remote.servePage("parties", listOf(remotePartyRow("p-legacy", businessRelated = null)))

            syncEngine(db, remote, notifier).runSync()

            assertThat(db.partyDao().byId("p-legacy")!!.businessRelated).isTrue()
        }
}
