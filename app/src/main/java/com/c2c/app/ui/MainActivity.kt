package com.c2c.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {

    /** 알림에서 연 일정 ID. 화면이 처리하면 null로 비운다. */
    private val openEventId = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                AppRoot(openEventId = openEventId.value, onOpenEventConsumed = { openEventId.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getLongExtra(EXTRA_EVENT_ID, -1L)?.takeIf { it >= 0 }?.let { openEventId.value = it }
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
    }
}
