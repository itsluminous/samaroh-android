package com.itsluminous.samaroh.feature.reports.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.designsystem.component.ChipRow
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.reports.domain.RangePreset

/** Catalog label of a preset chip (`reports.range.*`). */
@StringRes
fun RangePreset.labelRes(): Int =
    when (this) {
        RangePreset.MINUS_12M -> R.string.reports_range_minus_12m
        RangePreset.MINUS_3M -> R.string.reports_range_minus_3m
        RangePreset.THIS_MONTH -> R.string.reports_range_this_month
        RangePreset.PLUS_3M -> R.string.reports_range_plus_3m
        RangePreset.PLUS_12M -> R.string.reports_range_plus_12m
        RangePreset.CUSTOM -> R.string.reports_range_custom
    }

/** Test tag of a preset chip: `range_chip_<id>`. */
fun rangeChipTag(preset: RangePreset): String = "range_chip_${preset.id}"

/**
 * THE date-range quick filter of every report page (ADR-104, shared contract
 * `reports-range-filter.md`): one `FilterChip` per [RangePreset] in contract order —
 * −12 months · −3 months · This month · +3 months · +12 months · Custom — inside a
 * horizontally scrolling [ChipRow] (ADR-091: never wraps, never shrinks labels). The
 * five math presets call [onPreset]; Custom calls [onCustomClick] (the picker supplies the
 * dates, so Custom is never "selected" by tapping it alone).
 */
@Composable
fun RangeQuickFilter(
    selected: RangePreset,
    onPreset: (RangePreset) -> Unit,
    onCustomClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ChipRow(modifier = modifier, contentPadding = PaddingValues(horizontal = 16.dp)) {
        RangePreset.CHIPS.forEach { preset ->
            FilterChip(
                selected = selected == preset,
                onClick = { if (preset == RangePreset.CUSTOM) onCustomClick() else onPreset(preset) },
                label = { Text(stringResource(preset.labelRes())) },
                modifier = Modifier.testTag(rangeChipTag(preset)),
            )
        }
    }
}
