package com.itsluminous.samaroh.feature.menu.data

import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.sync.SyncItemError
import com.itsluminous.samaroh.core.data.sync.SyncStatus
import com.itsluminous.samaroh.core.data.sync.SyncStatusProvider
import com.itsluminous.samaroh.core.database.dao.OutboxDao
import com.itsluminous.samaroh.core.model.Business
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FALLBACK [SyncStatusProvider] reading the outbox directly: the pending count and
 * per-item errors are real; `lastSyncAt` stays null until the W1-E sync engine provides
 * the authoritative implementation.
 *
 * INTEGRATOR NOTE (docs/decisions.md ADR-007): when `core:sync` binds its own
 * `SyncStatusProvider`, drop the binding in
 * [com.itsluminous.samaroh.feature.menu.di.MenuModule] — this class then simply goes
 * unused.
 */
@Singleton
class OutboxSyncStatusProvider
    @Inject
    constructor(
        private val outboxDao: OutboxDao,
    ) : SyncStatusProvider {
        override val status: Flow<SyncStatus> =
            outboxDao.pendingCount().map { count ->
                val errors =
                    outboxDao
                        .nextBatch(limit = ERROR_SCAN_LIMIT)
                        .filter { !it.lastError.isNullOrBlank() }
                        .map { entry ->
                            SyncItemError(
                                entityType = entry.entityType,
                                entityId = entry.entityId,
                                operation = entry.operation,
                                message = entry.lastError.orEmpty(),
                                attemptCount = entry.attemptCount,
                            )
                        }
                SyncStatus(pendingCount = count, errors = errors, lastSyncAt = null)
            }

        private companion object {
            const val ERROR_SCAN_LIMIT = 100
        }
    }

/**
 * The business the Menu tab operates on. v1 is single-business per §4.0 onboarding; the
 * first (alphabetical) live business is "current". Replace with a real selection holder
 * if multi-business ever lands.
 */
@Singleton
class CurrentBusinessProvider
    @Inject
    constructor(
        businessRepository: BusinessRepository,
    ) {
        val currentBusiness: Flow<Business?> = businessRepository.businesses().map { it.firstOrNull() }
    }
