package com.itsluminous.samaroh.feature.expenses.ledger

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.repository.AttachmentWithLocalState
import com.itsluminous.samaroh.core.data.repository.ExpensesLedgerRepository
import com.itsluminous.samaroh.core.data.repository.ExpensesRepository
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.GoogleLinkException
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.model.Party
import com.itsluminous.samaroh.feature.expenses.ExpensesSession
import com.itsluminous.samaroh.feature.expenses.attachments.AttachmentContentResolver
import com.itsluminous.samaroh.feature.expenses.attachments.AttachmentDeleter
import com.itsluminous.samaroh.feature.expenses.attachments.AttachmentOpenResult
import com.itsluminous.samaroh.feature.expenses.domain.FuzzyNameMatcher
import com.itsluminous.samaroh.feature.expenses.domain.LedgerRow
import com.itsluminous.samaroh.feature.expenses.domain.RunningBalanceCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock
import javax.inject.Inject

/** Route argument name for the party id (shared by ledger and add-entry destinations). */
const val ARG_PARTY_ID = "partyId"

data class PartyLedgerState(
    val party: Party? = null,
    /** Newest-first entries annotated with the balance after each (§4.2). */
    val rows: List<LedgerRow> = emptyList(),
    /** expenseId → its live attachments (thumbnails + pending badges). */
    val attachmentsByExpense: Map<String, List<AttachmentWithLocalState>> = emptyMap(),
    /** Net balance = the newest row's balance-after (0 when no entries). */
    val netBalancePaise: Long = 0,
    /** Drives the PermissionGate around edit/delete (`expenses.edit`, owner-mode default). */
    val canEditEntries: Boolean = true,
    /** `expenses.create` gate: shows the You gave / You got buttons. */
    val canCreateEntries: Boolean = true,
    /** Entry-delete gate: `expenses.delete`. */
    val canDeleteEntries: Boolean = true,
    /** Party-edit gate (ADR-028): `expenses.edit` OR `expenses.manage_parties`. */
    val canEditParty: Boolean = true,
    /** Party-delete gate (ADR-028): `expenses.delete`. */
    val canDeleteParty: Boolean = true,
    /** ADR-039 gate: `expenses.view_amounts`; masks entry amounts and balances as ₹••• when false. */
    val canViewAmounts: Boolean = true,
    /** Active business display name for the edit-party "Associated with {business}?" pill. */
    val businessName: String = "",
    /**
     * Google is configured but the signed-in user has no linked account — pending
     * attachment badges point at the Settings link flow instead of a bare "waiting".
     */
    val googleUnlinked: Boolean = false,
    val loaded: Boolean = false,
)

/** Validation outcome of an edit-party save attempt (ADR-028). */
enum class EditPartyError {
    /** Trimmed name is empty. */
    EMPTY_NAME,

    /** Another live party of the business already carries this (normalized) name. */
    DUPLICATE_NAME,
}

sealed interface PartyLedgerEvent {
    /** The edit-party dialog saved successfully — close it. */
    data object PartySaved : PartyLedgerEvent

    /** The party (and its cascade) was deleted — toast + navigate back to the list. */
    data class PartyDeleted(
        val partyName: String,
    ) : PartyLedgerEvent

    /** A tapped attachment resolved to a local file — image → in-app viewer, else ACTION_VIEW (ADR-052). */
    data class OpenAttachment(
        val file: File,
        val mimeType: String,
        /** The tapped row — the viewer's download/delete actions need its metadata (ADR-053). */
        val attachment: AttachmentWithLocalState,
    ) : PartyLedgerEvent

    /** A bill image was deleted from the viewer — the screen closes it and confirms. */
    data object AttachmentDeleted : PartyLedgerEvent

    /** Tapped attachment has neither a local file nor a Drive copy yet (pending on another device). */
    data object AttachmentUnavailable : PartyLedgerEvent

    /** Drive download of a tapped attachment failed (typically offline) — friendly retry message. */
    data object AttachmentDownloadFailed : PartyLedgerEvent

    /** The link-Google flow launched from the view-attachment dialog failed (not a cancel). */
    data object GoogleLinkFailed : PartyLedgerEvent
}

