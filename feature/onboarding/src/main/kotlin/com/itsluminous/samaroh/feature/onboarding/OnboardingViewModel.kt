package com.itsluminous.samaroh.feature.onboarding

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.auth.AuthConfig
import com.itsluminous.samaroh.core.auth.AuthFailureKind
import com.itsluminous.samaroh.core.auth.AuthRepository
import com.itsluminous.samaroh.core.auth.AuthResult
import com.itsluminous.samaroh.core.auth.GoogleIdTokenFetcher
import com.itsluminous.samaroh.core.auth.GoogleSignInOutcome
import com.itsluminous.samaroh.core.auth.MembershipRefreshResult
import com.itsluminous.samaroh.core.auth.MembershipRefresher
import com.itsluminous.samaroh.core.auth.Session
import com.itsluminous.samaroh.core.auth.SessionHolder
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.repository.EventTypeRepository
import com.itsluminous.samaroh.core.data.repository.MemberRepository
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.BusinessMember
import com.itsluminous.samaroh.core.model.MemberStatus
import com.itsluminous.samaroh.feature.onboarding.logo.LogoProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.UUID
import javax.inject.Inject

/** The onboarding flow's step machine (§4.0, in order). */
enum class OnboardingStep {
    LANGUAGE,
    WELCOME,
    SIGN_IN,
    FORK,
    JOIN,
    CREATE_BUSINESS,
    LINK_GOOGLE,
    DONE,
}

enum class AuthFormMode { SIGN_IN, SIGN_UP }

/** A pending/accepted invitation auto-detected after sign-in (§4.0 step 4). */
data class InviteSummary(
    val memberId: String,
    val businessId: String,
    val businessName: String?,
    val displayName: String,
)

