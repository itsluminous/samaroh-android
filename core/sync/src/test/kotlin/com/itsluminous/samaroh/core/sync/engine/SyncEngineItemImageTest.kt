package com.itsluminous.samaroh.core.sync.engine

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.database.entity.OutboxEntity
import com.itsluminous.samaroh.core.model.MasterItem
import com.itsluminous.samaroh.core.testing.Fixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR-023: local item photos are mirrored to Storage before their `master_items` upsert
 * pushes — a device-local file path must never reach the server.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineItemImageTest {
    private lateinit var db: SamarohDatabase
    private lateinit var remote: FakeRemoteStore

    @Before
    fun setUp() {
        db = newTestDatabase()
        remote = FakeRemoteStore()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun masterItemEntry(item: MasterItem): OutboxEntity =
        OutboxEntity(
            entityType = "master_items",
            entityId = item.id,
            operation = "upsert",
            payloadJson = testJson.encodeToString(MasterItem.serializer(), item),
            createdAt = FIXED_NOW,
        )

    @Test
    fun `local photo is uploaded and the pushed row carries the storage path`() =
        runTest {
            val item = Fixtures.masterItem(id = "item-1").copy(imagePath = "/data/user/0/app/files/inventory-images/item-1.webp")
            db.outboxDao().enqueue(masterItemEntry(item))
            val mirror = FakeItemImageMirror { itemId -> ItemImageMirror.Result.Uploaded("biz/$itemId/12345.webp") }

            val outcome = syncEngine(db, remote, imageMirror = mirror).runSync()

            assertThat(outcome.pushedCount).isEqualTo(1)
            assertThat(mirror.calls).containsExactly(
                Triple(item.businessId, "item-1", "/data/user/0/app/files/inventory-images/item-1.webp"),
            )
            val row = remote.upserts.single().second
            assertThat(row.getValue("image_path").jsonPrimitive.content).isEqualTo("biz/item-1/12345.webp")
            // Room converged on the storage path too — the UI now loads via Storage.
            val stored =
                db
                    .masterItemDao()
                    .itemsForBusiness(item.businessId)
                    .first()
                    .single { it.id == "item-1" }
            assertThat(stored.imagePath).isEqualTo("biz/item-1/12345.webp")
            assertThat(db.outboxDao().nextBatch()).isEmpty()
        }

    @Test
    fun `storage path payload pushes as-is without invoking the mirror`() =
        runTest {
            val item = Fixtures.masterItem(id = "item-2").copy(imagePath = "biz/item-2/from-web.webp")
            db.outboxDao().enqueue(masterItemEntry(item))
            val mirror = FakeItemImageMirror()

            syncEngine(db, remote, imageMirror = mirror).runSync()

            assertThat(mirror.calls).isEmpty()
            val row = remote.upserts.single().second
            assertThat(row.getValue("image_path").jsonPrimitive.content).isEqualTo("biz/item-2/from-web.webp")
        }

    @Test
    fun `item without a photo pushes without invoking the mirror`() =
        runTest {
            db.outboxDao().enqueue(masterItemEntry(Fixtures.masterItem(id = "item-3")))
            val mirror = FakeItemImageMirror()

            syncEngine(db, remote, imageMirror = mirror).runSync()

            assertThat(mirror.calls).isEmpty()
            assertThat(remote.upserts).hasSize(1)
        }

    @Test
    fun `missing local file pushes the row without a photo`() =
        runTest {
            val item = Fixtures.masterItem(id = "item-4").copy(imagePath = "/gone/item-4.webp")
            db.outboxDao().enqueue(masterItemEntry(item))
            val mirror = FakeItemImageMirror { ItemImageMirror.Result.MissingFile }

            val outcome = syncEngine(db, remote, imageMirror = mirror).runSync()

            assertThat(outcome.pushedCount).isEqualTo(1)
            assertThat(
                remote.upserts
                    .single()
                    .second
                    .getValue("image_path"),
            ).isEqualTo(JsonNull)
        }

    @Test
    fun `retriable upload failure keeps the op queued as a pending error`() =
        runTest {
            val item = Fixtures.masterItem(id = "item-5").copy(imagePath = "/local/item-5.webp")
            db.outboxDao().enqueue(masterItemEntry(item))
            val mirror = FakeItemImageMirror { ItemImageMirror.Result.Retriable("image-upload-network") }

            val outcome = syncEngine(db, remote, imageMirror = mirror).runSync()

            assertThat(outcome.pushedCount).isEqualTo(0)
            assertThat(outcome.itemErrorCount).isEqualTo(1)
            assertThat(remote.upserts).isEmpty()
            val errored = db.outboxDao().erroredEntries().first()
            assertThat(errored.single().lastError).isEqualTo("image-upload-network")
            // The payload still carries the local path — the next run retries the upload.
            assertThat(errored.single().payloadJson).contains("/local/item-5.webp")
        }

    @Test
    fun `rejected upload marks the item errored without blocking other entities`() =
        runTest {
            val failing = Fixtures.masterItem(id = "item-6").copy(imagePath = "/local/item-6.webp")
            val clean = Fixtures.masterItem(id = "item-7")
            db.outboxDao().enqueue(masterItemEntry(failing))
            db.outboxDao().enqueue(masterItemEntry(clean))
            val mirror = FakeItemImageMirror { ItemImageMirror.Result.Rejected("row-level security violation") }

            val outcome = syncEngine(db, remote, imageMirror = mirror).runSync()

            assertThat(outcome.pushedCount).isEqualTo(1)
            assertThat(outcome.itemErrorCount).isEqualTo(1)
            assertThat(
                remote.upserts
                    .single()
                    .second
                    .getValue("id")
                    .jsonPrimitive.content,
            ).isEqualTo("item-7")
        }
}
