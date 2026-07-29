package com.sa.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sa.assistant.ui.navigation.SaNavHost
import com.sa.assistant.ui.theme.SaTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-Activity host. Everything below this is Compose + Navigation —
 * MainActivity itself stays deliberately empty (no business logic) per
 * the "no God classes / no massive MainActivity" architecture rule.
 *
 * Phase 6 Part 1 addition: [AssistantForegroundService] relaunches this
 * Activity with [EXTRA_WAKE_TRIGGERED] when the "SA" wake word is heard.
 * [wakeTriggerSignal] is a plain counter (not a boolean) so that a second
 * wake-up while the app is already open still re-fires the navigation
 * effect in [SaNavHost] — a boolean that's already `true` wouldn't change.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var wakeTriggerSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeWakeExtra(intent)
        setContent {
            SaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SaNavHost(openChatSignal = wakeTriggerSignal)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeWakeExtra(intent)
    }

    private fun consumeWakeExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_WAKE_TRIGGERED, false) == true) {
            wakeTriggerSignal += 1
            intent.removeExtra(EXTRA_WAKE_TRIGGERED)
        }
    }

    companion object {
        const val EXTRA_WAKE_TRIGGERED = "com.sa.assistant.EXTRA_WAKE_TRIGGERED"
    }
}
