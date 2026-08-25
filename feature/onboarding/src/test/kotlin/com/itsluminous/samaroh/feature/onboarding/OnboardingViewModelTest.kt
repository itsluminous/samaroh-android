package com.itsluminous.samaroh.feature.onboarding

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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
import com.itsluminous.samaroh.core.data.repository.MemberRepository
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.BusinessMember
import com.itsluminous.samaroh.core.model.BusinessSettings
import com.itsluminous.samaroh.core.model.MemberStatus
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.feature.onboarding.logo.LogoProcessor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** §4.0 flow state machine: language → welcome → sign-in → fork → create/join → link Google → done. */
@RunWith(RobolectricTestRunner::class)
class OnboardingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now: Instant = Instant.parse("2026-08-25T09:00:00Z")
    private val session = Session(userId = "uid-1", email = "user@example.com")

    private val fakeAuth = FakeAuthRepository()
    private val fakeSessionHolder = FakeSessionHolder()
    private val fakeRefresher = FakeMembershipRefresher()
    private val fakeBusinessRepo = FakeBusinessRepository()
    private val fakeMemberRepo = FakeMemberRepository()
    private val fakeLocale = FakeLocaleApplier()

    private fun viewModel(
        supabaseConfigured: Boolean = true,
        googleConfigured: Boolean = true,
    ): OnboardingViewModel {
        val config =
            AuthConfig(
                supabaseUrl = if (supabaseConfigured) "https://example.supabase.co" else "",
                supabaseAnonKey = if (supabaseConfigured) "anon-key" else "",
                googleWebClientId = if (googleConfigured) "client-id" else "",
            )
        return OnboardingViewModel(
            authRepository = fakeAuth,
            sessionHolder = fakeSessionHolder,
            membershipRefresher = fakeRefresher,
            businessRepository = fakeBusinessRepo,
            memberRepository = fakeMemberRepo,
            localeApplier = fakeLocale,
            logoProcessor = LogoProcessor(ApplicationProvider.getApplicationContext()),
            googleIdTokenFetcher = GoogleIdTokenFetcher(config),
            authConfig = config,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
    }

    private fun invitedMember(businessId: String = "biz-9") =
        BusinessMember(
            id = "member-1",
            businessId = businessId,
            invitedEmail = session.email,
            userId = session.userId,
            displayName = "Ramu Kaka",
            isOwner = false,
            status = MemberStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )

    @Test
    fun `flow starts at the language step`() {
        assertThat(viewModel().uiState.value.step).isEqualTo(OnboardingStep.LANGUAGE)
    }

    @Test
    fun `selecting a language applies it and continue advances to welcome`() {
        val vm = viewModel()
        vm.selectLanguage("hi")

        assertThat(fakeLocale.applied).containsExactly("hi")
        assertThat(vm.uiState.value.selectedLanguage).isEqualTo("hi")
        assertThat(vm.uiState.value.step).isEqualTo(OnboardingStep.LANGUAGE)

        vm.continueFromLanguage()
        assertThat(vm.uiState.value.step).isEqualTo(OnboardingStep.WELCOME)
    }

    @Test
    fun `welcome carousel is skippable to sign-in`() {
        val vm = viewModel()
        vm.continueFromLanguage()
        vm.finishWelcome()
        assertThat(vm.uiState.value.step).isEqualTo(OnboardingStep.SIGN_IN)
    }

    @Test
    fun `successful email sign-in advances to fork with no invites`() =
        runTest {
            fakeSessionHolder.flow.value = session
            val vm = viewModel()
            vm.submitEmailAuth("user@example.com", "secret123")

            val state = vm.uiState.value
            assertThat(state.step).isEqualTo(OnboardingStep.FORK)
            assertThat(state.invites).isEmpty()
            assertThat(fakeAuth.signInCalls).containsExactly("user@example.com")
        }

    @Test
    fun `sign-up mode calls signUp and rejected sign-up surfaces the error`() =
        runTest {
            fakeAuth.nextResult = AuthResult.Failure(AuthFailureKind.REJECTED)
            val vm = viewModel()
            vm.setAuthMode(AuthFormMode.SIGN_UP)
            vm.submitEmailAuth("user@example.com", "pw")

            val state = vm.uiState.value
            assertThat(fakeAuth.signUpCalls).containsExactly("user@example.com")
            assertThat(state.step).isEqualTo(OnboardingStep.LANGUAGE)
            assertThat(state.authError).isEqualTo(AuthFailureKind.REJECTED)
            assertThat(state.isBusy).isFalse()
        }

    @Test
    fun `pending invite is auto-detected after sign-in`() =
        runTest {
            fakeSessionHolder.flow.value = session
            fakeBusinessRepo.saveBusiness(business("biz-9", "Sharma Palace", ownerUserId = "someone-else"))
            fakeRefresher.result = MembershipRefreshResult.Refreshed(listOf(invitedMember("biz-9")))
            val vm = viewModel()
            vm.submitEmailAuth("user@example.com", "secret123")

            val state = vm.uiState.value
            assertThat(state.step).isEqualTo(OnboardingStep.FORK)
            assertThat(state.invites).hasSize(1)
            assertThat(state.invites.first().businessId).isEqualTo("biz-9")
            assertThat(state.invites.first().businessName).isEqualTo("Sharma Palace")
        }

    @Test
    fun `own or revoked memberships are not invites`() =
        runTest {
            fakeSessionHolder.flow.value = session
            fakeRefresher.result =
                MembershipRefreshResult.Refreshed(
                    listOf(
                        invitedMember("biz-own").copy(id = "m-own", isOwner = true),
                        invitedMember("biz-revoked").copy(id = "m-rev", status = MemberStatus.REVOKED),
                        invitedMember("biz-other").copy(id = "m-other", invitedEmail = "other@example.com"),
                    ),
                )
            val vm = viewModel()
            vm.submitEmailAuth("user@example.com", "secret123")

            assertThat(vm.uiState.value.invites).isEmpty()
        }

    @Test
    fun `accepting an invite selects the business and moves to link-google`() =
        runTest {
            fakeSessionHolder.flow.value = session
            fakeRefresher.result = MembershipRefreshResult.Refreshed(listOf(invitedMember("biz-9")))
            val vm = viewModel()
            vm.submitEmailAuth("user@example.com", "secret123")
            vm.chooseJoin()
            assertThat(vm.uiState.value.step).isEqualTo(OnboardingStep.JOIN)

            vm.acceptInvite(
                vm.uiState.value.invites
                    .first(),
            )
            assertThat(vm.uiState.value.step).isEqualTo(OnboardingStep.LINK_GOOGLE)
            assertThat(vm.uiState.value.activeBusinessId).isEqualTo("biz-9")
        }

    @Test
    fun `create business validates required name and owner`() =
        runTest {
            fakeSessionHolder.flow.value = session
            val vm = viewModel()
            vm.chooseCreate()
            vm.submitCreateBusiness()

            val state = vm.uiState.value
            assertThat(state.nameMissing).isTrue()
            assertThat(state.ownerNameMissing).isTrue()
            assertThat(fakeBusinessRepo.saved).isEmpty()
        }

    @Test
    fun `create business saves business plus active owner member and advances`() =
        runTest {
            fakeSessionHolder.flow.value = session
            val vm = viewModel()
            vm.chooseCreate()
            vm.updateForm(CreateBusinessForm(name = "Sharma Palace", ownerName = "Sharma ji", address = "Patna"))
            vm.submitCreateBusiness()

            val state = vm.uiState.value
            assertThat(state.step).isEqualTo(OnboardingStep.LINK_GOOGLE)
            assertThat(state.activeBusinessId).isNotNull()

            val business = fakeBusinessRepo.saved.single()
            assertThat(business.name).isEqualTo("Sharma Palace")
            assertThat(business.ownerName).isEqualTo("Sharma ji")
            assertThat(business.ownerUserId).isEqualTo(session.userId)
            // Blank type falls back to the model's canonical default.
            assertThat(business.businessType).isEqualTo("Marriage Hall")

            val member = fakeMemberRepo.saved.single()
            assertThat(member.businessId).isEqualTo(business.id)
            assertThat(member.isOwner).isTrue()
            assertThat(member.status).isEqualTo(MemberStatus.ACTIVE)
            assertThat(member.invitedEmail).isEqualTo(session.email)
            assertThat(member.userId).isEqualTo(session.userId)
        }

    @Test
    fun `do it later finishes onboarding`() =
        runTest {
            fakeSessionHolder.flow.value = session
            val vm = viewModel()
            vm.chooseCreate()
            vm.updateForm(CreateBusinessForm(name = "Hall", ownerName = "Owner"))
            vm.submitCreateBusiness()

            vm.finishOnboarding()
            assertThat(vm.uiState.value.step).isEqualTo(OnboardingStep.DONE)
        }

    @Test
    fun `google button state degrades gracefully when client id is empty`() {
        val vm = viewModel(googleConfigured = false)
        assertThat(vm.uiState.value.googleSignInConfigured).isFalse()
        // The fetcher itself also refuses without touching Credential Manager.
        runTest {
            val fetcher = GoogleIdTokenFetcher(AuthConfig("url", "key", ""))
            assertThat(fetcher(ApplicationProvider.getApplicationContext()))
                .isEqualTo(GoogleSignInOutcome.NotConfigured)
        }
    }

    @Test
    fun `missing supabase config is exposed so the ui shows the localized state`() {
        val vm = viewModel(supabaseConfigured = false)
        assertThat(vm.uiState.value.supabaseConfigured).isFalse()
    }

    @Test
    fun `google id token outcome signs in and cancellation is a no-op`() =
        runTest {
            fakeSessionHolder.flow.value = session
            val vm = viewModel()
            vm.onGoogleSignInOutcome(GoogleSignInOutcome.Cancelled)
            assertThat(vm.uiState.value.step).isEqualTo(OnboardingStep.LANGUAGE)
            assertThat(vm.uiState.value.authError).isNull()

            vm.onGoogleSignInOutcome(GoogleSignInOutcome.IdToken("token-1"))
            assertThat(fakeAuth.googleTokens).containsExactly("token-1")
            assertThat(vm.uiState.value.step).isEqualTo(OnboardingStep.FORK)
        }

    @Test
    fun `back navigation walks the flow backwards`() {
        val vm = viewModel()
        vm.continueFromLanguage()
        vm.finishWelcome()
        assertThat(vm.goBack()).isTrue()
        assertThat(vm.uiState.value.step).isEqualTo(OnboardingStep.WELCOME)
        assertThat(vm.goBack()).isTrue()
        assertThat(vm.uiState.value.step).isEqualTo(OnboardingStep.LANGUAGE)
        assertThat(vm.goBack()).isFalse()
    }

    private fun business(
        id: String,
        name: String,
        ownerUserId: String,
    ) = Business(
        id = id,
        name = name,
        ownerName = "Owner",
        ownerUserId = ownerUserId,
        createdAt = now,
        updatedAt = now,
    )
}

