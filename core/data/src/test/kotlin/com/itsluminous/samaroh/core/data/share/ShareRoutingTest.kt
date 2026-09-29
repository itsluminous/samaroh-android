package com.itsluminous.samaroh.core.data.share

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.ExpensesPermissions
import com.itsluminous.samaroh.core.model.FilesPermissions
import com.itsluminous.samaroh.core.model.InventoryPermissions
import com.itsluminous.samaroh.core.model.MemberPermissions
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Share chooser row visibility per (MIME, count, permissions) — ADR-086 / design D18. */
@RunWith(RobolectricTestRunner::class)
class ShareRoutingTest {
    private fun shared(
        mime: String,
        name: String = "f",
    ) = SharedFile(Uri.parse("content://x/$name"), mime, name, sizeBytes = 10)

    private val image = shared("image/jpeg", "a.jpg")
    private val pdf = shared("application/pdf", "a.pdf")
    private val doc = shared("application/msword", "a.doc")

    @Test
    fun `owner with a single image gets all three rows in order`() {
        assertThat(ShareRouting.availableActions(listOf(image), isOwner = true, permissions = MemberPermissions()))
            .containsExactly(ShareAction.CREATE_INVOICE, ShareAction.SET_ITEM_PHOTO, ShareAction.SAVE_TO_FILES)
            .inOrder()
    }

    @Test
    fun `a single pdf offers invoice and files but not item photo`() {
        assertThat(ShareRouting.availableActions(listOf(pdf), isOwner = true, permissions = MemberPermissions()))
            .containsExactly(ShareAction.CREATE_INVOICE, ShareAction.SAVE_TO_FILES)
            .inOrder()
    }

    @Test
    fun `any other mime offers only save to files`() {
        assertThat(ShareRouting.availableActions(listOf(doc), isOwner = true, permissions = MemberPermissions()))
            .containsExactly(ShareAction.SAVE_TO_FILES)
    }

    @Test
    fun `multiple files offer only save to files even when all are images`() {
        assertThat(ShareRouting.availableActions(listOf(image, image), isOwner = true, permissions = MemberPermissions()))
            .containsExactly(ShareAction.SAVE_TO_FILES)
    }

    @Test
    fun `more than twenty files offer nothing`() {
        assertThat(ShareRouting.availableActions(List(21) { image }, isOwner = true, permissions = MemberPermissions())).isEmpty()
    }

    @Test
    fun `rows are permission-hidden for members`() {
        val none = ShareRouting.availableActions(listOf(image), isOwner = false, permissions = MemberPermissions())
        assertThat(none).isEmpty()

        val uploader = MemberPermissions(files = FilesPermissions(view = true, upload = true))
        assertThat(ShareRouting.availableActions(listOf(image), isOwner = false, permissions = uploader))
            .containsExactly(ShareAction.SAVE_TO_FILES)

        val expenses = MemberPermissions(expenses = ExpensesPermissions(view = true, create = true))
        assertThat(ShareRouting.availableActions(listOf(pdf), isOwner = false, permissions = expenses))
            .containsExactly(ShareAction.CREATE_INVOICE)

        val inventory = MemberPermissions(inventory = InventoryPermissions(view = true, manageMasterItems = true))
        assertThat(ShareRouting.availableActions(listOf(image), isOwner = false, permissions = inventory))
            .containsExactly(ShareAction.SET_ITEM_PHOTO)
        // files.view without upload is not enough to save.
        val viewer = MemberPermissions(files = FilesPermissions(view = true))
        assertThat(ShareRouting.availableActions(listOf(doc), isOwner = false, permissions = viewer)).isEmpty()
    }

    @Test
    fun `empty payload offers nothing`() {
        assertThat(ShareRouting.availableActions(emptyList(), isOwner = true, permissions = MemberPermissions())).isEmpty()
    }
}
