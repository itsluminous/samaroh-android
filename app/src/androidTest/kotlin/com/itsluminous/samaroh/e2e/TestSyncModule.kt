package com.itsluminous.samaroh.e2e

import com.itsluminous.samaroh.core.data.sync.AttachmentUploader
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.data.sync.SyncStatus
import com.itsluminous.samaroh.core.sync.ConflictNotifier
import com.itsluminous.samaroh.core.sync.DataStoreSyncMetaStore
import com.itsluminous.samaroh.core.sync.NotificationConflictNotifier
import com.itsluminous.samaroh.core.sync.RoomOutboxWriter
import com.itsluminous.samaroh.core.sync.RoomSyncStatus
import com.itsluminous.samaroh.core.sync.SyncMetaStore
import com.itsluminous.samaroh.core.sync.WorkManagerSyncScheduler
import com.itsluminous.samaroh.core.sync.di.SyncModule
import com.itsluminous.samaroh.core.sync.engine.ItemImageMirror
import com.itsluminous.samaroh.core.sync.remote.RemoteStoreProvider
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Mirrors the production [SyncModule] EXCEPT the remote store, which is pinned to the
 * "unconfigured" null provider: the developer machine's `local.properties` may carry
 * real Supabase credentials, and the e2e suite must be hermetic — offline-first flows
 * only, outbox entries stay pending, nothing is pushed to a live backend.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SyncModule::class])
abstract class TestSyncModule {
    @Binds abstract fun bindOutboxWriter(impl: RoomOutboxWriter): OutboxWriter

    @Binds abstract fun bindSyncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler

    @Binds abstract fun bindSyncStatus(impl: RoomSyncStatus): SyncStatus

    @Binds abstract fun bindConflictNotifier(impl: NotificationConflictNotifier): ConflictNotifier

    @Binds abstract fun bindSyncMetaStore(impl: DataStoreSyncMetaStore): SyncMetaStore

    @BindsOptionalOf abstract fun optionalAttachmentUploader(): AttachmentUploader

    companion object {
        @Provides
        @Singleton
        fun provideRemoteStoreProvider(): RemoteStoreProvider = RemoteStoreProvider { null }

        /**
         * Hermetic no-op mirror (the prod graph binds the Storage-backed one): a
         * Retriable result keeps the op queued — matching the suite's "outbox entries
         * stay pending" contract — and nothing ever reaches remote storage.
         */
        @Provides
        @Singleton
        fun provideItemImageMirror(): ItemImageMirror =
            object : ItemImageMirror {
                override suspend fun mirror(
                    businessId: String,
                    itemId: String,
                    localPath: String,
                ): ItemImageMirror.Result = ItemImageMirror.Result.Retriable("test-unconfigured")
            }
    }
}
