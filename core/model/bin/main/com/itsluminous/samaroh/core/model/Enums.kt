package com.itsluminous.samaroh.core.model

/**
 * Domain enums — exact mirrors of the Postgres enums in the canonical schema
 * (shared/supabase/migrations/001_schema.sql). [wire] is the value stored in the
 * database (both Room and Postgres) and used in sync payloads. FROZEN CONTRACT:
 * changes require a docs/decisions.md entry.
 */
enum class MemberStatus(
    val wire: String,
) {
    INVITED("invited"),
    ACTIVE("active"),
    REVOKED("revoked"),
    ;

    companion object {
        fun fromWire(value: String): MemberStatus = entries.first { it.wire == value }
    }
}

enum class BookingStatus(
    val wire: String,
) {
    TENTATIVE("tentative"),
    CONFIRMED("confirmed"),
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    ;

    companion object {
        fun fromWire(value: String): BookingStatus = entries.first { it.wire == value }
    }
}

enum class PaymentMethod(
    val wire: String,
) {
    CASH("cash"),
    UPI("upi"),
    BANK_TRANSFER("bank_transfer"),
    CHEQUE("cheque"),
    OTHER("other"),
    ;

    companion object {
        fun fromWire(value: String): PaymentMethod = entries.first { it.wire == value }
    }
}

enum class ReminderStatus(
    val wire: String,
) {
    PENDING("pending"),
    CONFIRMED("confirmed"),
    SNOOZED("snoozed"),
    DISMISSED("dismissed"),
    ;

    companion object {
        fun fromWire(value: String): ReminderStatus = entries.first { it.wire == value }
    }
}

enum class TxnType(
    val wire: String,
) {
    ADD("add"),
    REMOVE("remove"),
    ;

    companion object {
        fun fromWire(value: String): TxnType = entries.first { it.wire == value }
    }
}

enum class ExpenseDirection(
    val wire: String,
) {
    /** Money the business gave out ("You gave"). */
    PAID("paid"),

    /** Money the business received ("You got"). */
    RECEIVED("received"),
    ;

    companion object {
        fun fromWire(value: String): ExpenseDirection = entries.first { it.wire == value }
    }
}

enum class BookingSource(
    val wire: String,
) {
    WALK_IN("walk_in"),
    PHONE("phone"),
    REFERRAL("referral"),
    REPEAT("repeat"),
    OTHER("other"),
    ;

    companion object {
        fun fromWire(value: String): BookingSource = entries.first { it.wire == value }
    }
}
