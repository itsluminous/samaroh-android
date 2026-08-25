package com.itsluminous.samaroh.core.sync.di

import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.sync.RoomOutboxWriter
import com.itsluminous.samaroh.core.sync.WorkManagerSyncScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds abstract fun bindOutboxWriter(impl: RoomOutboxWriter): OutboxWriter

    @Binds abstract fun bindSyncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler
}
