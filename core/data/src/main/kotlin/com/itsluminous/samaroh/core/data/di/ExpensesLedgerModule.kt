package com.itsluminous.samaroh.core.data.di

import com.itsluminous.samaroh.core.data.attachments.AttachmentUploadQueue
import com.itsluminous.samaroh.core.data.attachments.LocalOnlyAttachmentUploadQueue
import com.itsluminous.samaroh.core.data.repository.ExpensesLedgerRepository
import com.itsluminous.samaroh.core.data.repository.RoomExpensesLedgerRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Bindings for the W1-B additive expenses contracts (docs/decisions.md ADR-007) — kept in
 * their own module so [DataModule] stays untouched. The [AttachmentUploadQueue] binding is
 * a local-only placeholder; `core:google` (W1-F) supersedes it with the Drive uploader at
 * integration.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExpensesLedgerModule {
    @Binds abstract fun bindExpensesLedgerRepository(impl: RoomExpensesLedgerRepository): ExpensesLedgerRepository

    @Binds abstract fun bindAttachmentUploadQueue(impl: LocalOnlyAttachmentUploadQueue): AttachmentUploadQueue
}
