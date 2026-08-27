package com.itsluminous.samaroh.core.sync.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.itsluminous.samaroh.core.data.sync.AttachmentUploader
import com.itsluminous.samaroh.core.data.sync.ConflictResolution
import com.itsluminous.samaroh.core.data.sync.PostSyncHook
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.database.entity.OutboxEntity
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.sync.ConflictNotifier
import com.itsluminous.samaroh.core.sync.SyncMetaStore
import com.itsluminous.samaroh.core.sync.remote.RemoteStore
import com.itsluminous.samaroh.core.sync.remote.RemoteStoreProvider
import com.itsluminous.samaroh.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

/*
 * Shared fakes and builders for SyncEngine tests.
 */

val FIXED_NOW: Instant = Instant.parse("2026-08-25T12:00:00Z")
val FIXED_CLOCK: Clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)

val testJson = Json { encodeDefaults = true }

fun newTestDatabase(): SamarohDatabase = inMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())

class FakeRemoteStore : RemoteStore {
    val upserts = mutableListOf<Pair<String, JsonObject>>()
    val tombstones = mutableListOf<Triple<String, String, String>>()
    val pullCalls = mutableListOf<Triple<String, String?, Instant>>()

    /** Keyset id per pull call, parallel to [pullCalls] (ADR-024). */
    val pullAfterIds = mutableListOf<String?>()

    /** Last cursor column requested per table (asserts the expense_attachments created_at cursor). */
    val pullCursorColumns = mutableMapOf<String, String>()

    /** Pages served per table; each pull for a table pops one page (then empty). */
    val pullPages = mutableMapOf<String, ArrayDeque<List<JsonObject>>>()

    /** Programmable failure hook for upserts. */
    var onUpsert: ((table: String, row: JsonObject) -> Exception?)? = null

    /** Programmable failure hook for tombstone updates. */
    var onTombstone: ((table: String, id: String) -> Exception?)? = null

    /**
     * Side-effect hook invoked on every pull — lets tests simulate concurrent local
     * writes mid-run (e.g. `MembershipRefresher` upserting a business into Room while
     * the engine's pull pass is in flight).
     */
    var onPull: (suspend (table: String, businessId: String?) -> Unit)? = null

    fun servePage(
        table: String,
        rows: List<JsonObject>,
    ) {
        pullPages.getOrPut(table) { ArrayDeque() }.addLast(rows)
    }

    override suspend fun upsert(
        table: String,
        row: JsonObject,
    ) {
        onUpsert?.invoke(table, row)?.let { throw it }
        upserts += table to row
    }

    override suspend fun updateTombstone(
        table: String,
        idColumn: String,
        id: String,
        deletedAt: String,
        touchUpdatedAt: Boolean,
    ) {
        onTombstone?.invoke(table, id)?.let { throw it }
        tombstones += Triple(table, id, deletedAt)
    }

    override suspend fun pull(
        table: String,
        businessId: String?,
        after: Instant,
        afterId: String?,
        limit: Int,
        columns: String?,
        cursorColumn: String,
        idColumn: String,
    ): List<JsonObject> {
        pullCalls += Triple(table, businessId, after)
        pullAfterIds += afterId
        pullCursorColumns[table] = cursorColumn
        onPull?.invoke(table, businessId)
        return pullPages[table]?.removeFirstOrNull() ?: emptyList()
    }
}

class RecordingConflictNotifier : ConflictNotifier {
    data class Event(
        val title: String,
        val fields: List<String>,
        val resolution: ConflictResolution,
    )

    val events = mutableListOf<Event>()

    override fun notifyConflict(
        title: String,
        fields: List<String>,
        resolution: ConflictResolution,
    ) {
        events += Event(title, fields, resolution)
    }
}

class InMemorySyncMetaStore : SyncMetaStore {
    private val state = MutableStateFlow<Instant?>(null)
    override val lastSyncTime: Flow<Instant?> = state

    override suspend fun recordSyncTime(at: Instant) {
        state.value = at
    }
}

class FakeAttachmentUploader(
    private val result: AttachmentUploader.UploadResult,
) : AttachmentUploader {
    val uploaded = mutableListOf<String>()

    override suspend fun upload(attachmentId: String): AttachmentUploader.UploadResult {
        uploaded += attachmentId
        return result
    }
}

class FakeItemImageMirror(
    private val result: (itemId: String) -> ItemImageMirror.Result = { itemId ->
        ItemImageMirror.Result.Uploaded("mirrored/$itemId/1.webp")
    },
) : ItemImageMirror {
    /** Each call as (businessId, itemId, localPath). */
    val calls = mutableListOf<Triple<String, String, String>>()

    override suspend fun mirror(
        businessId: String,
        itemId: String,
        localPath: String,
    ): ItemImageMirror.Result {
        calls += Triple(businessId, itemId, localPath)
        return result(itemId)
    }
}

fun syncEngine(
    db: SamarohDatabase,
    remote: RemoteStore?,
    notifier: RecordingConflictNotifier = RecordingConflictNotifier(),
    metaStore: SyncMetaStore = InMemorySyncMetaStore(),
    uploader: AttachmentUploader? = null,
    imageMirror: ItemImageMirror = FakeItemImageMirror(),
    postSyncHooks: Set<PostSyncHook> = emptySet(),
    clock: Clock = FIXED_CLOCK,
): SyncEngine =
    SyncEngine(
        outboxDao = db.outboxDao(),
        businessDao = db.businessDao(),
        cursorDao = db.syncCursorDao(),
        conflictDao = db.syncConflictDao(),
        applier =
            LocalApplier(
                businessDao = db.businessDao(),
                businessMemberDao = db.businessMemberDao(),
                businessSettingsDao = db.businessSettingsDao(),
                googleAccountLinkDao = db.googleAccountLinkDao(),
                bookingDao = db.bookingDao(),
                dateBlockDao = db.dateBlockDao(),
                bookingPaymentDao = db.bookingPaymentDao(),
                paymentReminderDao = db.paymentReminderDao(),
                partyDao = db.partyDao(),
                expenseDao = db.expenseDao(),
                expenseAttachmentDao = db.expenseAttachmentDao(),
                masterItemDao = db.masterItemDao(),
                inventoryTransactionDao = db.inventoryTransactionDao(),
            ),
        remoteStoreProvider = RemoteStoreProvider { remote },
        attachmentUploader = Optional.ofNullable(uploader),
        itemImageMirror = imageMirror,
        conflictNotifier = notifier,
        syncMetaStore = metaStore,
        postSyncHooks = postSyncHooks,
        clock = clock,
    )

fun bookingOutboxEntry(
    booking: Booking,
    createdAt: Instant = FIXED_NOW,
): OutboxEntity =
    OutboxEntity(
        entityType = "bookings",
        entityId = booking.id,
        operation = "upsert",
        payloadJson = testJson.encodeToString(Booking.serializer(), booking),
        createdAt = createdAt,
    )
