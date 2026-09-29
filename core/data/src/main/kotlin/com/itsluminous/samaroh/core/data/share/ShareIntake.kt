package com.itsluminous.samaroh.core.data.share

import android.net.Uri
import com.itsluminous.samaroh.core.model.FileItem
import com.itsluminous.samaroh.core.model.MemberPermissions
import javax.inject.Inject
import javax.inject.Singleton

/** One item delivered by the system share sheet (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`). */
data class SharedFile(
    val uri: Uri,
    val mimeType: String,
    val displayName: String,
    /** Size reported by the content provider, or null when unknown (checked again at staging). */
    val sizeBytes: Long?,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")

    val isPdf: Boolean get() = mimeType.equals(FileItem.MIME_PDF, ignoreCase = true)
}

/** The rows of the unified "Save to Samaroh" chooser (ADR-086). */
enum class ShareAction {
    /** Existing ADR-078 flow: a single image/PDF becomes a bill on a new expense entry. */
    CREATE_INVOICE,

    /** A single image becomes a master item's photo (existing edit-item pipeline). */
    SET_ITEM_PHOTO,

    /** Any files (≤ 20) are uploaded into a Files-module folder. */
    SAVE_TO_FILES,
}

/**
 * Pure routing decision behind the share chooser (ADR-086, design D18) — unit-tested:
 * which rows are offered for a given share payload and member permissions. Rows are
 * PERMISSION-HIDDEN (never greyed, ADR-038); owners pass every permission gate.
 */
object ShareRouting {
    fun availableActions(
        files: List<SharedFile>,
        isOwner: Boolean,
        permissions: MemberPermissions,
    ): List<ShareAction> {
        if (files.isEmpty()) return emptyList()
        val single = files.singleOrNull()
        return buildList {
            if (single != null && (single.isImage || single.isPdf) && (isOwner || permissions.expenses.create)) {
                add(ShareAction.CREATE_INVOICE)
            }
            if (single != null && single.isImage && (isOwner || permissions.inventory.manageMasterItems)) {
                add(ShareAction.SET_ITEM_PHOTO)
            }
            if (files.size <= FileItem.MAX_BATCH && (isOwner || permissions.files.upload)) {
                add(ShareAction.SAVE_TO_FILES)
            }
        }
    }
}

/**
 * Hands the shared files from the activity's intent to whichever feature the chooser
 * routes to (ADR-086). Uris do not survive nav-argument encoding (and the read grant is
 * process-scoped), so the payload rides in this one-shot process singleton; every
 * consumer clears it so a stale share is never re-applied.
 */
@Singleton
class ShareIntakeHolder
    @Inject
    constructor() {
        private var pending: List<SharedFile> = emptyList()

        fun set(files: List<SharedFile>) {
            pending = files
        }

        fun peek(): List<SharedFile> = pending

        fun consume(): List<SharedFile> = pending.also { pending = emptyList() }

        fun clear() {
            pending = emptyList()
        }
    }
