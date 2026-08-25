package com.itsluminous.samaroh.core.auth

import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.data.session.CurrentUserProvider
import com.itsluminous.samaroh.core.model.Business
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ActiveBusinessProvider]/[CurrentUserProvider] built on [SessionHolder]
 * (docs/decisions.md ADR-017). v1 is single-business (§4.0): the active business is the
 * first live local business regardless of session; the current user is the Supabase auth
 * user, or null while signed out — consumers then default to owner-mode.
 */
@Singleton
class SessionActiveBusinessProvider
    @Inject
    constructor(
        businessRepository: BusinessRepository,
    ) : ActiveBusinessProvider {
        override val activeBusiness: Flow<Business?> =
            businessRepository.businesses().map { businesses ->
                businesses.firstOrNull { it.deletedAt == null }
            }
    }

@Singleton
class SessionCurrentUserProvider
    @Inject
    constructor(
        sessionHolder: SessionHolder,
    ) : CurrentUserProvider {
        override val currentUserId: Flow<String?> = sessionHolder.session.map { it?.userId }
    }
