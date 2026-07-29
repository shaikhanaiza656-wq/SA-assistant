package com.sa.assistant.core.wakeword

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sa.assistant.data.model.DEFAULT_WAKE_PHRASE
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.wakeWordDataStore by preferencesDataStore(name = "sa_wake_word_settings")

/**
 * Persists whether background wake-word listening is turned on, and which
 * phrase it listens for. [AssistantForegroundService] observes [isEnabled]
 * to start/stop [WakeWordListener]; [com.sa.assistant.ui.settings.SettingsScreen]
 * reads/writes both through [com.sa.assistant.ui.settings.SettingsViewModel].
 */
@Singleton
class WakeWordPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val isEnabledKey = booleanPreferencesKey("wake_word_enabled")
    private val wakePhraseKey = stringPreferencesKey("wake_word_phrase")

    val isEnabled: Flow<Boolean> = context.wakeWordDataStore.data.map { it[isEnabledKey] ?: false }

    val wakePhrase: Flow<String> = context.wakeWordDataStore.data.map {
        it[wakePhraseKey] ?: DEFAULT_WAKE_PHRASE
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.wakeWordDataStore.edit { it[isEnabledKey] = enabled }
    }

    suspend fun setWakePhrase(phrase: String) {
        context.wakeWordDataStore.edit { it[wakePhraseKey] = phrase.ifBlank { DEFAULT_WAKE_PHRASE } }
    }
}
