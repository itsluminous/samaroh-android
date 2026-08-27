package com.itsluminous.samaroh.feature.inventory.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.inventory.UnitOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Localized display label of a stored unit wire value; free-text units display as-is. */
@Composable
fun unitDisplayLabel(unit: String): String =
    when (UnitOption.fromWire(unit)) {
        UnitOption.PIECES -> stringResource(R.string.inventory_masterlist_unit_pieces)
        UnitOption.QUANTITY -> stringResource(R.string.inventory_masterlist_unit_quantity)
        UnitOption.KG -> stringResource(R.string.inventory_masterlist_unit_kg)
        UnitOption.LITRE -> stringResource(R.string.inventory_masterlist_unit_litre)
        UnitOption.CUSTOM -> unit
    }

/** Localized display label of a unit dropdown option (custom shows its own label). */
@Composable
fun unitOptionLabel(option: UnitOption): String =
    when (option) {
        UnitOption.PIECES -> stringResource(R.string.inventory_masterlist_unit_pieces)
        UnitOption.QUANTITY -> stringResource(R.string.inventory_masterlist_unit_quantity)
        UnitOption.KG -> stringResource(R.string.inventory_masterlist_unit_kg)
        UnitOption.LITRE -> stringResource(R.string.inventory_masterlist_unit_litre)
        UnitOption.CUSTOM -> stringResource(R.string.inventory_masterlist_unit_custom)
    }

/** Locale-aware medium date for the "Updated {date}" row line. */
fun formatDate(instant: Instant): String =
    DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(instant)

/** Locale-aware medium date + short time for transaction-history rows. */
fun formatDateTime(instant: Instant): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(instant)
