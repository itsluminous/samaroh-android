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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.designsystem.component.AmountText
import com.itsluminous.samaroh.core.designsystem.component.AmountTone
import com.itsluminous.samaroh.core.designsystem.component.EmptyState
import com.itsluminous.samaroh.core.designsystem.component.SamarohCard
import com.itsluminous.samaroh.core.designsystem.component.SamarohExtendedFab
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
            SamarohExtendedFab(
                onClick = onAddPerson,
                icon = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
                text = { Text(stringResource(R.string.expenses_home_add_person)) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TotalsCard(
                gavePaise = state.totals.gavePaise,
                gotPaise = state.totals.gotPaise,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                label = { Text(stringResource(R.string.expenses_home_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
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

@Composable
private fun TotalsCard(
    gavePaise: Long,
    gotPaise: Long,
    modifier: Modifier = Modifier,
) {
    SamarohCard(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TotalsCell(
                label = stringResource(R.string.expenses_home_you_gave),
                amountPaise = gavePaise,
                tone = AmountTone.MONEY_OUT,
                modifier = Modifier.weight(1f),
            )
            TotalsCell(
                label = stringResource(R.string.expenses_home_you_got),
                amountPaise = gotPaise,
                tone = AmountTone.MONEY_IN,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TotalsCell(
    label: String,
    amountPaise: Long,
    tone: AmountTone,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        AmountText(amountPaise = amountPaise, tone = tone, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun PartyRow(
    item: PartyListItem,
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
