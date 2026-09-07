package com.itsluminous.samaroh.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Per-use-case image ENCODER QUALITY preferences (ADR-053) in the shared settings
 * DataStore. The Settings screen writes them; the two compression call sites
 * (expense-attachment compressor, inventory item-photo store) read them when building
 * their [com.itsluminous.samaroh.core.designsystem.imaging] `CompressionSpec` — only the
 * quality is user-tunable, the per-use-case dimension caps and formats stay fixed
 * (ADR-050).
 *
 * Values are raw 0–100 encoder qualities, coerced into a per-use-case band on READ so a
 * stale/corrupt stored value can never produce an unreadable bill or a bloated
 * thumbnail. Unset means the default (bills 90 per ADR-050; item photos 30 — the
 * Space-saver level — per ADR-056).
 */
@Singleton
class ImageQualityPreferences
    @Inject
    constructor(
        @SettingsDataStore private val dataStore: DataStore<Preferences>,
    ) {
        /** Invoice/bill attachment encoder quality, always within [BILLS_MIN]..[BILLS_MAX]. */
        val billsQuality: Flow<Int> =
            dataStore.data.map { prefs -> (prefs[KEY_BILLS_QUALITY] ?: BILLS_DEFAULT).coerceIn(BILLS_MIN, BILLS_MAX) }

        /** Inventory item-photo encoder quality, always within [ITEM_MIN]..[ITEM_MAX]. */
        val itemPhotoQuality: Flow<Int> =
            dataStore.data.map { prefs -> (prefs[KEY_ITEM_QUALITY] ?: ITEM_DEFAULT).coerceIn(ITEM_MIN, ITEM_MAX) }

        suspend fun setBillsQuality(quality: Int) {
            dataStore.edit { it[KEY_BILLS_QUALITY] = quality.coerceIn(BILLS_MIN, BILLS_MAX) }
        }

        suspend fun setItemPhotoQuality(quality: Int) {
            dataStore.edit { it[KEY_ITEM_QUALITY] = quality.coerceIn(ITEM_MIN, ITEM_MAX) }
        }

        companion object {
            val KEY_BILLS_QUALITY = intPreferencesKey("image_quality_bills")
            val KEY_ITEM_QUALITY = intPreferencesKey("image_quality_item_photos")

            // Bills must stay readable (documents): a narrow, high band (ADR-050 default 90).
            const val BILLS_DEFAULT = 90
            const val BILLS_MIN = 60
            const val BILLS_MAX = 100

            // Item photos render as small thumbnails: a wide, lower band. Default is the
            // Space-saver level (ADR-056) — thumbnails tolerate strong compression, and
            // the owner prefers minimal storage/bandwidth out of the box.
            const val ITEM_DEFAULT = 30
            const val ITEM_MIN = 30
            const val ITEM_MAX = 90

            /** The three plain-language levels the Settings chips offer per use case. */
            val BILLS_LEVELS = QualityLevels(high = 90, balanced = 75, low = 60)
            val ITEM_LEVELS = QualityLevels(high = 75, balanced = 50, low = 30)
        }
    }

/** The chip-level quality mapping of one use case (Settings → Image quality). */
data class QualityLevels(
    val high: Int,
    val balanced: Int,
    val low: Int,
) {
    /**
     * The level value closest to [quality] — keeps a chip visibly selected even if a
     * stored value doesn't exactly match a level (future finer-grained control).
     */
    fun nearest(quality: Int): Int = listOf(high, balanced, low).minBy { abs(it - quality) }
}
