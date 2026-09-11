package com.itsluminous.samaroh.feature.notes.home

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.NoteChecklistItem
import com.itsluminous.samaroh.core.model.NoteKind
import com.itsluminous.samaroh.core.model.NoteStatus
import com.itsluminous.samaroh.core.model.NotesPermissions
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.feature.notes.FakeBookingColorCatalog
import com.itsluminous.samaroh.feature.notes.FakeNotesRepository
import com.itsluminous.samaroh.feature.notes.NotesSession
import com.itsluminous.samaroh.feature.notes.domain.NotesSection
import com.itsluminous.samaroh.feature.notes.fakeNotesSession
import com.itsluminous.samaroh.feature.notes.linkFixture
import com.itsluminous.samaroh.feature.notes.noteFixture
import com.itsluminous.samaroh.feature.notes.sweep.NotesTrashSweeper
import com.itsluminous.samaroh.feature.notes.tagFixture
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Notes home logic (ADR-077): create/edit save pipeline, tag link diffing, lifecycle
 * quick actions (complete/trash/restore/purge), inline checklist toggles, permission
 * gates and the startup Trash sweep.
 */
class NotesHomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now: Instant = Instant.parse("2026-09-10T06:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val repository = FakeNotesRepository()

    private fun viewModel(session: NotesSession = fakeNotesSession()) =
        NotesHomeViewModel(
            repository = repository,
            session = session,
            bookingColorsProvider = FakeBookingColorCatalog(),
            sweeper = NotesTrashSweeper(repository, session, clock),
            clock = clock,
        )

    private fun editorOnlySession() =
        fakeNotesSession(
            userId = "member-1",
            isOwner = false,
            permissions = MemberPermissions(notes = NotesPermissions(view = true)),
        )

    @Test
    fun `creating a note saves it with title content color and pin`() =
        runTest {
            val vm = viewModel()
            vm.state.test {
                awaitItemMatching { it.loaded }
                vm.startCreate(NoteKind.NOTE)
                awaitItemMatching { it.editor != null }
                vm.setEditorTitle("Vendors")
                vm.setEditorContent("Call the caterer")
                vm.setEditorColor("peacock")
                vm.toggleEditorPin()
                vm.saveEditor()
                awaitItemMatching { it.editor == null && it.pinned.size + it.others.size == 1 }

                val note = repository.notesFlow.value.single()
                assertThat(note.kind).isEqualTo(NoteKind.NOTE)
                assertThat(note.title).isEqualTo("Vendors")
                assertThat(note.content).isEqualTo("Call the caterer")
                assertThat(note.color).isEqualTo("peacock")
                assertThat(note.pinned).isTrue()
                assertThat(note.status).isEqualTo(NoteStatus.ACTIVE)
                assertThat(note.createdBy).isEqualTo(Fixtures.USER_ID)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `creating a checklist keeps item order and drops blank rows`() =
        runTest {
            val vm = viewModel()
            vm.state.test {
                awaitItemMatching { it.loaded }
                vm.startCreate(NoteKind.CHECKLIST)
                val editor = awaitItemMatching { it.editor != null }.editor!!
                val first = editor.items.single().id
                vm.setChecklistItemText(first, "Milk")
                vm.addChecklistItem()
                val second =
                    vm.state.value.editor!!
                        .items[1]
                        .id
                vm.setChecklistItemText(second, "Sugar")
                vm.toggleEditorChecklistItem(second)
                vm.addChecklistItem() // stays blank — dropped on save
                // Drag reorder (ADR-081): move Sugar above Milk.
                vm.moveChecklistItem(second, 0)
                vm.saveEditor()
                awaitItemMatching { it.editor == null && (it.pinned + it.others).isNotEmpty() }

                val note = repository.notesFlow.value.single()
                assertThat(note.kind).isEqualTo(NoteKind.CHECKLIST)
                assertThat(note.checklist.map { it.text }).containsExactly("Sugar", "Milk").inOrder()
                assertThat(note.checklist.first().done).isTrue()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `editing links and unlinks tags as soft link rows`() =
        runTest {
            repository.notesFlow.value = listOf(noteFixture("n-1"))
            repository.tagsFlow.value = listOf(tagFixture("t-old", "Old"), tagFixture("t-new", "New"))
            repository.linksFlow.value = listOf(linkFixture("n-1", "t-old"))
            val vm = viewModel()
            vm.state.test {
                awaitItemMatching { it.loaded && (it.pinned + it.others).isNotEmpty() }
                vm.openNote("n-1")
                awaitItemMatching { it.editor?.tagIds == setOf("t-old") }
                vm.startEditing()
                vm.toggleEditorTag("t-old") // unlink
                vm.toggleEditorTag("t-new") // link
                vm.saveEditor()
                awaitItemMatching { it.editor == null }

                val links = repository.linksFlow.value
                assertThat(links.single { it.tagId == "t-old" }.deletedAt).isNotNull()
                assertThat(links.single { it.tagId == "t-new" }.deletedAt).isNull()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `createTagFromQuery persists the tag once and selects it`() =
        runTest {
            repository.notesFlow.value = listOf(noteFixture("n-1"))
            val vm = viewModel()
            vm.state.test {
                awaitItemMatching { it.loaded && (it.pinned + it.others).isNotEmpty() }
                vm.openNote("n-1")
                awaitItemMatching { it.editor != null }
                vm.startEditing()
                vm.setTagQuery("Urgent")
                vm.createTagFromQuery()
                awaitItemMatching { it.editor?.tagIds?.size == 1 }

                val tag = repository.tagsFlow.value.single()
                assertThat(tag.name).isEqualTo("Urgent")

                // Same name again (any casing) reuses the live tag — no duplicate row.
                vm.setTagQuery("urgent")
                vm.createTagFromQuery()
                awaitItemMatching { it.editor?.tagQuery == "" }
                assertThat(repository.tagsFlow.value).hasSize(1)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `complete trash restore and purge walk the lifecycle`() =
        runTest {
            repository.notesFlow.value = listOf(noteFixture("n-1"))
            val vm = viewModel()
            vm.state.test {
                awaitItemMatching { it.loaded && (it.pinned + it.others).isNotEmpty() }

                vm.completeNote("n-1")
                awaitItemMatching { (it.pinned + it.others).isEmpty() }
                assertThat(
                    repository.notesFlow.value
                        .single()
                        .status,
                ).isEqualTo(NoteStatus.COMPLETED)
                assertThat(
                    repository.notesFlow.value
                        .single()
                        .completedAt,
                ).isEqualTo(now)

                vm.restoreNote("n-1")
                awaitItemMatching { (it.pinned + it.others).isNotEmpty() }
                assertThat(
                    repository.notesFlow.value
                        .single()
                        .status,
                ).isEqualTo(NoteStatus.ACTIVE)
                assertThat(
                    repository.notesFlow.value
                        .single()
                        .completedAt,
                ).isNull()

                vm.trashNote("n-1")
                awaitItemMatching { (it.pinned + it.others).isEmpty() }
                assertThat(
                    repository.notesFlow.value
                        .single()
                        .status,
                ).isEqualTo(NoteStatus.TRASHED)
                assertThat(
                    repository.notesFlow.value
                        .single()
                        .trashedAt,
                ).isEqualTo(now)

                // Delete forever needs the confirm step (notes.delete gate).
                vm.selectSection(NotesSection.TRASH)
                vm.requestPurge("n-1")
                awaitItemMatching { it.confirmPurgeId == "n-1" }
                vm.confirmPurge()
                awaitItemMatching { it.confirmPurgeId == null }
                assertThat(repository.purgedNoteIds).containsExactly("n-1")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `inline checklist toggle rewrites just that item`() =
        runTest {
            repository.notesFlow.value =
                listOf(
                    noteFixture(
                        "n-1",
                        kind = NoteKind.CHECKLIST,
                        checklist = listOf(NoteChecklistItem("i-1", "Milk"), NoteChecklistItem("i-2", "Sugar")),
                    ),
                )
            val vm = viewModel()
            vm.state.test {
                awaitItemMatching { it.loaded && (it.pinned + it.others).isNotEmpty() }
                vm.toggleChecklistItemInline("n-1", "i-2")
                awaitItemMatching { state ->
                    (state.pinned + state.others)
                        .firstOrNull()
                        ?.note
                        ?.checklist
                        ?.any { it.id == "i-2" && it.done } == true
                }
                val checklist =
                    repository.notesFlow.value
                        .single()
                        .checklist
                assertThat(checklist.single { it.id == "i-2" }.done).isTrue()
                assertThat(checklist.single { it.id == "i-1" }.done).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `view-only members cannot create edit or mutate`() =
        runTest {
            repository.notesFlow.value = listOf(noteFixture("n-1"))
            val vm = viewModel(editorOnlySession())
            vm.state.test {
                val loaded = awaitItemMatching { it.loaded && (it.pinned + it.others).isNotEmpty() && !it.canCreate }
                assertThat(loaded.canEdit).isFalse()
                assertThat(loaded.canDelete).isFalse()

                vm.startCreate(NoteKind.NOTE)
                assertThat(vm.state.value.editor).isNull()

                vm.openNote("n-1")
                awaitItemMatching { it.editor != null }
                vm.startEditing()
                assertThat(
                    vm.state.value.editor!!
                        .editing,
                ).isFalse()

                vm.completeNote("n-1")
                vm.trashNote("n-1")
                vm.togglePin("n-1")
                vm.toggleChecklistItemInline("n-1", "i-x")
                vm.requestPurge("n-1")
                assertThat(
                    repository.notesFlow.value
                        .single()
                        .status,
                ).isEqualTo(NoteStatus.ACTIVE)
                assertThat(vm.state.value.confirmPurgeId).isNull()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `startup sweep purges expired trash for owners`() =
        runTest {
            repository.notesFlow.value =
                listOf(noteFixture("expired", status = NoteStatus.TRASHED, trashedAt = now.minusSeconds(31L * 24 * 3600)))
            val vm = viewModel()
            vm.state.test {
                awaitItemMatching { it.loaded }
                assertThat(repository.purgedNoteIds).containsExactly("expired")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `search and tag selection shape the visible list`() =
        runTest {
            repository.notesFlow.value =
                listOf(noteFixture("n-milk", title = "Milk order"), noteFixture("n-tent", title = "Tent vendor"))
            repository.tagsFlow.value = listOf(tagFixture("t-1", "Vendors"))
            repository.linksFlow.value = listOf(linkFixture("n-tent", "t-1"))
            val vm = viewModel()
            vm.state.test {
                awaitItemMatching { it.loaded && (it.pinned + it.others).size == 2 }

                vm.setSearchQuery("milk")
                val searched = awaitItemMatching { (it.pinned + it.others).size == 1 }
                assertThat((searched.pinned + searched.others).single().note.id).isEqualTo("n-milk")

                vm.setSearchQuery("")
                awaitItemMatching { (it.pinned + it.others).size == 2 }
                vm.selectTag("t-1")
                val tagged = awaitItemMatching { (it.pinned + it.others).size == 1 }
                assertThat((tagged.pinned + tagged.others).single().note.id).isEqualTo("n-tent")

                // Tapping the active tag again clears the filter.
                vm.selectTag("t-1")
                awaitItemMatching { (it.pinned + it.others).size == 2 }
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---- feedback batch (ADR-079) ----

    @Test
    fun `saving an empty new note or checklist dismisses without persisting`() =
        runTest {
            val vm = viewModel()
            vm.state.test {
                awaitItemMatching { it.loaded }
                // Empty plain note.
                vm.startCreate(NoteKind.NOTE)
                awaitItemMatching { it.editor != null }
                vm.saveEditor()
                awaitItemMatching { it.editor == null }
                assertThat(repository.notesFlow.value).isEmpty()

                // Checklist whose only rows are blank.
                vm.startCreate(NoteKind.CHECKLIST)
                awaitItemMatching { it.editor != null }
                vm.addChecklistItem()
                vm.saveEditor()
                awaitItemMatching { it.editor == null }
                assertThat(repository.notesFlow.value).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `selectTagSuggestion picks an existing tag by name or creates a new one`() =
        runTest {
            repository.tagsFlow.value = listOf(tagFixture("t-1", "Vendors"))
            val vm = viewModel()
            vm.state.test {
                awaitItemMatching { it.loaded && it.tags.isNotEmpty() }
                vm.startCreate(NoteKind.NOTE)
                awaitItemMatching { it.editor != null }

                // Existing name (case-insensitive) selects, never duplicates.
                vm.setTagQuery("vendors")
                vm.selectTagSuggestion("vendors")
                awaitItemMatching { it.editor?.tagIds == setOf("t-1") && it.editor?.tagQuery == "" }
                assertThat(repository.tagsFlow.value).hasSize(1)

                // Unknown name creates on the fly and selects it.
                vm.setTagQuery("Urgent")
                vm.selectTagSuggestion("Urgent")
                val state = awaitItemMatching { it.editor?.tagIds?.size == 2 }
                val created = repository.tagsFlow.value.single { it.name == "Urgent" }
                assertThat(state.editor!!.tagIds).contains(created.id)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `rename tag rejects duplicates and commits unique names`() =
        runTest {
            repository.tagsFlow.value = listOf(tagFixture("t-1", "Vendors"), tagFixture("t-2", "Urgent"))
            val vm = viewModel()
            vm.state.test {
                awaitItemMatching { it.loaded && it.tags.size == 2 }
                vm.openManageTags()
                awaitItemMatching { it.manageTags != null }
                vm.startRenameTag("t-2")
                awaitItemMatching { it.manageTags?.renameTagId == "t-2" && it.manageTags?.renameValue == "Urgent" }

                // Duplicate (case-insensitive) → inline error, nothing saved.
                vm.setRenameValue("vendors")
                vm.confirmRenameTag()
                awaitItemMatching { it.manageTags?.renameDuplicate == true }
                assertThat(
                    repository.tagsFlow.value
                        .single { it.id == "t-2" }
                        .name,
                ).isEqualTo("Urgent")

                // Unique name commits and closes the rename row.
                vm.setRenameValue("Suppliers")
                vm.confirmRenameTag()
                awaitItemMatching { it.manageTags?.renameTagId == null && it.tags.any { tag -> tag.name == "Suppliers" } }
                assertThat(
                    repository.tagsFlow.value
                        .single { it.id == "t-2" }
                        .name,
                ).isEqualTo("Suppliers")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `delete tag exposes the linked count then tombstones the tag and its links only`() =
        runTest {
            repository.notesFlow.value = listOf(noteFixture("n-1"), noteFixture("n-2"))
            repository.tagsFlow.value = listOf(tagFixture("t-1", "Vendors"))
            repository.linksFlow.value = listOf(linkFixture("n-1", "t-1"), linkFixture("n-2", "t-1"))
            val vm = viewModel()
            vm.state.test {
                // The confirmation dialog's N: two live linked notes.
                awaitItemMatching { it.loaded && it.tagLinkCounts["t-1"] == 2 }
                vm.openManageTags()
                awaitItemMatching { it.manageTags != null }
                vm.requestDeleteTag("t-1")
                awaitItemMatching { it.manageTags?.confirmDeleteTagId == "t-1" }

                vm.confirmDeleteTag()
                awaitItemMatching { it.tags.isEmpty() }

                // Tag + links tombstoned; the notes themselves untouched.
                assertThat(
                    repository.tagsFlow.value
                        .single()
                        .deletedAt,
                ).isNotNull()
                assertThat(repository.linksFlow.value).hasSize(2)
                repository.linksFlow.value.forEach { assertThat(it.deletedAt).isNotNull() }
                assertThat(repository.notesFlow.value).hasSize(2)
                repository.notesFlow.value.forEach { assertThat(it.deletedAt).isNull() }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `deleting the drawer-filtered tag clears the filter`() =
        runTest {
            repository.notesFlow.value = listOf(noteFixture("n-1"))
            repository.tagsFlow.value = listOf(tagFixture("t-1", "Vendors"))
            repository.linksFlow.value = listOf(linkFixture("n-1", "t-1"))
            val vm = viewModel()
            vm.state.test {
                awaitItemMatching { it.loaded && it.tags.isNotEmpty() }
                vm.selectTag("t-1")
                awaitItemMatching { it.selectedTagId == "t-1" }
                vm.openManageTags()
                vm.requestDeleteTag("t-1")
                awaitItemMatching { it.manageTags?.confirmDeleteTagId == "t-1" }
                vm.confirmDeleteTag()
                awaitItemMatching { it.selectedTagId == null && it.tags.isEmpty() }
                cancelAndIgnoreRemainingEvents()
            }
        }
}

private suspend fun <T> app.cash.turbine.TurbineTestContext<T>.awaitItemMatching(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}
