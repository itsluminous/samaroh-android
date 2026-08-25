package com.itsluminous.samaroh.core.data.di

import com.itsluminous.samaroh.core.data.repository.ExpensesLedgerRepository
import com.itsluminous.samaroh.core.data.repository.RoomExpensesLedgerRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Bindings for the W1-B additive expenses contracts (docs/decisions.md ADR-011) — kept in
 * their own module so [DataModule] stays untouched. The `AttachmentUploadQueue` binding
 * moved to `core:google` (Drive-backed, ADR-018); the local-only placeholder is retired.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExpensesLedgerModule {
    @Binds abstract fun bindExpensesLedgerRepository(impl: RoomExpensesLedgerRepository): ExpensesLedgerRepository
}
