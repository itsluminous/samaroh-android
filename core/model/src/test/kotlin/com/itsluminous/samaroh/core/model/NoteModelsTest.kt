package com.itsluminous.samaroh.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.Instant

/**
 * NOTES module models (ADR-077): wire-shape serialization, defaulted decode for
 * absent columns, checklist jsonb ↔ model list round-trip and enum tolerance.
 */
class NoteModelsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val encodeDefaults = Json { encodeDefaults = true }

    @Test
    fun `note decodes from a wire-shaped row with defaults for absent fields`() {
        val decoded =
            json.decodeFromString(
                Note.serializer(),
                """
                {
                  "id": "n-1",
                  "business_id": "b-1",
                  "created_by": "u-1",
                  "created_at": "2026-09-01T10:00:00Z",
                  "updated_at": "2026-09-01T10:00:00Z"
                }
                """.trimIndent(),
            )

        assertThat(decoded.kind).isEqualTo(NoteKind.NOTE)
        assertThat(decoded.status).isEqualTo(NoteStatus.ACTIVE)
        assertThat(decoded.checklist).isEmpty()
        assertThat(decoded.pinned).isFalse()
        assertThat(decoded.color).isNull()
        assertThat(decoded.trashedAt).isNull()
    }

    @Test
    fun `checklist round-trips as the jsonb array shape`() {
        val note =
            Note(
                id = "n-2",
                businessId = "b-1",
                kind = NoteKind.CHECKLIST,
                title = "Shopping",
                checklist =
                    listOf(
                        NoteChecklistItem(id = "i-1", text = "Milk", done = true),
                        NoteChecklistItem(id = "i-2", text = "Sugar"),
                    ),
                createdBy = "u-1",
                createdAt = Instant.parse("2026-09-01T10:00:00Z"),
                updatedAt = Instant.parse("2026-09-01T10:00:00Z"),
            )

        val encoded = encodeDefaults.encodeToString(Note.serializer(), note)
        assertThat(encoded).contains("\"checklist\":[{\"id\":\"i-1\",\"text\":\"Milk\",\"done\":true}")

        val decoded = json.decodeFromString(Note.serializer(), encoded)
        assertThat(decoded).isEqualTo(note)
        assertThat(decoded.checklist[1].done).isFalse()
    }

    @Test
    fun `note status wire values map both ways`() {
        assertThat(NoteStatus.fromWire("active")).isEqualTo(NoteStatus.ACTIVE)
        assertThat(NoteStatus.fromWire("completed")).isEqualTo(NoteStatus.COMPLETED)
        assertThat(NoteStatus.fromWire("trashed")).isEqualTo(NoteStatus.TRASHED)
        assertThat(NoteStatus.TRASHED.wire).isEqualTo("trashed")
    }

    @Test
    fun `note kind fromWire is tolerant - unknown degrades to NOTE`() {
        assertThat(NoteKind.fromWire("checklist")).isEqualTo(NoteKind.CHECKLIST)
        assertThat(NoteKind.fromWire("something_new")).isEqualTo(NoteKind.NOTE)
    }

    @Test
    fun `tag link serializes with wire column names`() {
        val link =
            NoteTagLink(
                noteId = "n-1",
                tagId = "t-1",
                businessId = "b-1",
                createdAt = Instant.parse("2026-09-01T10:00:00Z"),
                updatedAt = Instant.parse("2026-09-01T10:00:00Z"),
            )
        val encoded = encodeDefaults.encodeToString(NoteTagLink.serializer(), link)

        assertThat(encoded).contains("\"note_id\":\"n-1\"")
        assertThat(encoded).contains("\"tag_id\":\"t-1\"")
        assertThat(encoded).contains("\"business_id\":\"b-1\"")
        assertThat(json.decodeFromString(NoteTagLink.serializer(), encoded)).isEqualTo(link)
    }

    @Test
    fun `notes permissions default to false and presets grant per the schema guidance`() {
        // Absent module (older permission JSON) decodes as no access.
        val legacy = json.decodeFromString(MemberPermissions.serializer(), """{ "booking": { "view": true } }""")
        assertThat(legacy.notes.view).isFalse()
        assertThat(legacy.notes.create).isFalse()

        // Viewer = view; Staff = view+create; Manager = all (ADR-077 preset guidance).
        assertThat(MemberPermissions.viewer().notes).isEqualTo(NotesPermissions(view = true))
        assertThat(MemberPermissions.staff().notes).isEqualTo(NotesPermissions(view = true, create = true))
        assertThat(MemberPermissions.manager().notes)
            .isEqualTo(NotesPermissions(view = true, create = true, edit = true, delete = true))
    }
}