// ---- fakes ----

private class FakeAuthRepository : AuthRepository {
    var nextResult: AuthResult = AuthResult.Success
    val signInCalls = mutableListOf<String>()
    val signUpCalls = mutableListOf<String>()
    val googleTokens = mutableListOf<String>()

    override val isConfigured: Boolean = true

    override suspend fun signInWithEmail(
        email: String,
        password: String,
    ): AuthResult {
        signInCalls += email
        return nextResult
    }

    override suspend fun signUpWithEmail(
        email: String,
        password: String,
    ): AuthResult {
        signUpCalls += email
        return nextResult
    }

    override suspend fun signInWithGoogleIdToken(idToken: String): AuthResult {
        googleTokens += idToken
        return nextResult
    }
}

private class FakeSessionHolder : SessionHolder {
    val flow = MutableStateFlow<Session?>(null)
    override val session: Flow<Session?> = flow

    override suspend fun signOut() {
        flow.value = null
    }
}

private class FakeMembershipRefresher : MembershipRefresher {
    var result: MembershipRefreshResult = MembershipRefreshResult.Refreshed(emptyList())

    override suspend fun refresh(): MembershipRefreshResult = result
}

private class FakeBusinessRepository : BusinessRepository {
    val saved = mutableListOf<Business>()

    override fun businesses(): Flow<List<Business>> = MutableStateFlow(saved.toList())

    override suspend fun business(id: String): Business? = saved.firstOrNull { it.id == id }

    override suspend fun saveBusiness(business: Business) {
        saved += business
    }

    override fun settings(businessId: String): Flow<BusinessSettings?> = MutableStateFlow(null)

    override suspend fun saveSettings(settings: BusinessSettings) = Unit
}

private class FakeMemberRepository : MemberRepository {
    val saved = mutableListOf<BusinessMember>()

    override fun membersForBusiness(businessId: String): Flow<List<BusinessMember>> =
        MutableStateFlow(saved.filter { it.businessId == businessId })

    override suspend fun memberForUser(
        businessId: String,
        userId: String,
    ): BusinessMember? = saved.firstOrNull { it.businessId == businessId && it.userId == userId }

    override suspend fun saveMember(member: BusinessMember) {
        saved += member
    }
}

private class FakeLocaleApplier : LocaleApplier {
    val applied = mutableListOf<String>()
    override val supportedLocales: List<String> = listOf("en", "hi")

    override fun apply(languageTag: String) {
        applied += languageTag
    }

    override fun current(): String? = applied.lastOrNull()
}
