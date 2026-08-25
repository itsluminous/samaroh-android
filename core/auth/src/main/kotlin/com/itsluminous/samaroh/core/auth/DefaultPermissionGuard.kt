package com.itsluminous.samaroh.core.auth

import com.itsluminous.samaroh.core.auth.permissions.PermissionMatrix
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.repository.MemberRepository
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.BusinessMember
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.MemberStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-layer permission checks (§3 layer 2) reading `business_members.permissions` from
 * Room. Owners pass every check implicitly; revoked/invited members get nothing.
 * NEVER security by itself — Postgres RLS stays the authoritative layer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultPermissionGuard
    @Inject
    constructor(
        private val sessionHolder: SessionHolder,
        private val memberRepository: MemberRepository,
        private val businessRepository: BusinessRepository,
    ) : PermissionGuard {
        override fun permissions(businessId: String): Flow<MemberPermissions> =
            sessionHolder.session
                .flatMapLatest { session ->
                    if (session == null) {
                        flowOf(MemberPermissions())
                    } else {
                        combine(
                            businessRepository.businesses(),
                            memberRepository.membersForBusiness(businessId),
                        ) { businesses, members ->
                            effectivePermissions(
                                session = session,
                                business = businesses.find { it.id == businessId },
                                member = members.findFor(session),
                            )
                        }
                    }
                }.distinctUntilChanged()

        override fun isOwner(businessId: String): Flow<Boolean> =
            sessionHolder.session
                .flatMapLatest { session ->
                    if (session == null) {
                        flowOf(false)
                    } else {
                        combine(
                            businessRepository.businesses(),
                            memberRepository.membersForBusiness(businessId),
                        ) { businesses, members ->
                            isOwner(session, businesses.find { it.id == businessId }, members.findFor(session))
                        }
                    }
                }.distinctUntilChanged()

        private fun List<BusinessMember>.findFor(session: Session): BusinessMember? =
            firstOrNull { it.userId == session.userId }
                ?: firstOrNull { it.invitedEmail.equals(session.email, ignoreCase = true) }

        private fun isOwner(
            session: Session,
            business: Business?,
            member: BusinessMember?,
        ): Boolean = business?.ownerUserId == session.userId || (member?.isOwner == true && member.status == MemberStatus.ACTIVE)

        private fun effectivePermissions(
            session: Session,
            business: Business?,
            member: BusinessMember?,
        ): MemberPermissions =
            when {
                // Owner: implicit full access, cannot be revoked (§3).
                isOwner(session, business, member) -> PermissionMatrix.fullAccess()
                // Active employee: exactly what the owner granted; absent actions stay false.
                member != null && member.status == MemberStatus.ACTIVE -> member.permissions
                // Invited (not yet activated), revoked, or not a member at all: nothing.
                else -> MemberPermissions()
            }
    }
