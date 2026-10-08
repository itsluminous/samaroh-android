package com.itsluminous.samaroh.feature.booking.domain

import com.itsluminous.samaroh.core.data.repository.MemberRepository
import com.itsluminous.samaroh.core.data.session.CurrentUserProvider
import com.itsluminous.samaroh.core.model.BookingPermissions
import com.itsluminous.samaroh.core.model.Business
import kotlinx.coroutines.flow.first
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

/*
 * Reminder delivery gates (ADR-095, payment rule tightened by ADR-097) — ONE definition
 * shared by the engine (planning + snooze re-fire), the calendar ViewModel (card rows)
 * and the calendar screen (card visibility), so a notification can never be posted for
 * a member whose card would be empty, or vice versa. Owners bypass the permission
 * object (§3); signed-out/offline users are owner-mode.
 */

/**
 * "Did X pay?" payment reminders (ADR-097): a reminder is a nudge to ACT on a due, so it
 * goes only to people who can both SEE the due (`booking.view_amounts`) and RECORD the
 * payment (`booking.record_payment`). Lacking either → no notification, no popup, no
 * snooze re-fire, no card rows. Recording a payment from the booking card itself stays
 * governed by `record_payment` alone.
 */
val BookingActor.canReceivePaymentReminders: Boolean
    get() = isOwner || (permissions.recordPayment && permissions.viewAmounts)

/** Tentative follow-ups — confirming the booking needs `booking.edit`. */
val BookingActor.canReceiveFollowUps: Boolean
    get() = isOwner || permissions.edit

/** "N days before" event reminders — anyone who can see the calendar. */
val BookingActor.canReceiveUpcomingReminders: Boolean
    get() = isOwner || permissions.view

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

/**
 * Session-aware provider (Wave-1 integration, docs/decisions.md ADR-017): resolves the
 * actor from the app-wide current-user source. Signed-out/offline keeps the historical
 * owner-mode default; a signed-in non-owner acts with their granted §3 booking
 * permissions (unknown users get view-only).
 */
@Singleton
class SessionBookingActorProvider
    @Inject
    constructor(
        private val memberRepository: MemberRepository,
        private val currentUserProvider: CurrentUserProvider,
        private val ownerProvider: OwnerBookingActorProvider,
    ) : BookingActorProvider {
        override suspend fun actorFor(business: Business): BookingActor {
            val userId = currentUserProvider.currentUserId.first()
            if (userId == null || userId == business.ownerUserId) return ownerProvider.actorFor(business)
            val member = memberRepository.memberForUser(business.id, userId)
            return BookingActor(
                userId = userId,
                displayName = member?.displayName ?: business.ownerName,
                isOwner = false,
                permissions = member?.permissions?.booking ?: BookingPermissions(view = true),
            )
        }
    }
