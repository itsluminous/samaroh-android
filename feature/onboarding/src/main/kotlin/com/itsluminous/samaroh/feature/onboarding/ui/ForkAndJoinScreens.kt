package com.itsluminous.samaroh.feature.onboarding.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.designsystem.component.EmptyState
import com.itsluminous.samaroh.core.designsystem.component.SamarohCard
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.onboarding.InviteSummary
import com.itsluminous.samaroh.feature.onboarding.OnboardingUiState

/**
 * §4.0 step 4 — the create-vs-join fork. Pending invitations were auto-detected right
 * after sign-in (server-side auto-activation + client refresh, §3) and surface as a
 * badge on the join option.
 */
@Composable
internal fun ForkScreen(
    state: OnboardingUiState,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_fork_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp),
        )
        SamarohCard(modifier = Modifier.clickable(onClick = onCreate)) {
            Text(text = stringResource(R.string.onboarding_fork_create), style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(R.string.onboarding_fork_create_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        SamarohCard(modifier = Modifier.padding(top = 16.dp).clickable(onClick = onJoin)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.onboarding_fork_join),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (state.invites.isNotEmpty()) {
                    Badge {
                        Text(
                            pluralStringResource(
                                R.plurals.onboarding_fork_invite_badge,
                                state.invites.size,
                                state.invites.size,
                            ),
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.onboarding_fork_join_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** §4.0 step 4 (join path) — accept an auto-detected invitation. */
@Composable
internal fun JoinScreen(
    state: OnboardingUiState,
    onAccept: (InviteSummary) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = stringResource(R.string.onboarding_join_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        if (state.invites.isEmpty()) {
            Column(modifier = Modifier.weight(1f)) {
                EmptyState(
                    icon = Icons.Filled.Mail,
                    title = stringResource(R.string.onboarding_join_title),
                    message = stringResource(R.string.onboarding_join_empty),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
            ) {
                Text(stringResource(R.string.onboarding_join_refresh))
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.invites, key = { it.memberId }) { invite ->
                    SamarohCard {
                        Text(
                            text = invite.businessName ?: invite.displayName,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(R.string.onboarding_join_invited_as, invite.displayName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Button(
                            onClick = { onAccept(invite) },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text(stringResource(R.string.onboarding_join_accept))
                        }
                    }
                }
            }
        }
    }
}
