package com.itsluminous.samaroh.feature.expenses

/**
 * Business/user context for the Expenses tab until the Wave-1 session and business
 * selection land (`core:auth` SessionHolder impl is the W1-D deliverable; wiring happens
 * at integration). Single replacement point: swap these for the active session's ids.
 *
 * The edit/delete permission default is true (owner semantics); the `PermissionGate`
 * plumbing is already in place, so connecting `PermissionGuard.permissions()` at
 * integration only changes the source of [DEFAULT_CAN_EDIT_ENTRIES].
 */
internal object ExpensesSessionDefaults {
    /** Matches the Wave-0 fixture/demo business until business selection exists. */
    const val BUSINESS_ID = "00000000-0000-0000-0000-00000000b1a5"
    const val USER_ID = "00000000-0000-0000-0000-0000000000fe"
    const val DEFAULT_CAN_EDIT_ENTRIES = true
}
