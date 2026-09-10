package com.itsluminous.samaroh.core.sync.wire

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * NOTES module wire contract (ADR-077): enum casing for status/kind, checklist jsonb
 * pass-through, completed_at/trashed_at timestamp normalization and the composite-PK
 * link spec.
 */
class NotesWireTest {
    @Test
    fun `notes status and kind lowercase on the wire and uppercase locally`() {
        val wire =
            WireConverter.toWire(
                "notes",
                """{"id":"n-1","kind":"CHECKLIST","status":"TRASHED","title":"t"}""",
            )
        assertThat(wire.getValue("kind").jsonPrimitive.content).isEqualTo("checklist")
        assertThat(wire.getValue("status").jsonPrimitive.content).isEqualTo("trashed")

        val local =
            WireConverter.toLocal(
                "notes",
                kotlinx.serialization.json.Json
                    .parseToJsonElement("""{"id":"n-1","kind":"note","status":"active"}""")
                    .jsonObject,
            )
        assertThat(local.getValue("kind").jsonPrimitive.content).isEqualTo("NOTE")
        assertThat(local.getValue("status").jsonPrimitive.content).isEqualTo("ACTIVE")
    }

    @Test
    fun `checklist jsonb passes through both directions untouched`() {
        val payload = """{"id":"n-1","checklist":[{"id":"i-1","text":"Milk","done":true}],"status":"ACTIVE"}"""
        val wire = WireConverter.toWire("notes", payload)
        assertThat(wire.getValue("checklist").jsonArray).hasSize(1)
        assertThat(
            wire
                .getValue("checklist")
                .jsonArray[0]
                .jsonObject
                .getValue("text")
                .jsonPrimitive.content,
        ).isEqualTo("Milk")

        val local = WireConverter.toLocal("notes", wire)
        assertThat(local.getValue("checklist").jsonArray).isEqualTo(wire.getValue("checklist").jsonArray)
    }

    @Test
    fun `completed_at and trashed_at normalize like every other timestamptz`() {
        val local =
            WireConverter.toLocal(
                "notes",
                kotlinx.serialization.json.Json
                    .parseToJsonElement(
                        """{"id":"n-1","completed_at":"2026-09-01T10:00:00.123456+00:00","trashed_at":"2026-09-02T10:00:00+05:30"}""",
                    ).jsonObject,
            )
        assertThat(local.getValue("completed_at").jsonPrimitive.content).isEqualTo("2026-09-01T10:00:00.123Z")
        assertThat(local.getValue("trashed_at").jsonPrimitive.content).isEqualTo("2026-09-02T04:30:00Z")
    }

    @Test
    fun `notes tables are registered business-scoped with the composite link spec`() {
        val notes = SyncTables.byName("notes")!!
        assertThat(notes.businessScoped).isTrue()
        assertThat(notes.enumFields).containsExactly("status", "kind")
        assertThat(notes.hasUpdatedAt).isTrue()

        assertThat(SyncTables.byName("note_tags")!!.businessScoped).isTrue()

        val links = SyncTables.byName("note_tag_links")!!
        assertThat(links.idColumn).isEqualTo("note_id")
        assertThat(links.idColumn2).isEqualTo("tag_id")
    }

    @Test
    fun `entityIdOf composes composite ids and passes single ids through`() {
        val linkRow =
            kotlinx.serialization.json.Json
                .parseToJsonElement("""{"note_id":"n-1","tag_id":"t-9","business_id":"b-1"}""")
                .jsonObject
        assertThat(SyncTables.byName("note_tag_links")!!.entityIdOf(linkRow)).isEqualTo("n-1|t-9")

        val noteRow =
            kotlinx.serialization.json.Json
                .parseToJsonElement("""{"id":"n-1"}""")
                .jsonObject
        assertThat(SyncTables.byName("notes")!!.entityIdOf(noteRow)).isEqualTo("n-1")
    }
}
