package com.itsluminous.samaroh.feature.expenses.sharetarget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.designsystem.component.EmptyState
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.i18n.R

/**
 * "Who is this invoice for?" (ADR-078): the party picker a Create-invoice share lands
 * on. Signed-out / permission-less states show the graceful message instead; picking
 * a party opens the add-entry screen with the shared file pre-attached.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePartyPickerScreen(
    onPartyPicked: (partyId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SharePartyPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.expenses_share_target_pick_party_title)) },
                navigationIcon = {
                    ExplainableIcon(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        explanationRes = R.string.common_action_close,
                        onClick = {
                            viewModel.abandon()
                            onBack()
                        },
                    )
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state.gate) {
                ShareTargetGate.LOADING -> Unit
                ShareTargetGate.NOT_SIGNED_IN ->
                    EmptyState(
                        icon = Icons.Filled.Lock,
                        title = stringResource(R.string.expenses_share_target_signed_out),
                        message = "",
                    )
                ShareTargetGate.NO_PERMISSION ->
                    EmptyState(
                        icon = Icons.Filled.Lock,
                        title = stringResource(R.string.expenses_share_target_no_permission),
                        message = "",
                    )
                ShareTargetGate.READY -> {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        label = { Text(stringResource(R.string.expenses_home_search_hint)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (!state.hasAnyParty) {
                        EmptyState(
                            icon = Icons.Filled.Group,
                            title = stringResource(R.string.expenses_share_target_empty),
                            message = "",
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.parties, key = { it.id }) { party ->
                                ListItem(
                                    headlineContent = { Text(party.name) },
                                    supportingContent = party.phone?.let { { Text(it) } },
                                    modifier = Modifier.clickable { onPartyPicked(party.id) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}