@HiltViewModel
class PartyLedgerViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val expensesRepository: ExpensesRepository,
        private val ledgerRepository: ExpensesLedgerRepository,
        private val session: ExpensesSession,
        private val googleAccountLinker: GoogleAccountLinker,
        private val attachmentResolver: AttachmentContentResolver,
        private val attachmentDeleter: AttachmentDeleter,
        private val syncScheduler: SyncScheduler,
        private val clock: Clock,
    ) : ViewModel() {
        val partyId: String = checkNotNull(savedStateHandle[ARG_PARTY_ID])

        /** Bumped after a party edit so the one-shot party lookup re-emits the fresh row. */
        private val partyRefresh = MutableStateFlow(0)

        private val _editPartyError = MutableStateFlow<EditPartyError?>(null)

        /** Validation error of the last edit-party save attempt; cleared on retry/dismiss. */
        val editPartyError: StateFlow<EditPartyError?> = _editPartyError.asStateFlow()

        private val _events = MutableSharedFlow<PartyLedgerEvent>(extraBufferCapacity = 1)
        val events: SharedFlow<PartyLedgerEvent> = _events.asSharedFlow()

        /** All permission gates + Google link state as one flow (keeps the state combine at 5 sources). */
        private val gates =
            combine(
                combine(
                    session.canEditEntries,
                    session.canCreateEntries,
                    session.canDeleteEntries,
                    session.canManageParties,
                    session.canDeleteParties,
                ) { canEdit, canCreate, canDeleteEntry, canManage, canDeleteParty ->
                    Gates(canEdit, canCreate, canDeleteEntry, canManage, canDeleteParty)
                },
                session.canViewAmounts,
                googleAccountLinker.linkState,
            ) { base, viewAmounts, link ->
                base.copy(viewAmounts = viewAmounts, googleUnlinked = link is GoogleLinkState.NotLinked)
            }

        val state: StateFlow<PartyLedgerState> =
            combine(
                partyRefresh.map { ledgerRepository.party(partyId) },
                expensesRepository.entriesForParty(partyId),
                ledgerRepository.attachmentsForParty(partyId),
                gates,
                session.businessName,
            ) { party, entries, attachments, gate, businessName ->
                val rows = RunningBalanceCalculator.withRunningBalance(entries)
                PartyLedgerState(
                    party = party,
                    rows = rows,
                    attachmentsByExpense = attachments.groupBy { it.attachment.expenseId },
                    netBalancePaise = rows.firstOrNull()?.balanceAfterPaise ?: 0,
                    canEditEntries = gate.editEntries,
                    canCreateEntries = gate.createEntries,
                    canDeleteEntries = gate.deleteEntries,
                    canEditParty = gate.manageParties,
                    canDeleteParty = gate.deleteParties,
                    canViewAmounts = gate.viewAmounts,
                    businessName = businessName,
                    googleUnlinked = gate.googleUnlinked,
                    loaded = true,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PartyLedgerState())

        /** The §3 gates the ledger screen renders from (+ Google link state, same combine). */
        private data class Gates(
            val editEntries: Boolean,
            val createEntries: Boolean,
            val deleteEntries: Boolean,
            val manageParties: Boolean,
            val deleteParties: Boolean,
            val viewAmounts: Boolean = true,
            val googleUnlinked: Boolean = false,
        )

        /** Tombstone delete (§4.2); the row disappears locally and the delete syncs as a tombstone. */
        fun deleteEntry(expenseId: String) {
            viewModelScope.launch {
                expensesRepository.deleteExpense(expenseId)
            }
        }

        // ---- View attachment (ADR-052) -------------------------------------------------

        /** Attachment id currently being resolved — drives the thumbnail's loading spinner. */
        private val _openingAttachmentId = MutableStateFlow<String?>(null)
        val openingAttachmentId: StateFlow<String?> = _openingAttachmentId.asStateFlow()

        /** Attachment waiting on a Google link; non-null shows the link-to-view dialog. */
        private val attachmentAwaitingLink = MutableStateFlow<AttachmentWithLocalState?>(null)
        val showAttachmentLinkPrompt: StateFlow<Boolean> =
            attachmentAwaitingLink
                .map { it != null }
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)

        /** The link-to-view dialog's Connect button is running the account-picker flow. */
        private val _attachmentLinking = MutableStateFlow(false)
        val attachmentLinking: StateFlow<Boolean> = _attachmentLinking.asStateFlow()

        /** Pending Google scope-consent sheet the screen must launch, then call [completeGoogleConsent]. */
        private val _consentIntent = MutableStateFlow<PendingIntent?>(null)
        val consentIntent: StateFlow<PendingIntent?> = _consentIntent.asStateFlow()

        /**
         * Opens a tapped attachment (ADR-052): local cache → immediately; else Drive
         * download (spinner on the thumbnail) → open; else the link dialog / the localized
         * "not on this device yet" / offline messages. One resolution at a time — a second
         * tap while a download runs is ignored rather than queued.
         */
        fun openAttachment(attachment: AttachmentWithLocalState) {
            if (_openingAttachmentId.value != null) return
            _openingAttachmentId.value = attachment.attachment.id
            viewModelScope.launch {
                when (val result = attachmentResolver.resolve(attachment)) {
                    is AttachmentOpenResult.Ready ->
                        _events.emit(PartyLedgerEvent.OpenAttachment(result.file, result.mimeType, attachment))
                    AttachmentOpenResult.NeedsGoogleLink -> attachmentAwaitingLink.value = attachment
                    AttachmentOpenResult.NotAvailable -> _events.emit(PartyLedgerEvent.AttachmentUnavailable)
                    AttachmentOpenResult.DownloadFailed -> _events.emit(PartyLedgerEvent.AttachmentDownloadFailed)
                }
                _openingAttachmentId.value = null
            }
        }

        /**
         * Confirmed viewer delete (ADR-053): tombstone + outbox via the repository, local
         * cache file removed, best-effort Drive `files.delete` — the [AttachmentDeleter]
         * owns the cascade. The ledger's Room flow drops the thumbnail reactively.
         */
        fun deleteAttachment(attachment: AttachmentWithLocalState) {
            viewModelScope.launch {
                attachmentDeleter.delete(attachment)
                _events.emit(PartyLedgerEvent.AttachmentDeleted)
            }
        }

        /** "Later" on the link-to-view dialog — nothing opens; the thumbnail stays tappable. */
        fun dismissAttachmentLinkPrompt() {
            if (_attachmentLinking.value) return
            attachmentAwaitingLink.value = null
        }

        /**
         * "Connect Google" on the link-to-view dialog: runs the Settings link flow in place
         * (§4.4 linker; same plumbing as the add-entry prompt). [activityContext] MUST be an
         * Activity context — Credential Manager shows UI. On success the tapped attachment
         * is re-resolved, which now downloads it from Drive.
         */
        fun linkGoogleToView(activityContext: Context) {
            if (_attachmentLinking.value) return
            _attachmentLinking.value = true
            viewModelScope.launch {
                googleAccountLinker
                    .link(activityContext)
                    .onSuccess { onLinkedForView() }
                    .onFailure(::handleLinkFailure)
            }
        }

        /** Completes the link after the scope-consent sheet returned [resultIntent]. */
        fun completeGoogleConsent(resultIntent: Intent?) {
            _consentIntent.value = null
            viewModelScope.launch {
                googleAccountLinker
                    .completeLink(resultIntent)
                    .onSuccess { onLinkedForView() }
                    .onFailure(::handleLinkFailure)
            }
        }

        private fun onLinkedForView() {
            // Freshly linked: nudge sync so THIS device's pending uploads move too, then
            // retry the tapped attachment — the resolver now takes the download path.
            syncScheduler.requestImmediateSync()
            val pending = attachmentAwaitingLink.value
            attachmentAwaitingLink.value = null
            _attachmentLinking.value = false
            pending?.let(::openAttachment)
        }

        private fun handleLinkFailure(error: Throwable) {
            when (error) {
                is GoogleLinkException.NeedsScopeConsent -> {
                    _consentIntent.value = error.pendingIntent
                    return // keep `linking`; the consent sheet continues the flow
                }
                is GoogleLinkException.Cancelled -> Unit // keep the dialog; Later still works
                else -> _events.tryEmit(PartyLedgerEvent.GoogleLinkFailed)
            }
            _attachmentLinking.value = false
        }

        /**
         * Edit-party save (ADR-028): name (trimmed, deduped against the business's other
         * live parties), optional phone and the business/personal flag — full parity with
         * the add-person form. Emits [PartyLedgerEvent.PartySaved] on success; sets
         * [editPartyError] and keeps the dialog open otherwise.
         */
        fun saveParty(
            name: String,
            phone: String,
            businessRelated: Boolean,
        ) {
            val party = state.value.party ?: return
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                _editPartyError.value = EditPartyError.EMPTY_NAME
                return
            }
            viewModelScope.launch {
                val normalized = FuzzyNameMatcher.normalize(trimmed)
                val duplicate =
                    expensesRepository
                        .partiesWithBalance(session.businessId())
                        .first()
                        .any { it.party.id != party.id && FuzzyNameMatcher.normalize(it.party.name) == normalized }
                if (duplicate) {
                    _editPartyError.value = EditPartyError.DUPLICATE_NAME
                    return@launch
                }
                _editPartyError.value = null
                val updated =
                    party.copy(
                        name = trimmed,
                        phone = phone.trim().ifEmpty { null },
                        businessRelated = businessRelated,
                        updatedAt = clock.instant(),
                    )
                if (updated != party) expensesRepository.saveParty(updated)
                partyRefresh.update { it + 1 }
                _events.emit(PartyLedgerEvent.PartySaved)
            }
        }

        /** Clears the edit validation error (dialog dismissed or the name was retyped). */
        fun clearEditPartyError() {
            _editPartyError.value = null
        }

        /**
         * Delete party (ADR-028): cascade-tombstones the party, its expenses and their
         * attachments (one outbox DELETE per row), removes the local cached attachment
         * files, then emits [PartyLedgerEvent.PartyDeleted] so the UI navigates back.
         */
        fun deleteParty() {
            val party = state.value.party ?: return
            viewModelScope.launch {
                val localCachePaths = ledgerRepository.deletePartyCascade(party.id)
                localCachePaths.forEach { path -> runCatching { File(path).delete() } }
                _events.emit(PartyLedgerEvent.PartyDeleted(party.name))
            }
        }
    }
