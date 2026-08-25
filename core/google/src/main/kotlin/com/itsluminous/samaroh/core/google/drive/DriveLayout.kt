package com.itsluminous.samaroh.core.google.drive

/**
 * Where a file belongs inside the business's Drive tree (spec §9.1):
 *
 * ```
 * Samaroh/
 * └── {Business Name}/
 *     ├── backups/backup-2026-08-25-0900.zip
 *     ├── invoices/bookings/INV-2026-0042.pdf
 *     ├── invoices/expenses/{party}/{timestamp}-{filename}
 *     └── images/inventory/{item-name}.webp
 * ```
 */
sealed interface DriveTarget {
    /** `backups/` — backup ZIP archives (§4.4). */
    data object Backups : DriveTarget

    /** `invoices/bookings/` — generated booking invoice PDFs (§4.1). */
    data object BookingInvoices : DriveTarget

    /** `invoices/expenses/{party}/` — expense attachment originals (§4.2). */
    data class ExpenseInvoices(
        val partyName: String,
    ) : DriveTarget

    /** `images/inventory/` — master-item images (§4.3). */
    data object InventoryImages : DriveTarget
}

/** Pure mapping of the §9.1 Drive layout — no I/O, unit-tested. */
object DriveLayout {
    /** Fixed root folder name; its Drive id is cached in `google_accounts.drive_root_folder_id`. */
    const val ROOT_FOLDER_NAME = "Samaroh"

    /**
     * Folder chain BELOW the cached root folder, ordered parent-first. The first segment
     * is always the business folder.
     */
    fun folderPathBelowRoot(
        businessName: String,
        target: DriveTarget,
    ): List<String> {
        val base = listOf(sanitizeSegment(businessName))
        return base +
            when (target) {
                DriveTarget.Backups -> listOf("backups")
                DriveTarget.BookingInvoices -> listOf("invoices", "bookings")
                is DriveTarget.ExpenseInvoices -> listOf("invoices", "expenses", sanitizeSegment(target.partyName))
                DriveTarget.InventoryImages -> listOf("images", "inventory")
            }
    }

    /** Human-readable `a/b/c` form of the full path (root included) — used in logs/docs. */
    fun describePath(
        businessName: String,
        target: DriveTarget,
        fileName: String,
    ): String = (listOf(ROOT_FOLDER_NAME) + folderPathBelowRoot(businessName, target) + fileName).joinToString("/")

    /**
     * Drive folder names cannot contain `/`; also collapse whitespace runs so
     * `" My  Hall "` and `"My Hall"` land in one folder.
     */
    private fun sanitizeSegment(name: String): String =
        name
            .replace('/', '-')
            .trim()
            .replace(Regex("\\s+"), " ")
            .ifEmpty { "-" }
}
