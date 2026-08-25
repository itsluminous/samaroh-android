package com.itsluminous.samaroh.feature.menu.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.i18n.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Shared scaffold for every Menu tab screen: localized title, optional back arrow
 * (via [ExplainableIcon], §6), snackbar plumbing for one-shot resource messages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuScreenScaffold(
    @StringRes titleRes: Int,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    @StringRes messageRes: Int? = null,
    onMessageShown: () -> Unit = {},
    scrollable: Boolean = true,
    content: @Composable (Modifier) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val messageText = messageRes?.let { stringResource(it) }
    LaunchedEffect(messageText) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            onMessageShown()
        }
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(titleRes),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        ExplainableIcon(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            explanationRes = R.string.common_action_close,
                            onClick = onBack,
                        )
                    }
                },
            )
        },
    ) { padding ->
        val base = Modifier.fillMaxSize().padding(padding)
        if (scrollable) {
            Column(modifier = base.verticalScroll(rememberScrollState())) {
                content(Modifier)
            }
        } else {
            content(base)
        }
    }
}

/** Localized medium date + short time for "last backup" / "last sync" lines. */
fun formatInstant(instant: Instant): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(instant)
