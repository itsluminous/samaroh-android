package com.itsluminous.samaroh.core.sync.di

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
import com.itsluminous.samaroh.core.sync.remote.PostgrestRemoteStore
import com.itsluminous.samaroh.core.sync.remote.RemoteStore
import com.itsluminous.samaroh.core.sync.remote.RemoteStoreProvider
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds abstract fun bindOutboxWriter(impl: RoomOutboxWriter): OutboxWriter

    @Binds abstract fun bindSyncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler

    @Binds abstract fun bindSyncStatus(impl: RoomSyncStatus): SyncStatus

    @Binds abstract fun bindConflictNotifier(impl: NotificationConflictNotifier): ConflictNotifier

    @Binds abstract fun bindSyncMetaStore(impl: DataStoreSyncMetaStore): SyncMetaStore

    /** Bound by `core:google` (W1-F); while absent, attachment ops stay queued with a pending state. */
    @BindsOptionalOf abstract fun optionalAttachmentUploader(): AttachmentUploader

    companion object {
        /**
         * The remote store rides on the SHARED [SupabaseClient] from `core:auth` (Auth +
         * Postgrest installed): supabase-kt then attaches the signed-in user's access
         * token to every Postgrest call, so RLS evaluates as the user. A separate
         * Postgrest-only client would push/pull as `anon` — RLS rejects every write and
         * filters every read to nothing. Null client (Supabase unconfigured) =
         * offline-only no-op sync.
         */
        @Provides
        @Singleton
        fun provideRemoteStoreProvider(client: SupabaseClient?): RemoteStoreProvider {
            val store: RemoteStore? = client?.let { PostgrestRemoteStore(it.postgrest) }
            return RemoteStoreProvider { store }
        }
    }
}
