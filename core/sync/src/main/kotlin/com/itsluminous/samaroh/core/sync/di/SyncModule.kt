package com.itsluminous.samaroh.core.sync.di

import com.itsluminous.samaroh.core.data.session.SessionScopedStore
import com.itsluminous.samaroh.core.data.sync.AttachmentPermissionRepair
import com.itsluminous.samaroh.core.data.sync.AttachmentUploader
import com.itsluminous.samaroh.core.data.sync.ItemPhotoDriveMirror
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.data.sync.PostSyncHook
import com.itsluminous.samaroh.core.data.sync.RemoteChangeListener
import com.itsluminous.samaroh.core.data.sync.ReplicaIntegrity
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.data.sync.SyncStatus
import com.itsluminous.samaroh.core.sync.ConflictNotifier
import com.itsluminous.samaroh.core.sync.DataStoreSyncMetaStore
import com.itsluminous.samaroh.core.sync.DefaultReplicaIntegrity
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
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
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

    /** "Is the local replica a consistent snapshot?" — gates destructive planning (ADR-060). */
    @Binds abstract fun bindReplicaIntegrity(impl: DefaultReplicaIntegrity): ReplicaIntegrity

    /** Sign-out wipes the sync metadata (last-sync time) with the rest of the local data (ADR-040). */
    @Binds
    @IntoSet
    abstract fun bindSyncMetaSessionScopedStore(impl: DataStoreSyncMetaStore): SessionScopedStore

    /** Bound by `core:google` (W1-F); while absent, attachment ops stay queued with a pending state. */
    @BindsOptionalOf abstract fun optionalAttachmentUploader(): AttachmentUploader

    /** Bound by `core:google` (ADR-055); while absent, item photos simply gain no Drive copy. */
    @BindsOptionalOf abstract fun optionalItemPhotoDriveMirror(): ItemPhotoDriveMirror

    /** Bound by `core:google` (ADR-059); while absent, bill link-permissions simply stay pending. */
    @BindsOptionalOf abstract fun optionalAttachmentPermissionRepair(): AttachmentPermissionRepair

    /** Feature modules contribute [PostSyncHook]s via `@IntoSet`; valid even when none do (ADR-024). */
    @Multibinds abstract fun postSyncHooks(): Set<PostSyncHook>

    /** Modules contribute [RemoteChangeListener]s via `@IntoSet`; valid even when none do (ADR-047). */
    @Multibinds abstract fun remoteChangeListeners(): Set<RemoteChangeListener>

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
