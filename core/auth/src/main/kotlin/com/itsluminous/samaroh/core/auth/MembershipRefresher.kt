package com.itsluminous.samaroh.core.auth

import com.itsluminous.samaroh.core.database.dao.BusinessDao
import com.itsluminous.samaroh.core.database.dao.BusinessMemberDao
import com.itsluminous.samaroh.core.database.entity.BusinessEntity
import com.itsluminous.samaroh.core.database.entity.BusinessMemberEntity
import com.itsluminous.samaroh.core.model.BusinessMember
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.MemberStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

sealed interface MembershipRefreshResult {
    /** Memberships (and their businesses) were pulled and applied to Room. */
    data class Refreshed(
        val memberships: List<BusinessMember>,
    ) : MembershipRefreshResult

    data object NotConfigured : MembershipRefreshResult

    data object SignedOut : MembershipRefreshResult

    data class Failed(
        val cause: Throwable,
    ) : MembershipRefreshResult
}

/**
 * Client half of the invite-acceptance flow (§3): the server trigger auto-activates a
 * membership when a user signs UP with the invited email; for auth accounts that
 * already existed the invite stays `invited` and the user accepts it explicitly on the
 * join screen via [activateInvite] (ADR-037). [refresh] re-pulls membership rows (plus
 * their businesses) so the onboarding fork and [PermissionGuard] see them immediately —
 * without waiting for the periodic sync engine.
 */
interface MembershipRefresher {
    suspend fun refresh(): MembershipRefreshResult

    /**
     * Accepts a pending invitation: flips the caller's own `business_members` row to
     * `active` server-side (allowed by the self-activation RLS policy, migration 004)
     * and applies the result to Room. Idempotent: a row that is ALREADY active for this
     * user (auto-activated by a server trigger, or a concurrent accept) counts as
     * success. Returns false offline, signed out, or when the server refuses.
     */
    suspend fun activateInvite(memberId: String): Boolean
}

/**
 * Postgrest-backed [MembershipRefresher]. Applies pulled rows straight to the DAOs
 * (pull application, NOT through repositories — repository writes would enqueue outbox
 * pushes and echo server rows back to the server).
 */
@Singleton
class SupabaseMembershipRefresher
    @Inject
    constructor(
        private val client: SupabaseClient?,
        private val sessionHolder: SessionHolder,
        private val businessDao: BusinessDao,
        private val memberDao: BusinessMemberDao,
    ) : MembershipRefresher {
        override suspend fun refresh(): MembershipRefreshResult {
            val supabase = client ?: return MembershipRefreshResult.NotConfigured
            sessionHolder.session.first() ?: return MembershipRefreshResult.SignedOut
            return try {
                // RLS scopes both selects to rows this user may see (own memberships +
                // businesses they own or are an active member of).
                val members = supabase.from("business_members").select().decodeList<BusinessMemberWireRow>()
                val businesses = supabase.from("businesses").select().decodeList<BusinessWireRow>()
                businesses.forEach { businessDao.upsert(it.toEntity()) }
                members.forEach { memberDao.upsert(it.toEntity()) }
                MembershipRefreshResult.Refreshed(members.map { it.toModel() })
            } catch (e: Exception) {
                MembershipRefreshResult.Failed(e)
            }
        }

        override suspend fun activateInvite(memberId: String): Boolean {
            val supabase = client ?: return false
            val session = sessionHolder.session.first() ?: return false
            return try {
                val activated =
                    supabase
                        .from("business_members")
                        .update(
                            {
                                set("user_id", session.userId)
                                set("status", "active")
                            },
                        ) {
                            select()
                            filter {
                                eq("id", memberId)
                                eq("status", "invited")
                            }
                        }.decodeList<BusinessMemberWireRow>()
                val row =
                    activated.firstOrNull()
                        // 0 rows updated: either a server trigger already activated it (a
                        // race with signup auto-activation) or RLS refused. Re-read and
                        // accept the already-active-for-me case as success.
                        ?: supabase
                            .from("business_members")
                            .select { filter { eq("id", memberId) } }
                            .decodeList<BusinessMemberWireRow>()
                            .firstOrNull { it.status == "active" && it.userId == session.userId }
                        ?: return false
                memberDao.upsert(row.toEntity())
                true
            } catch (e: Exception) {
                false
            }
        }
    }

/** Postgres `timestamptz` wire format (e.g. `2026-08-25T11:44:00.123456+00:00`). */
private fun pgInstant(value: String): Instant = OffsetDateTime.parse(value).toInstant()

/** `business_members` as PostgREST returns it — timestamps as offset strings, status as wire text. */
@Serializable
internal data class BusinessMemberWireRow(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("invited_email") val invitedEmail: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("display_name") val displayName: String,
    @SerialName("is_owner") val isOwner: Boolean = false,
    val status: String,
    val permissions: MemberPermissions = MemberPermissions(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toEntity(): BusinessMemberEntity =
        BusinessMemberEntity(
            id = id,
            businessId = businessId,
            invitedEmail = invitedEmail,
            userId = userId,
            displayName = displayName,
            isOwner = isOwner,
            status = MemberStatus.fromWire(status),
            permissions = permissions,
            createdAt = pgInstant(createdAt),
            updatedAt = pgInstant(updatedAt),
            deletedAt = deletedAt?.let(::pgInstant),
        )

    fun toModel(): BusinessMember =
        BusinessMember(
            id = id,
            businessId = businessId,
            invitedEmail = invitedEmail,
            userId = userId,
            displayName = displayName,
            isOwner = isOwner,
            status = MemberStatus.fromWire(status),
            permissions = permissions,
            createdAt = pgInstant(createdAt),
            updatedAt = pgInstant(updatedAt),
            deletedAt = deletedAt?.let(::pgInstant),
        )
}

/** `businesses` as PostgREST returns it (no money columns; counters are ints). */
@Serializable
internal data class BusinessWireRow(
    val id: String,
    val name: String,
    @SerialName("business_type") val businessType: String = "Marriage Hall",
    val address: String? = null,
    @SerialName("owner_name") val ownerName: String,
    @SerialName("logo_path") val logoPath: String? = null,
    val currency: String = "INR",
    @SerialName("invoice_prefix") val invoicePrefix: String = "INV",
    @SerialName("invoice_counter") val invoiceCounter: Int = 0,
    @SerialName("owner_user_id") val ownerUserId: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toEntity(): BusinessEntity =
        BusinessEntity(
            id = id,
            name = name,
            businessType = businessType,
            address = address,
            ownerName = ownerName,
            logoPath = logoPath,
            currency = currency,
            invoicePrefix = invoicePrefix,
            invoiceCounter = invoiceCounter,
            ownerUserId = ownerUserId,
            createdAt = pgInstant(createdAt),
            updatedAt = pgInstant(updatedAt),
            deletedAt = deletedAt?.let(::pgInstant),
        )
}
