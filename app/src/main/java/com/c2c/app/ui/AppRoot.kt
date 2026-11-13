package com.c2c.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.c2c.app.data.AppSettings

private const val TAB_EVENTS = 0
private const val TAB_SETTINGS = 1

/** 탭 두 개: 생성된 일정 / 설정. 아직 API 키를 넣지 않았다면 설정 탭부터 보여준다. */
@Composable
fun AppRoot(openEventId: Long?, onOpenEventConsumed: () -> Unit) {
    val context = LocalContext.current
    var tab by rememberSaveable {
        mutableIntStateOf(if (AppSettings(context).apiKey.isBlank()) TAB_SETTINGS else TAB_EVENTS)
    }
    val snackbar = remember { SnackbarHostState() }
    var showFeedback by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(openEventId) { if (openEventId != null) tab = TAB_EVENTS }

    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == TAB_EVENTS, onClick = { tab = TAB_EVENTS }, text = { Text("생성된 일정") })
                    Tab(selected = tab == TAB_SETTINGS, onClick = { tab = TAB_SETTINGS }, text = { Text("설정") })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                TAB_EVENTS -> EventsScreen(
                    snackbar = snackbar,
                    openEventId = openEventId,
                    onOpenEventConsumed = onOpenEventConsumed,
                    onGoToSettings = { tab = TAB_SETTINGS },
                )
                else -> if (showFeedback) {
                    FeedbackScreen(snackbar = snackbar, onBack = { showFeedback = false })
                } else {
                    SettingsScreen(onOpenFeedback = { showFeedback = true })
                }
            }
        }
    }
}
