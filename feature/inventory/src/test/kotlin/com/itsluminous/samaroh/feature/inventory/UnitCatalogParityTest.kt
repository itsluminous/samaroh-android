package com.itsluminous.samaroh.feature.inventory

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * `shared/units.json` is the CANONICAL cross-app unit list (ADR-061, same
 * single-source-of-truth contract as event-types.json); this module binds it to the
 * compile-time [UnitOption]/[UnitGroup] enums for lint-checked labels. This test keeps
 * the binding honest: every group and unit in the shared file must exist in the enums
 * with the same wire value, the same grouping and the SAME ORDER (the picker renders in
 * declaration order), and vice versa — a drift on either side fails the build.
 */
class UnitCatalogParityTest {
    @Serializable
    private data class UnitsFile(
        val groups: List<Group>,
        val custom: Custom,
    )

    @Serializable
    private data class Group(
        val key: String,
        @SerialName("label_key") val labelKey: String,
        val units: List<UnitEntry>,
    )

    @Serializable
    private data class UnitEntry(
        val key: String,
        val wire: String,
        @SerialName("label_key") val labelKey: String,
    )

    @Serializable
    private data class Custom(
        val key: String,
        @SerialName("label_key") val labelKey: String,
    )

    private val file: UnitsFile by lazy {
        // Module dir is feature/inventory — the shared submodule lives at the repo root.
        val raw = File("../../shared/units.json").readText()
        Json { ignoreUnknownKeys = true }.decodeFromString(UnitsFile.serializer(), raw)
    }

    @Test
    fun `groups match the shared file - keys and order`() {
        assertThat(UnitGroup.entries.map { it.key }).isEqualTo(file.groups.map { it.key })
    }

    @Test
    fun `units match the shared file - wire values, grouping and order`() {
        val fromFile = file.groups.flatMap { group -> group.units.map { it.wire to group.key } }
        val fromEnum =
            UnitOption.entries
                .filter { it != UnitOption.CUSTOM }
                .map { it.wire to it.group?.key }
        assertThat(fromEnum).isEqualTo(fromFile)
    }

    @Test
    fun `the original five wire values are frozen for compatibility`() {
        // Existing rows (and the web app) store these exact strings — never change them.
        assertThat(UnitOption.PIECES.wire).isEqualTo("pcs")
        assertThat(UnitOption.QUANTITY.wire).isEqualTo("qty")
        assertThat(UnitOption.KG.wire).isEqualTo("kg")
        assertThat(UnitOption.LITRE.wire).isEqualTo("litre")
        assertThat(UnitOption.CUSTOM.wire).isNull()
        assertThat(file.custom.key).isEqualTo("custom")
    }

    @Test
    fun `fromWire round-trips every canonical wire and falls back to CUSTOM`() {
        for (group in file.groups) {
            for (unit in group.units) {
                assertThat(UnitOption.fromWire(unit.wire).wire).isEqualTo(unit.wire)
            }
        }
        assertThat(UnitOption.fromWire("my own unit")).isEqualTo(UnitOption.CUSTOM)
        // A stored free-text unit that happens to equal a NEW wire value now renders
        // with the canonical label — deliberate (e.g. a legacy custom "g" becomes Grams).
        assertThat(UnitOption.fromWire("g")).isEqualTo(UnitOption.GRAMS)
    }

    @Test
    fun `every unit and group has a distinct label resource`() {
        val labels = UnitOption.entries.mapNotNull { it.labelRes } + UnitGroup.entries.map { it.labelRes }
        assertThat(labels).containsNoDuplicates()
    }
}
