package com.itsluminous.samaroh.feature.booking.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingStatus
import org.junit.Test

/**
 * ADR-054 action matrix: which card actions show, by status × marker × permissions.
 * The sheet renders exactly this state, so this IS the card's action-visibility test.
 */
class BookingCardActionsTest {
    private fun actions(
        status: BookingStatus,
        isMarker: Boolean = false,
        canEdit: Boolean = true,
        canDelete: Boolean = true,
        canRecordPayment: Boolean = true,
        canInvoice: Boolean = true,
    ) = BookingCardActions.forBooking(status, isMarker, canEdit, canDelete, canRecordPayment, canInvoice)

    private val activeStatuses = listOf(BookingStatus.TENTATIVE, BookingStatus.CONFIRMED, BookingStatus.COMPLETED)

    @Test
    fun `active statuses show the full action surface with all permissions`() {
        activeStatuses.forEach { status ->
            val state = actions(status)
            assertThat(state.showEdit).isTrue()
            assertThat(state.showRecordPayment).isTrue()
            assertThat(state.showInvoice).isTrue()
            assertThat(state.showWhatsAppReminder).isTrue()
            assertThat(state.showCancel).isTrue()
            assertThat(state.showRestore).isFalse()
            assertThat(state.showDelete).isFalse()
        }
    }

    @Test
    fun `cancelled shows ONLY restore and delete with all permissions`() {
        val state = actions(BookingStatus.CANCELLED)
        assertThat(state.showEdit).isFalse()
        assertThat(state.showRecordPayment).isFalse()
        assertThat(state.showInvoice).isFalse()
        assertThat(state.showWhatsAppReminder).isFalse()
        assertThat(state.showCancel).isFalse()
        assertThat(state.showRestore).isTrue()
        assertThat(state.showDelete).isTrue()
    }

    @Test
    fun `cancelled marker still shows only restore and delete`() {
        val state = actions(BookingStatus.CANCELLED, isMarker = true)
        assertThat(state.showRestore).isTrue()
        assertThat(state.showDelete).isTrue()
        assertThat(state.showRecordPayment).isFalse()
        assertThat(state.showInvoice).isFalse()
        assertThat(state.showWhatsAppReminder).isFalse()
    }

    @Test
    fun `restore is gated on booking edit`() {
        val state = actions(BookingStatus.CANCELLED, canEdit = false)
        assertThat(state.showRestore).isFalse()
        assertThat(state.showDelete).isTrue()
    }

    @Test
    fun `delete is gated on booking delete`() {
        val state = actions(BookingStatus.CANCELLED, canDelete = false)
        assertThat(state.showRestore).isTrue()
        assertThat(state.showDelete).isFalse()
    }

    @Test
    fun `viewer-only cancelled card offers nothing`() {
        val state =
            actions(
                BookingStatus.CANCELLED,
                canEdit = false,
                canDelete = false,
                canRecordPayment = false,
                canInvoice = false,
            )
        assertThat(state).isEqualTo(
            BookingCardActionState(
                showEdit = false,
                showRecordPayment = false,
                showInvoice = false,
                showWhatsAppReminder = false,
                showCancel = false,
                showRestore = false,
                showDelete = false,
            ),
        )
    }

    @Test
    fun `active card gates edit cancel payment and invoice on their permissions`() {
        val state =
            actions(
                BookingStatus.CONFIRMED,
                canEdit = false,
                canDelete = false,
                canRecordPayment = false,
                canInvoice = false,
            )
        assertThat(state.showEdit).isFalse()
        assertThat(state.showCancel).isFalse()
        assertThat(state.showRecordPayment).isFalse()
        assertThat(state.showInvoice).isFalse()
        // The WhatsApp reminder never had a permission gate — sharing text is free.
        assertThat(state.showWhatsAppReminder).isTrue()
    }

    @Test
    fun `active marker drops the whole money surface but keeps edit and cancel`() {
        val state = actions(BookingStatus.CONFIRMED, isMarker = true)
        assertThat(state.showEdit).isTrue()
        assertThat(state.showCancel).isTrue()
        assertThat(state.showRecordPayment).isFalse()
        assertThat(state.showInvoice).isFalse()
        assertThat(state.showWhatsAppReminder).isFalse()
    }
}
