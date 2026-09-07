package com.itsluminous.samaroh.feature.inventory

import androidx.annotation.StringRes
import com.itsluminous.samaroh.core.i18n.R

/**
 * Unit dropdown group (ADR-061). Groups render as non-selectable headers in the picker,
 * in this order, matching `shared/units.json` — the canonical cross-app unit list.
 */
enum class UnitGroup(
    /** The group's key in `shared/units.json`. */
    val key: String,
    @StringRes val labelRes: Int,
) {
    COUNT("count", R.string.inventory_masterlist_unit_group_count),
    WEIGHT("weight", R.string.inventory_masterlist_unit_group_weight),
    LIQUID("liquid", R.string.inventory_masterlist_unit_group_liquid),
    DISTANCE("distance", R.string.inventory_masterlist_unit_group_distance),
}

/**
 * Unit dropdown options (§4.3, ADR-061). [wire] is the stored `master_items.unit` value
 * (STABLE — the original five `pcs`/`qty`/`kg`/`litre`/free-text are frozen for
 * compatibility with existing rows and the web app); CUSTOM stores free text. The
 * canonical list lives in `shared/units.json` (single source of truth, like
 * event-types.json) so the web app renders the same set — `UnitCatalogParityTest`
 * verifies this enum matches that file entry-for-entry, in order.
 */
enum class UnitOption(
    val wire: String?,
    /** Dropdown group; null only for CUSTOM (rendered last, ungrouped). */
    val group: UnitGroup?,
    /** Localized label; null only for CUSTOM (label comes from `unit_custom`). */
    @StringRes val labelRes: Int?,
) {
    PIECES("pcs", UnitGroup.COUNT, R.string.inventory_masterlist_unit_pieces),
    QUANTITY("qty", UnitGroup.COUNT, R.string.inventory_masterlist_unit_quantity),
    SETS("sets", UnitGroup.COUNT, R.string.inventory_masterlist_unit_sets),
    UNITS("units", UnitGroup.COUNT, R.string.inventory_masterlist_unit_units),
    ITEMS("items", UnitGroup.COUNT, R.string.inventory_masterlist_unit_items),
    BOXES("boxes", UnitGroup.COUNT, R.string.inventory_masterlist_unit_boxes),
    PACKETS("packets", UnitGroup.COUNT, R.string.inventory_masterlist_unit_packets),
    KG("kg", UnitGroup.WEIGHT, R.string.inventory_masterlist_unit_kg),
    GRAMS("g", UnitGroup.WEIGHT, R.string.inventory_masterlist_unit_grams),
    MILLIGRAMS("mg", UnitGroup.WEIGHT, R.string.inventory_masterlist_unit_milligrams),
    TONS("ton", UnitGroup.WEIGHT, R.string.inventory_masterlist_unit_tons),
    LITRE("litre", UnitGroup.LIQUID, R.string.inventory_masterlist_unit_litre),
    MILLILITRES("ml", UnitGroup.LIQUID, R.string.inventory_masterlist_unit_millilitres),
    CUPS("cup", UnitGroup.LIQUID, R.string.inventory_masterlist_unit_cups),
    METERS("m", UnitGroup.DISTANCE, R.string.inventory_masterlist_unit_meters),
    CENTIMETERS("cm", UnitGroup.DISTANCE, R.string.inventory_masterlist_unit_centimeters),
    MILLIMETERS("mm", UnitGroup.DISTANCE, R.string.inventory_masterlist_unit_millimeters),
    KILOMETERS("km", UnitGroup.DISTANCE, R.string.inventory_masterlist_unit_kilometers),
    FEET("ft", UnitGroup.DISTANCE, R.string.inventory_masterlist_unit_feet),
    INCHES("in", UnitGroup.DISTANCE, R.string.inventory_masterlist_unit_inches),
    CUSTOM(null, null, null),
    ;

    companion object {
        fun fromWire(unit: String): UnitOption = entries.firstOrNull { it.wire == unit } ?: CUSTOM

        /** The options of one [group], in declaration (= shared-file) order. */
        fun ofGroup(group: UnitGroup): List<UnitOption> = entries.filter { it.group == group }
    }
}
