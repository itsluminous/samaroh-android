package com.itsluminous.samaroh.feature.expenses.addentry

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.attachments.AttachmentUploadQueue
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.feature.expenses.FakeExpensesLedgerRepository
import com.itsluminous.samaroh.feature.expenses.FakeExpensesRepository
import com.itsluminous.samaroh.feature.expenses.attachments.AttachmentCompressor
import com.itsluminous.samaroh.feature.expenses.fakeExpensesSession
import com.itsluminous.samaroh.feature.expenses.ledger.ARG_PARTY_ID
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class AddEntryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class RecordingUploadQueue : AttachmentUploadQueue {
        val enqueued = mutableListOf<Pair<String, String>>()

        override suspend fun enqueue(
            localPath: String,
            expenseId: String,
        ) {
            enqueued += localPath to expenseId
        }
    }

    private val partyId = "party-1"
    private lateinit var context: Context
    private lateinit var expensesRepository: FakeExpensesRepository
    private lateinit var ledgerRepository: FakeExpensesLedgerRepository
    private lateinit var uploadQueue: RecordingUploadQueue

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        expensesRepository = FakeExpensesRepository()
        ledgerRepository = FakeExpensesLedgerRepository()
        uploadQueue = RecordingUploadQueue()
    }

    private fun viewModel(direction: ExpenseDirection = ExpenseDirection.PAID) =
        AddEntryViewModel(
            savedStateHandle = SavedStateHandle(mapOf(ARG_PARTY_ID to partyId, ARG_DIRECTION to direction.wire)),
            expensesRepository = expensesRepository,
            ledgerRepository = ledgerRepository,
            uploadQueue = uploadQueue,
            compressor = AttachmentCompressor(context, ioDispatcher = mainDispatcherRule.dispatcher),
            session = fakeExpensesSession(),
            clock = Clock.fixed(Fixtures.NOW, ZoneOffset.UTC),
        )

    @Test
    fun `defaults to today and the routed direction`() {
        val viewModel = viewModel(ExpenseDirection.RECEIVED)
        assertThat(viewModel.state.value.direction).isEqualTo(ExpenseDirection.RECEIVED)
        assertThat(viewModel.state.value.date).isEqualTo(LocalDate.ofInstant(Fixtures.NOW, ZoneOffset.systemDefault()))
    }

    @Test
    fun `invalid amount flags error and saves nothing`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onAmountChange("abc")
            viewModel.save()
            assertThat(viewModel.state.value.amountError).isTrue()
            assertThat(expensesRepository.expenses.value).isEmpty()
        }

    @Test
    fun `save without attachments persists the expense in paise and finishes`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onAmountChange("500.50")
            viewModel.onNotesChange("  cement bags  ")
            viewModel.save()

            val saved = expensesRepository.expenses.value.single()
            assertThat(saved.amountPaise).isEqualTo(50_050L)
            assertThat(saved.partyId).isEqualTo(partyId)
            assertThat(saved.direction).isEqualTo(ExpenseDirection.PAID)
            assertThat(saved.notes).isEqualTo("cement bags")
            assertThat(viewModel.state.value.showGooglePrompt).isFalse()
        }

    @Test
    fun `save with attachments persists pending metadata, queues uploads and prompts for Google`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onAmountChange("100")
            viewModel.onAttachmentPicked(imageUri(), "image/png", "bill.png")
            assertThat(viewModel.state.value.attachments).hasSize(1)

            viewModel.save()

            val expense = expensesRepository.expenses.value.single()
            val (attachment, localPath) = ledgerRepository.savedAttachments.single()
            assertThat(attachment.expenseId).isEqualTo(expense.id)
            assertThat(attachment.driveFileId).isNull() // pending state
            assertThat(localPath).isNotNull()
            assertThat(uploadQueue.enqueued).containsExactly(localPath!! to expense.id)
            assertThat(viewModel.state.value.showGooglePrompt).isTrue()

            viewModel.dismissGooglePrompt()
            assertThat(viewModel.state.value.showGooglePrompt).isFalse()
        }

    @Test
    fun `attachment limit is enforced`() =
        runTest {
            val viewModel = viewModel()
            repeat(MAX_ATTACHMENTS) { viewModel.onAttachmentPicked(imageUri(), "image/png", "bill-$it.png") }
            assertThat(viewModel.state.value.attachments).hasSize(MAX_ATTACHMENTS)

            viewModel.onAttachmentPicked(imageUri(), "image/png", "one-too-many.png")
            assertThat(viewModel.state.value.attachments).hasSize(MAX_ATTACHMENTS)
        }

    private fun imageUri(): Uri {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile("source", ".png", context.cacheDir)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }
}
