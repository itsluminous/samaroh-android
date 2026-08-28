package com.itsluminous.samaroh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Member permissions — exact mirror of shared/permissions/permissions-schema.json.
 * Every action defaults to `false` when absent EXCEPT the per-module `view_amounts`
 * keys, which default to `true` (backward compat: pre-existing permission objects keep
 * showing amounts); owners bypass this object entirely. Backups are owner-only and
 * deliberately NOT representable here. FROZEN CONTRACT.
 */
@Serializable
data class MemberPermissions(
    val booking: BookingPermissions = BookingPermissions(),
    val expenses: ExpensesPermissions = ExpensesPermissions(),
    val inventory: InventoryPermissions = InventoryPermissions(),
    val reports: ReportsPermissions = ReportsPermissions(),
    val settings: SettingsPermissions = SettingsPermissions(),
) {
    companion object {
        /** Preset: every view permission, nothing else. */
        fun viewer(): MemberPermissions =
            MemberPermissions(
                booking = BookingPermissions(view = true),
                expenses = ExpensesPermissions(view = true),
                inventory = InventoryPermissions(view = true),
                reports = ReportsPermissions(view = true),
            )

        /** Preset: view + create. */
        fun staff(): MemberPermissions =
            MemberPermissions(
                booking = BookingPermissions(view = true, create = true, recordPayment = true),
                expenses = ExpensesPermissions(view = true, create = true),
                inventory = InventoryPermissions(view = true, create = true),
            )

        /** Preset: everything except settings/members. */
        fun manager(): MemberPermissions =
            MemberPermissions(
                booking =
                    BookingPermissions(
                        view = true,
                        create = true,
                        edit = true,
                        delete = true,
                        recordPayment = true,
                        generateInvoice = true,
                    ),
                expenses = ExpensesPermissions(view = true, create = true, edit = true, delete = true, manageParties = true),
                inventory = InventoryPermissions(view = true, create = true, edit = true, delete = true, manageMasterItems = true),
                reports = ReportsPermissions(view = true),
            )
    }
}

@Serializable
data class BookingPermissions(
    val view: Boolean = false,
    /** ABSENT = TRUE: masks package total, advance, balance due and payment history when false. */
    @SerialName("view_amounts") val viewAmounts: Boolean = true,
    val create: Boolean = false,
    val edit: Boolean = false,
    val delete: Boolean = false,
    @SerialName("record_payment") val recordPayment: Boolean = false,
    @SerialName("generate_invoice") val generateInvoice: Boolean = false,
)

@Serializable
data class ExpensesPermissions(
    val view: Boolean = false,
    /** ABSENT = TRUE: masks ledger entry amounts and party balances when false. */
    @SerialName("view_amounts") val viewAmounts: Boolean = true,
    val create: Boolean = false,
    val edit: Boolean = false,
    val delete: Boolean = false,
    @SerialName("manage_parties") val manageParties: Boolean = false,
)

@Serializable
data class InventoryPermissions(
    val view: Boolean = false,
    /** ABSENT = TRUE: masks item prices, transaction values and stock worth when false. */
    @SerialName("view_amounts") val viewAmounts: Boolean = true,
    val create: Boolean = false,
    val edit: Boolean = false,
    val delete: Boolean = false,
    @SerialName("manage_master_items") val manageMasterItems: Boolean = false,
)

@Serializable
data class ReportsPermissions(
    val view: Boolean = false,
    /** ABSENT = TRUE: hides money reports entirely from the reports home when false. */
    @SerialName("view_amounts") val viewAmounts: Boolean = true,
)

@Serializable
data class SettingsPermissions(
    @SerialName("manage_business") val manageBusiness: Boolean = false,
    @SerialName("manage_members") val manageMembers: Boolean = false,
    @SerialName("gcal_sync") val gcalSync: Boolean = false,
)
