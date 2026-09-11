package com.itsluminous.samaroh.feature.menu.ui.settings

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.SyncItemError
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.core.testing.inMemoryDatabase
import com.itsluminous.samaroh.feature.menu.fakes.FakeSyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Discard-change flow on the Sync status screen (ADR-080). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SyncStatusViewModelTest {
    @get:Rule val dispatcherRule = MainDispatcherRule()

    private lateinit var db: SamarohDatabase
    private lateinit var syncStatus: FakeSyncStatus
    private lateinit var viewModel: SyncStatusViewModel

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        syncStatus = FakeSyncStatus()
        viewModel = SyncStatusViewModel(syncStatus, SyncEntryDisplayResolver(db.syncDisplayDao()))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `error rows surface with their outbox id`() =
        runTest(dispatcherRule.dispatcher) {
            syncStatus.itemErrorsFlow.value =
                listOf(
                    SyncItemError(
                        outboxId = 7L,
                        entityType = "businesses",
                        entityId = "biz-1",
                        operation = OutboxOperation.UPSERT,
                        message = "Not allowed - your account does not have permission for this change.",
                        attemptCount = 3,
                        payloadJson = """{"id":"biz-1","name":"Sharma Palace"}""",
                    ),
                )

            // Display resolution runs suspend DAO lookups on Room's own executor, so
            // await the first emission instead of racing runCurrent() against it.
            val errors =
                viewModel.status
                    .filterNotNull()
                    .first()
                    .errors
            assertThat(errors).hasSize(1)
            assertThat(errors.single().outboxId).isEqualTo(7L)
        }

    @Test
    fun `discardError removes the item and confirms with a snackbar`() =
        runTest(dispatcherRule.dispatcher) {
            syncStatus.itemErrorsFlow.value =
                listOf(
                    SyncItemError(
                        outboxId = 7L,
                        entityType = "businesses",
                        entityId = "biz-1",
                        operation = OutboxOperation.UPSERT,
                        message = "rejected",
                        attemptCount = 3,
                        payloadJson = """{"id":"biz-1"}""",
                    ),
                )
            // Await the error row landing in the shared state (resolver hops through
            // Room's executor, outside the test scheduler).
            assertThat(
                viewModel.status
                    .filterNotNull()
                    .first()
                    .errors,
            ).hasSize(1)

            viewModel.discardError(7L)

            // The discard runs in viewModelScope; await the state converging to
            // empty errors — that emission can only follow the discard completing.
            val after = viewModel.status.filterNotNull().first { it.errors.isEmpty() }
            assertThat(after.errors).isEmpty()
            assertThat(syncStatus.discardedIds).containsExactly(7L)
            assertThat(viewModel.message.value).isEqualTo(R.string.settings_sync_discarded)

            viewModel.onMessageShown()
            assertThat(viewModel.message.value).isNull()
        }
}
