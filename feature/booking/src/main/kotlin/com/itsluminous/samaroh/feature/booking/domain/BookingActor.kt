package com.itsluminous.samaroh.feature.booking.domain

import com.itsluminous.samaroh.core.data.repository.MemberRepository
import com.itsluminous.samaroh.core.model.BookingPermissions
import com.itsluminous.samaroh.core.model.Business
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who is acting on bookings and what they may do. `SessionHolder`/`PermissionGuard`
 * implementations are the W1-D deliverable, so this module keeps its own narrow seam:
 * the default provider treats the device user as the business owner (full access) —
 * exactly the pre-auth, offline-first behavior. Swapping in a session-aware provider is
 * a single Hilt binding change at integration.
 */
data class BookingActor(
    val userId: String,
    val displayName: String,
    val isOwner: Boolean,
    val permissions: BookingPermissions,
)

interface BookingActorProvider {
    suspend fun actorFor(business: Business): BookingActor
}

/** Owner-until-auth-lands default (§3: owners bypass the permission object). */
@Singleton
class OwnerBookingActorProvider
    @Inject
    constructor(
        private val memberRepository: MemberRepository,
    ) : BookingActorProvider {
        override suspend fun actorFor(business: Business): BookingActor {
            val member = memberRepository.memberForUser(business.id, business.ownerUserId)
            return BookingActor(
                userId = business.ownerUserId,
                displayName = member?.displayName ?: business.ownerName,
                isOwner = true,
                permissions =
                    BookingPermissions(
                        view = true,
                        create = true,
                        edit = true,
                        delete = true,
                        recordPayment = true,
                        generateInvoice = true,
                    ),
            )
        }
    }
