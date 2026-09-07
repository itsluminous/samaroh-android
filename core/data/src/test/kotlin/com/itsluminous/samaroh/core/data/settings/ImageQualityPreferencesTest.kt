package com.itsluminous.samaroh.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Image-quality preference contract (ADR-053, defaults per ADR-056): the exact DataStore
 * keys, defaults when unset (bills 90 = High, item photos 30 = Space saver), and
 * per-use-case band coercion on both read and write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImageQualityPreferencesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private val storeScope = CoroutineScope(dispatcher + Job())

    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tmp.root, "settings.preferences_pb")
        }
    }
    private val prefs by lazy { ImageQualityPreferences(dataStore) }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun `unset values fall back to the defaults - bills High, item photos Space saver`() =
        testScope.runTest {
            assertThat(prefs.billsQuality.first()).isEqualTo(ImageQualityPreferences.BILLS_DEFAULT)
            assertThat(prefs.itemPhotoQuality.first()).isEqualTo(ImageQualityPreferences.ITEM_DEFAULT)
            // Pin the literal values: bills stay q90 (High), item photos default q30
            // (Space saver, the lowest chip) — ADR-056.
            assertThat(ImageQualityPreferences.BILLS_DEFAULT).isEqualTo(90)
            assertThat(ImageQualityPreferences.ITEM_DEFAULT).isEqualTo(30)
        }

    @Test
    fun `set values round-trip under the contract keys`() =
        testScope.runTest {
            prefs.setBillsQuality(60)
            prefs.setItemPhotoQuality(75)

            assertThat(prefs.billsQuality.first()).isEqualTo(60)
            assertThat(prefs.itemPhotoQuality.first()).isEqualTo(75)
            // The KEYS are the cross-feature contract — assert them literally.
            val raw = dataStore.data.first()
            assertThat(raw[ImageQualityPreferences.KEY_BILLS_QUALITY]).isEqualTo(60)
            assertThat(raw[ImageQualityPreferences.KEY_ITEM_QUALITY]).isEqualTo(75)
        }

    @Test
    fun `writes are coerced into the per-use-case bands`() =
        testScope.runTest {
            prefs.setBillsQuality(5)
            prefs.setItemPhotoQuality(1_000)

            assertThat(prefs.billsQuality.first()).isEqualTo(ImageQualityPreferences.BILLS_MIN)
            assertThat(prefs.itemPhotoQuality.first()).isEqualTo(ImageQualityPreferences.ITEM_MAX)
        }

    @Test
    fun `out-of-band STORED values are coerced on read`() =
        testScope.runTest {
            // Simulate a corrupt/stale stored value written outside the setters.
            dataStore.edit {
                it[ImageQualityPreferences.KEY_BILLS_QUALITY] = 0
                it[ImageQualityPreferences.KEY_ITEM_QUALITY] = 999
            }

            assertThat(prefs.billsQuality.first()).isEqualTo(ImageQualityPreferences.BILLS_MIN)
            assertThat(prefs.itemPhotoQuality.first()).isEqualTo(ImageQualityPreferences.ITEM_MAX)
        }

    @Test
    fun `level values sit inside their own bands`() {
        val bills = ImageQualityPreferences.BILLS_LEVELS
        val items = ImageQualityPreferences.ITEM_LEVELS
        for (level in listOf(bills.high, bills.balanced, bills.low)) {
            assertThat(level).isIn(ImageQualityPreferences.BILLS_MIN..ImageQualityPreferences.BILLS_MAX)
        }
        for (level in listOf(items.high, items.balanced, items.low)) {
            assertThat(level).isIn(ImageQualityPreferences.ITEM_MIN..ImageQualityPreferences.ITEM_MAX)
        }
        // The defaults ARE selectable levels — the default chip renders selected.
        assertThat(bills.high).isEqualTo(ImageQualityPreferences.BILLS_DEFAULT)
        assertThat(items.low).isEqualTo(ImageQualityPreferences.ITEM_DEFAULT)
    }

    @Test
    fun `nearest level maps any stored quality to a visible chip`() {
        val levels = QualityLevels(high = 90, balanced = 75, low = 60)
        assertThat(levels.nearest(90)).isEqualTo(90)
        assertThat(levels.nearest(84)).isEqualTo(90)
        assertThat(levels.nearest(70)).isEqualTo(75)
        assertThat(levels.nearest(61)).isEqualTo(60)
        assertThat(levels.nearest(100)).isEqualTo(90)
    }
}
