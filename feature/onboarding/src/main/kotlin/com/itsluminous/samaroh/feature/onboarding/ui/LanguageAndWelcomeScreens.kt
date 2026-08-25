package com.itsluminous.samaroh.feature.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.onboarding.OnboardingUiState
import kotlinx.coroutines.launch

/**
 * §4.0 step 1 — language selection, the FIRST screen before anything else. Every
 * language is rendered in its own script (the catalog values are script-native).
 */
@Composable
internal fun LanguageScreen(
    state: OnboardingUiState,
    onLanguageSelected: (String) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_language_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.onboarding_language_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        state.supportedLocales.forEach { tag ->
            val selected = state.selectedLanguage?.startsWith(tag) == true
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp)
                        .selectable(selected = selected, onClick = { onLanguageSelected(tag) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = { onLanguageSelected(tag) })
                Text(
                    text = languageDisplayName(tag),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().padding(top = 32.dp).defaultMinSize(minHeight = 48.dp),
        ) {
            Text(stringResource(R.string.onboarding_action_continue))
        }
    }
}

/** Locale tag → display name in its OWN script, from the catalog. Unknown tags render raw. */
@Composable
internal fun languageDisplayName(tag: String): String =
    when (tag) {
        "en" -> stringResource(R.string.common_language_en)
        "hi" -> stringResource(R.string.common_language_hi)
        else -> tag
    }

private const val SLIDE_COUNT = 3

/** §4.0 step 2 — welcome carousel: 3 slides max, skippable, "as easy as WhatsApp" tone. */
@Composable
internal fun WelcomeScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { SLIDE_COUNT })
    val scope = rememberCoroutineScope()
    val onLastSlide = pagerState.currentPage == SLIDE_COUNT - 1

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onFinished) {
                Text(stringResource(R.string.onboarding_welcome_skip))
            }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            WelcomeSlide(page)
        }
        Button(
            onClick = {
                if (onLastSlide) {
                    onFinished()
                } else {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        ) {
            Text(
                stringResource(
                    if (onLastSlide) R.string.onboarding_welcome_get_started else R.string.onboarding_welcome_next,
                ),
            )
        }
    }
}

@Composable
private fun WelcomeSlide(
    page: Int,
    modifier: Modifier = Modifier,
) {
    val (icon, titleRes, bodyRes) =
        when (page) {
            0 -> Triple(Icons.Filled.CalendarMonth, R.string.onboarding_welcome_slide1_title, R.string.onboarding_welcome_slide1_body)
            1 ->
                Triple(
                    Icons.Filled.AccountBalanceWallet,
                    R.string.onboarding_welcome_slide2_title,
                    R.string.onboarding_welcome_slide2_body,
                )
            else -> Triple(Icons.Filled.CloudOff, R.string.onboarding_welcome_slide3_title, R.string.onboarding_welcome_slide3_body)
        }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                text = stringResource(bodyRes),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
