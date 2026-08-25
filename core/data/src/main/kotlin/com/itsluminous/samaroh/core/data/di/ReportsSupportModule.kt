package com.itsluminous.samaroh.core.data.di

import com.itsluminous.samaroh.core.data.repository.ReportsRepository
import com.itsluminous.samaroh.core.data.repository.RoomReportsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binding for the W2-A additive reports contract (docs/decisions.md ADR-019) — kept in
 * its own module so [DataModule] stays untouched.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReportsSupportModule {
    @Binds abstract fun bindReportsRepository(impl: RoomReportsRepository): ReportsRepository
}