/** Create-business form fields (§4.0 step 5). `name` and `ownerName` are required. */
data class CreateBusinessForm(
    val name: String = "",
    val businessType: String = "",
    val address: String = "",
    val ownerName: String = "",
    val logoPath: String? = null,
)

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.LANGUAGE,
    val selectedLanguage: String? = null,
    val supportedLocales: List<String> = emptyList(),
    val authMode: AuthFormMode = AuthFormMode.SIGN_IN,
    val isBusy: Boolean = false,
    /** False when this build carries no Supabase URL/key — auth is unavailable. */
    val supabaseConfigured: Boolean = true,
    /** False when `GOOGLE_WEB_CLIENT_ID` is empty — the Google button shows the localized "not configured" state. */
    val googleSignInConfigured: Boolean = true,
    val authError: AuthFailureKind? = null,
    val invites: List<InviteSummary> = emptyList(),
    /** True when the last invite-accept attempt failed (offline or server refused). */
    val acceptFailed: Boolean = false,
    val form: CreateBusinessForm = CreateBusinessForm(),
    val nameMissing: Boolean = false,
    val ownerNameMissing: Boolean = false,
    val createFailed: Boolean = false,
    /** The business the user created or joined; set before LINK_GOOGLE. */
    val activeBusinessId: String? = null,
)

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
        private val sessionHolder: SessionHolder,
        private val membershipRefresher: MembershipRefresher,
        private val businessRepository: BusinessRepository,
        private val memberRepository: MemberRepository,
        private val eventTypeRepository: EventTypeRepository,
        private val syncScheduler: SyncScheduler,
        private val localeApplier: LocaleApplier,
        private val logoProcessor: LogoProcessor,
        private val googleIdTokenFetcher: GoogleIdTokenFetcher,
        authConfig: AuthConfig,
        private val clock: Clock,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                OnboardingUiState(
                    // Post-sign-out re-entry (ADR-040): the device already has a chosen
                    // language, so the flow starts directly at the sign-in step.
                    step =
                        if (savedStateHandle.get<Boolean>(ONBOARDING_ARG_START_AT_SIGN_IN) == true) {
                            OnboardingStep.SIGN_IN
                        } else {
                            OnboardingStep.LANGUAGE
                        },
                    supportedLocales = localeApplier.supportedLocales,
                    selectedLanguage = localeApplier.current(),
                    supabaseConfigured = authConfig.isSupabaseConfigured,
                    googleSignInConfigured = authConfig.isGoogleSignInConfigured,
                ),
            )
        val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

        // ---- Language (step 1: FIRST screen, before anything else) ----

        fun selectLanguage(languageTag: String) {
            localeApplier.apply(languageTag)
            _uiState.value = _uiState.value.copy(selectedLanguage = languageTag)
        }

        fun continueFromLanguage() {
            _uiState.value = _uiState.value.copy(step = OnboardingStep.WELCOME)
        }

        // ---- Welcome carousel (step 2: 3 slides, skippable) ----

        fun finishWelcome() {
            _uiState.value = _uiState.value.copy(step = OnboardingStep.SIGN_IN)
        }

        // ---- Sign in (step 3: Google primary, email+password) ----

        fun setAuthMode(mode: AuthFormMode) {
            _uiState.value = _uiState.value.copy(authMode = mode, authError = null)
        }

        fun submitEmailAuth(
            email: String,
            password: String,
        ) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isBusy = true, authError = null)
                val result =
                    when (_uiState.value.authMode) {
                        AuthFormMode.SIGN_IN -> authRepository.signInWithEmail(email.trim(), password)
                        AuthFormMode.SIGN_UP -> authRepository.signUpWithEmail(email.trim(), password)
                    }
                when (result) {
                    is AuthResult.Success -> onSignedIn()
                    is AuthResult.Failure ->
                        _uiState.value = _uiState.value.copy(isBusy = false, authError = result.kind)
                }
            }
        }

        /** Starts Credential Manager Google sign-in. [activityContext] must be an Activity context. */
        fun signInWithGoogle(activityContext: android.content.Context) {
            viewModelScope.launch {
                onGoogleSignInOutcome(googleIdTokenFetcher(activityContext))
            }
        }

        fun onGoogleSignInOutcome(outcome: GoogleSignInOutcome) {
            when (outcome) {
                is GoogleSignInOutcome.IdToken ->
                    viewModelScope.launch {
                        _uiState.value = _uiState.value.copy(isBusy = true, authError = null)
                        when (val result = authRepository.signInWithGoogleIdToken(outcome.token)) {
                            is AuthResult.Success -> onSignedIn()
                            is AuthResult.Failure ->
                                _uiState.value = _uiState.value.copy(isBusy = false, authError = result.kind)
                        }
                    }
                is GoogleSignInOutcome.Cancelled -> Unit
                is GoogleSignInOutcome.NotConfigured -> Unit // Button already shows the localized disabled state.
                is GoogleSignInOutcome.Failed ->
                    _uiState.value = _uiState.value.copy(authError = AuthFailureKind.NETWORK)
            }
        }

        /**
         * Offline-first escape hatch (§5: the app never blocks on network): proceeds to
         * the create/join fork without a session — device-local owner mode (ADR-017
         * signed-out default). Data syncs after a later sign-in.
         */
        fun continueWithoutAccount() {
            _uiState.value = _uiState.value.copy(isBusy = false, authError = null, step = OnboardingStep.FORK)
        }

        // ---- Fork (step 4: create vs join, pending-invite auto-detect) ----

        private suspend fun onSignedIn() {
            val session = sessionHolder.session.first()
            val memberships = refreshMemberships()
            // The session just became active: request an expedited engine run NOW so this
            // account's bookings/payments/etc. land before the user reaches the calendar
            // (§8 — sync on sign-in; the ON_START trigger fired pre-auth and pulled
            // nothing). The refresher above already wrote the businesses into Room, and
            // the engine re-enumerates mid-run arrivals, so one pass fetches everything.
            syncScheduler.ensurePeriodicSync()
            syncScheduler.requestImmediateSync()
            // Returning-user fast path: the account already belongs to a business — an
            // ACTIVE membership, or a business it owns that the refresh just pulled into
            // Room. Showing create/join again would fork the user's data into a second
            // business, so skip straight to done (the shell lands on the calendar and the
            // sync engine backfills the rest).
            val existingBusinessId = session?.let { existingBusinessId(it, memberships) }
            if (existingBusinessId != null) {
                _uiState.value =
                    _uiState.value.copy(isBusy = false, activeBusinessId = existingBusinessId, step = OnboardingStep.DONE)
                return
            }
            val invites = invitesFrom(session, memberships)
            _uiState.value = _uiState.value.copy(isBusy = false, step = OnboardingStep.FORK, invites = invites)
        }

        /** Pulls memberships + their businesses from the server into Room (no-op offline). */
        private suspend fun refreshMemberships(): List<BusinessMember> =
            when (val result = membershipRefresher.refresh()) {
                is MembershipRefreshResult.Refreshed -> result.memberships
                else -> emptyList()
            }

        /**
         * The business this account is already associated with, or null for a genuinely
         * new user: an ACTIVE, live membership (owner rows included — matched by user id
         * or invited email), else a live business owned by this user that the refresh
         * upserted into Room.
         */
        private suspend fun existingBusinessId(
            session: Session,
            memberships: List<BusinessMember>,
        ): String? {
            val activeMembership =
                memberships.firstOrNull { member ->
                    member.deletedAt == null &&
                        member.status == MemberStatus.ACTIVE &&
                        (
                            member.userId == session.userId ||
                                member.invitedEmail.equals(session.email, ignoreCase = true)
                        )
                }
            if (activeMembership != null) return activeMembership.businessId
            return businessRepository
                .businesses()
                .first()
                .firstOrNull { it.deletedAt == null && it.ownerUserId == session.userId }
                ?.id
        }

        /**
         * Invite acceptance is server-side (§3: a trigger auto-activates the membership on
         * sign-in); this is the client refresh path making the result visible immediately.
         */
        private suspend fun detectInvites(): List<InviteSummary> {
            val session = sessionHolder.session.first() ?: return emptyList()
            return invitesFrom(session, refreshMemberships())
        }

        private suspend fun invitesFrom(
            session: Session?,
            memberships: List<BusinessMember>,
        ): List<InviteSummary> {
            session ?: return emptyList()
            return memberships
                .filter { member ->
                    !member.isOwner &&
                        member.deletedAt == null &&
                        member.status != MemberStatus.REVOKED &&
                        member.invitedEmail.equals(session.email, ignoreCase = true)
                }.map { member ->
                    InviteSummary(
                        memberId = member.id,
                        businessId = member.businessId,
                        businessName = businessRepository.business(member.businessId)?.name,
                        displayName = member.displayName,
                    )
                }
        }

        fun chooseCreate() {
            _uiState.value = _uiState.value.copy(step = OnboardingStep.CREATE_BUSINESS)
        }

        fun chooseJoin() {
            _uiState.value = _uiState.value.copy(step = OnboardingStep.JOIN)
        }

        /** "Check again" on the join screen — re-pulls memberships from the server. */
        fun refreshInvites() {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isBusy = true, acceptFailed = false)
                val invites = detectInvites()
                _uiState.value = _uiState.value.copy(isBusy = false, invites = invites)
            }
        }

        /**
         * Accepts an invitation: activates the membership SERVER-side first (the
         * self-activation policy scopes it to the caller's own pending row — ADR-037);
         * only a confirmed activation enters the business, because RLS would otherwise
         * keep every business table invisible and the app would look empty.
         */
        fun acceptInvite(invite: InviteSummary) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isBusy = true, acceptFailed = false)
                val activated = membershipRefresher.activateInvite(invite.memberId)
                if (!activated) {
                    _uiState.value = _uiState.value.copy(isBusy = false, acceptFailed = true)
                    return@launch
                }
                // Membership is active: the business row (and its data) just became
                // visible under RLS — pull it now and kick the engine so the calendar
                // is populated when the user lands on it.
                refreshMemberships()
                syncScheduler.requestImmediateSync()
                _uiState.value =
                    _uiState.value.copy(
                        isBusy = false,
                        activeBusinessId = invite.businessId,
                        step = OnboardingStep.LINK_GOOGLE,
                    )
            }
        }

        // ---- Create business (step 5) ----

        fun updateForm(form: CreateBusinessForm) {
            _uiState.value = _uiState.value.copy(form = form, nameMissing = false, ownerNameMissing = false, createFailed = false)
        }

        /** Stores the square bitmap confirmed in the interactive cropper (WebP ≤320px). */
        fun onLogoCropped(bitmap: Bitmap) {
            viewModelScope.launch {
                val path = logoProcessor.process(bitmap)
                _uiState.value = _uiState.value.copy(form = _uiState.value.form.copy(logoPath = path))
            }
        }

        fun submitCreateBusiness() {
            val form = _uiState.value.form
            val nameMissing = form.name.isBlank()
            val ownerNameMissing = form.ownerName.isBlank()
            if (nameMissing || ownerNameMissing) {
                _uiState.value = _uiState.value.copy(nameMissing = nameMissing, ownerNameMissing = ownerNameMissing)
                return
            }
            viewModelScope.launch {
                // Offline-first (§5): a missing session (signed out, or email-confirmation
                // still pending after sign-up) must NOT block business creation — fall back
                // to a device-local owner identity; the app runs in owner-mode on the first
                // local business until a real session lands (ADR-017 default).
                val session = sessionHolder.session.first()
                _uiState.value = _uiState.value.copy(isBusy = true, createFailed = false)
                try {
                    val now = clock.instant()
                    val businessId = UUID.randomUUID().toString()
                    val ownerUserId = session?.userId ?: "local-${UUID.randomUUID()}"
                    val trimmedType = form.businessType.trim()
                    // A blank type keeps the model's canonical default (mirrors the Postgres column default).
                    val business =
                        Business(
                            id = businessId,
                            name = form.name.trim(),
                            address = form.address.trim().ifBlank { null },
                            ownerName = form.ownerName.trim(),
                            logoPath = form.logoPath,
                            ownerUserId = ownerUserId,
                            createdAt = now,
                            updatedAt = now,
                        ).let { if (trimmedType.isBlank()) it else it.copy(businessType = trimmedType) }
                    val ownerMember =
                        BusinessMember(
                            id = UUID.randomUUID().toString(),
                            businessId = businessId,
                            invitedEmail = session?.email.orEmpty(),
                            userId = ownerUserId,
                            displayName = form.ownerName.trim(),
                            isOwner = true,
                            status = MemberStatus.ACTIVE,
                            createdAt = now,
                            updatedAt = now,
                        )
                    businessRepository.saveBusiness(business)
                    memberRepository.saveMember(ownerMember)
                    // Client-side preset seeding for NEW businesses (ADR-032): server
                    // migration 006 only seeded businesses that existed when it ran.
                    eventTypeRepository.seedDefaults(businessId)
                    _uiState.value =
                        _uiState.value.copy(isBusy = false, activeBusinessId = businessId, step = OnboardingStep.LINK_GOOGLE)
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(isBusy = false, createFailed = true)
                }
            }
        }

        // ---- Link Google (step 6: prominent "Do it later") + finish (step 7) ----

        /** Both "Connect" (after the W1-F link flow) and "Do it later" end here — land on Booking. */
        fun finishOnboarding() {
            _uiState.value = _uiState.value.copy(step = OnboardingStep.DONE)
        }

        // ---- Back navigation within the flow ----

        fun goBack(): Boolean {
            val previous =
                when (_uiState.value.step) {
                    OnboardingStep.WELCOME -> OnboardingStep.LANGUAGE
                    OnboardingStep.SIGN_IN -> OnboardingStep.WELCOME
                    OnboardingStep.JOIN, OnboardingStep.CREATE_BUSINESS -> OnboardingStep.FORK
                    else -> return false
                }
            _uiState.value = _uiState.value.copy(step = previous)
            return true
        }
    }
