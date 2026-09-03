package com.itsluminous.samaroh.core.data.reminders

/**
 * Fires one SAMPLE booking reminder through the REAL reminder pipeline (ADR-045) —
 * the same notifier, channels, style resolution and (for the full-screen style) the
 * same exact-alarm receiver that production reminders use — so the owner can preview
 * exactly what a reminder will look and sound like with the currently selected
 * style + sound.
 *
 * Cross-feature contract: implemented in `feature:booking` (which owns the reminder
 * engine), consumed by `feature:menu`'s reminder-settings screen. Same pattern as
 * [com.itsluminous.samaroh.core.data.sync.SyncStatus] (implemented in `core:sync`,
 * consumed by menu).
 */
interface ReminderTestFirer {
    /**
     * Fires the sample using the current per-device reminder prefs. Best-effort like
     * every notification: silently no-ops when POST_NOTIFICATIONS is denied. Callers
     * that want to warn about a missing full-screen-intent grant must check BEFORE
     * calling (the system, not the app, decides the demotion).
     */
    suspend fun fireSample()
}
