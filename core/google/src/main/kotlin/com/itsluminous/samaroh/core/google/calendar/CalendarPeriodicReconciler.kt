package com.itsluminous.samaroh.core.google.calendar

import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.sync.RemoteChangeListener
import com.itsluminous.samaroh.core.database.dao.GoogleAccountLinkDao
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reconciles the 6-hour periodic calendar catch-up with LOCAL state (ADR-066). The
 * periodic job used to be scheduled ONLY from the Settings gcal toggle
 * ([com.itsluminous.samaroh.feature.menu.ui.settings] → [CalendarSyncScheduler.ensurePeriodicSync]),
 * so a reinstall, a sign-in on a new device, or a sync-delivered enable (another member
 * flipping the toggle) never scheduled it — offline booking edits then NEVER reached the
 * calendar because the on-change triggers (ADR-046/047) cover mutations, not the backlog.
 *
 * Runs from two cheap, network-free trigger points, mirroring the data-sync (§8) and
 * reminder (ADR-024) startup registrations:
 *  - every process ON_START via [CalendarSyncStartupInitializer];
 *  - after any sync run that applied `business_settings` rows (the multibound
 *    [RemoteChangeListener] hook, same mechanism as ADR-047) — a remote enable/disable
 *    takes effect without waiting for the next app start.
 *
 * Decision per business (all reads are local Room — no network):
 *  - gcal enabled AND a Google account row exists → [CalendarSyncScheduler.ensurePeriodicSync]
 *    (idempotent, KEEP policy);
 *  - gcal disabled → [CalendarSyncScheduler.cancelPeriodicSync] (a sync-delivered disable
 *    must not leave a zombie periodic job; the Settings disable path already cancels);
 *  - enabled but NOT linked → leave as-is: scheduling would only burn quiet skips
 *    ([CalendarSyncWorker.resolveFailure]), and the link flow kicks a fresh sync + the
 *    next reconcile pass schedules it.
 *
 * The link check is [GoogleAccountLinkDao.hasAnyLink], NOT the session-derived
 * [com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker.linkState]: at process
 * ON_START the Supabase session restore has not completed yet, so a session-gated state
 * races to "not linked" and the startup pass silently no-ops (observed on-device).
 * Sign-out wipes all local data (ADR-040), so any `google_accounts` row belongs to the
 * currently signed-in user.
 */
@Singleton
class CalendarPeriodicReconciler
    @Inject
    constructor(
        private val businessRepository: BusinessRepository,
        private val linkDao: GoogleAccountLinkDao,
        private val scheduler: CalendarSyncScheduler,
    ) : RemoteChangeListener {
        suspend fun reconcile() {
            val linked = linkDao.hasAnyLink()
            for (business in businessRepository.businesses().first()) {
                val enabled = businessRepository.settings(business.id).first()?.gcalSyncEnabled == true
                when {
                    enabled && linked -> scheduler.ensurePeriodicSync(business.id)
                    !enabled -> scheduler.cancelPeriodicSync(business.id)
                    // enabled && !linked: leave existing state untouched (see class doc).
                }
            }
        }

        /** A pulled `business_settings` row (remote enable/disable) re-runs the reconcile. */
        override suspend fun onRemoteChangesApplied(appliedTables: Map<String, Set<String>>) {
            if (SETTINGS_TABLE in appliedTables) reconcile()
        }

        private companion object {
            const val SETTINGS_TABLE = "business_settings"
        }
    }
