package com.itsluminous.samaroh.feature.expenses.home

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.data.settings.ListSortOrder
import com.itsluminous.samaroh.core.data.settings.SummaryPeriod
import com.itsluminous.samaroh.core.designsystem.component.AmountText
import com.itsluminous.samaroh.core.designsystem.component.AmountTone
import com.itsluminous.samaroh.core.designsystem.component.AutoShrinkText
import com.itsluminous.samaroh.core.designsystem.component.EmptyState
import com.itsluminous.samaroh.core.designsystem.component.SamarohCard
import com.itsluminous.samaroh.core.designsystem.component.SamarohFab
import com.itsluminous.samaroh.core.designsystem.component.SortMenuButton
import com.itsluminous.samaroh.core.designsystem.component.SortMenuEntry
import com.itsluminous.samaroh.core.designsystem.theme.animatedListItem
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.expenses.PersonalPartyTag

/** Expenses tab home (§4.2): totals card, search, party list, add-person FAB. */
@Composable
fun ExpensesHomeScreen(
    onPartyClick: (partyId: String) -> Unit,
    onAddPerson: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExpensesHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            // ADR-028 gate: members without expenses.edit/manage_parties cannot add people.
            // Icon-only FAB (ADR-071): contentDescription + long-press explanation carry
            // the meaning the dropped extended-FAB label used to.
            if (state.canManageParties) {
                SamarohFab(onClick = onAddPerson, explanationRes = R.string.expenses_home_add_person) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = stringResource(R.string.expenses_home_add_person))
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TotalsCard(
                gavePaise = state.totals.gavePaise,
                gotPaise = state.totals.gotPaise,
                masked = !state.canViewAmounts,
                period = state.summaryPeriod,
                onPeriodChange = viewModel::onSummaryPeriodChange,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // ADR-069: the party list's header row — search plus the compact sort menu
            // (this screen has no nested top bar; the global app bar is business-level).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::onSearchQueryChange,
                    label = { Text(stringResource(R.string.expenses_home_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                SortMenuButton(
                    entries = partySortMenuEntries(),
                    selected = state.sortOrder,
                    onSelect = viewModel::onSortOrderChange,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            if (!state.hasAnyParty) {
                EmptyState(
                    icon = Icons.Filled.Group,
                    title = stringResource(R.string.expenses_home_empty_title),
                    message = stringResource(R.string.expenses_home_empty_message),
                )
            } else if (state.parties.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = stringResource(R.string.expenses_home_no_results_title),
                    message = stringResource(R.string.expenses_home_no_results_message),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.parties, key = { it.party.id }) { item ->
                        PartyRow(
                            item = item,
                            masked = !state.canViewAmounts,
                            onClick = { onPartyClick(item.party.id) },
                            modifier = animatedListItem(),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/**
 * Header summary card (ADR-091): a compact period switch (This month / This year / All
 * time — persisted) above the "You gave"/"You got" totals for that window. Every text in
 * the card shrinks to fit instead of wrapping so the two cells stay one line each on a
 * 360dp phone in Hindi.
 */
@Composable
private fun TotalsCard(
    gavePaise: Long,
    gotPaise: Long,
    masked: Boolean,
    period: SummaryPeriod,
    onPeriodChange: (SummaryPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    SamarohCard(modifier = modifier) {
        SummaryPeriodSwitch(selected = period, onSelect = onPeriodChange, modifier = Modifier.fillMaxWidth())
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            TotalsCell(
                label = stringResource(R.string.expenses_home_you_gave),
                amountPaise = gavePaise,
                tone = AmountTone.MONEY_OUT,
                masked = masked,
                modifier = Modifier.weight(1f),
            )
            TotalsCell(
                label = stringResource(R.string.expenses_home_you_got),
                amountPaise = gotPaise,
                tone = AmountTone.MONEY_IN,
                masked = masked,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Segment labels may shrink further than amounts — three of them share one card width. */
private const val SEGMENT_LABEL_MIN_FONT_SCALE = 0.5f

/** The three summary windows in switch order (ADR-091), with their localized labels. */
private val summaryPeriodOrder = listOf(SummaryPeriod.THIS_MONTH, SummaryPeriod.THIS_YEAR, SummaryPeriod.ALL_TIME)

@Composable
private fun SummaryPeriod.label(): String =
    stringResource(
        when (this) {
            SummaryPeriod.THIS_MONTH -> R.string.expenses_summary_period_month
            SummaryPeriod.THIS_YEAR -> R.string.expenses_summary_period_year
            SummaryPeriod.ALL_TIME -> R.string.expenses_summary_period_all
        },
    )

/**
 * Single-choice segmented control for the summary window. Labels auto-shrink (no wrap,
 * no ellipsis) so three Hindi labels fit a narrow card; the row carries the localized
 * `expenses.summary.period_label` as its accessible name.
 */
@Composable
private fun SummaryPeriodSwitch(
    selected: SummaryPeriod,
    onSelect: (SummaryPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groupLabel = stringResource(R.string.expenses_summary_period_label)
    SingleChoiceSegmentedButtonRow(modifier = modifier.semantics { contentDescription = groupLabel }) {
        summaryPeriodOrder.forEachIndexed { index, period ->
            SegmentedButton(
                selected = period == selected,
                onClick = { onSelect(period) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = summaryPeriodOrder.size),
                icon = {}, // No check icon: it would steal width from the shrink-to-fit label.
                label = {
                    AutoShrinkText(
                        text = period.label(),
                        style = MaterialTheme.typography.labelLarge,
                        minFontScale = SEGMENT_LABEL_MIN_FONT_SCALE,
                        overflow = TextOverflow.Clip,
                        textAlign = TextAlign.Center,
                    )
                },
            )
        }
    }
}

@Composable
private fun TotalsCell(
    label: String,
    amountPaise: Long,
    tone: AmountTone,
    masked: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Label and amount both shrink to fit (ADR-091) — one line each, never wrapped.
        AutoShrinkText(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
        AmountText(
            amountPaise = amountPaise,
            tone = tone,
            style = MaterialTheme.typography.titleLarge,
            masked = masked,
            autoShrink = true,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun PartyRow(
    item: PartyListItem,
    masked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        leadingContent = { InitialsAvatar(initials = item.initials) },
        headlineContent = {
            // Personal tag renders on its own row BELOW the name so long names keep the
            // full width and wrap naturally instead of squeezing to one word per line.
            Column {
                Text(item.party.name, style = MaterialTheme.typography.bodyLarge)
                if (!item.party.businessRelated) {
                    PersonalPartyTag(modifier = Modifier.padding(top = 2.dp))
                }
            }
        },
        supportingContent = {
            item.lastEntryAt?.let { at ->
                // System-localized relative time ("2 hours ago") — follows the app locale.
                Text(
                    text = DateUtils.getRelativeTimeSpanString(at.toEpochMilli()).toString(),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        trailingContent = {
            AmountText(
                amountPaise = if (item.netBalancePaise < 0) -item.netBalancePaise else item.netBalancePaise,
                tone = if (item.netBalancePaise >= 0) AmountTone.MONEY_OUT else AmountTone.MONEY_IN,
                style = MaterialTheme.typography.titleMedium,
                masked = masked,
            )
        },
    )
}

@Composable
private fun InitialsAvatar(
    initials: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = initials, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** The three party-list orders with localized labels, in menu order (ADR-069). */
@Composable
private fun partySortMenuEntries(): List<SortMenuEntry<ListSortOrder>> =
    listOf(
        SortMenuEntry(ListSortOrder.LAST_UPDATED, stringResource(R.string.common_sort_last_updated)),
        SortMenuEntry(ListSortOrder.NAME_ASC, stringResource(R.string.common_sort_name_asc)),
        SortMenuEntry(ListSortOrder.NAME_DESC, stringResource(R.string.common_sort_name_desc)),
    )
