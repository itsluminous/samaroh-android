package com.itsluminous.samaroh.feature.menu.data

import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.model.Business
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Wave-1 integration note: the outbox-backed `OutboxSyncStatusProvider` fallback that
 * lived here (with the `SyncStatusProvider` contract, ADR-013) was deleted — the Menu
 * tab's sync-status screen now consumes the real `core:data` `SyncStatus` API implemented
 * by `core:sync` (`RoomSyncStatus`, ADR-008).
 */

/**
 * The business the Menu tab operates on — thin façade over the app-wide
 * [ActiveBusinessProvider] session seam (docs/decisions.md ADR-017), kept so existing
 * menu view models keep their injection point.
 */
@Singleton
class CurrentBusinessProvider
    @Inject
    constructor(
        activeBusinessProvider: ActiveBusinessProvider,
    ) {
        val currentBusiness: Flow<Business?> = activeBusinessProvider.activeBusiness
    }
