package com.itsluminous.samaroh.core.google.drive

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DriveLayoutTest {
    @Test
    fun `backups map to Samaroh business backups`() {
        assertThat(DriveLayout.folderPathBelowRoot("Sharma Hall", DriveTarget.Backups))
            .containsExactly("Sharma Hall", "backups")
            .inOrder()
    }

    @Test
    fun `booking invoices map to invoices bookings`() {
        assertThat(DriveLayout.folderPathBelowRoot("Sharma Hall", DriveTarget.BookingInvoices))
            .containsExactly("Sharma Hall", "invoices", "bookings")
            .inOrder()
    }

    @Test
    fun `expense invoices map to invoices expenses party`() {
        assertThat(DriveLayout.folderPathBelowRoot("Sharma Hall", DriveTarget.ExpenseInvoices("Ram Caterers")))
            .containsExactly("Sharma Hall", "invoices", "expenses", "Ram Caterers")
            .inOrder()
    }

    @Test
    fun `inventory images map to images inventory`() {
        assertThat(DriveLayout.folderPathBelowRoot("Sharma Hall", DriveTarget.InventoryImages))
            .containsExactly("Sharma Hall", "images", "inventory")
            .inOrder()
    }

    @Test
    fun `describePath renders full spec layout`() {
        assertThat(DriveLayout.describePath("Sharma Hall", DriveTarget.BookingInvoices, "INV-2026-0042.pdf"))
            .isEqualTo("Samaroh/Sharma Hall/invoices/bookings/INV-2026-0042.pdf")
    }

    @Test
    fun `segments are sanitized against slashes and whitespace runs`() {
        assertThat(DriveLayout.folderPathBelowRoot(" My  Hall / Annex ", DriveTarget.Backups).first())
            .isEqualTo("My Hall - Annex")
    }

    @Test
    fun `root folder name is fixed`() {
        assertThat(DriveLayout.ROOT_FOLDER_NAME).isEqualTo("Samaroh")
    }
}
