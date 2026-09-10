package com.itsluminous.samaroh.feature.notes.sweep

import com.itsluminous.samaroh.core.data.repository.NotesRepository
import com.itsluminous.samaroh.core.data.sync.PostSyncHook
import com.itsluminous.samaroh.feature.notes.NotesSession
import java.time.Clock
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The client-side 30-day Trash purge (ADR-077): hard-tombstones (soft delete + DELETE
 * push) every live TRASHED note whose `trashed_at` is older than 30 days. Runs after
 * every completed sync pull (the [NotesPostSyncHook] multibinding — which also covers
 * app start, since the startup sync nudge triggers a run) and when the Notes tab
 * opens (offline-only installs never sync). Gated on `notes.delete` — the same
 * client-side gate as "delete forever" (owners pass implicitly).
 */
@Singleton
class NotesTrashSweeper
    @Inject
    constructor(
        private val repository: NotesRepository,
        private val session: NotesSession,
        private val clock: Clock,
    ) {
        /** @return purged note count (0 when gated off, no business, or nothing expired). */
        suspend fun sweep(): Int {
            val businessId = session.businessId() ?: return 0
            if (!session.canDeleteNow()) return 0
            val cutoff = clock.instant().minus(PURGE_AFTER_DAYS, ChronoUnit.DAYS)
            return repository.purgeTrashedBefore(businessId, cutoff)
        }

        companion object {
            /** "Items in trash are removed after 30 days" (notes.trash.notice). */
            const val PURGE_AFTER_DAYS = 30L
        }
    }

/** Runs the Trash sweep after every completed sync pull (ADR-024 hook contract). */
class NotesPostSyncHook
    @Inject
    constructor(
        private val sweeper: NotesTrashSweeper,
    ) : PostSyncHook {
        override suspend fun onSyncApplied() {
            sweeper.sweep()
        }
    }
