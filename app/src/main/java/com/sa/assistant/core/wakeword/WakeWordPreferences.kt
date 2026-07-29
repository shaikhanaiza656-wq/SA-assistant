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
 * Persists whether background wake-word listening is turned on, which
 * phrase it listens for, and the real Picovoice Porcupine credentials
 * needed for always-on "SA" spotting.
 *
 * [porcupineAccessKey] is the free AccessKey every developer gets from
 * https://console.picovoice.ai after making an account. [porcupineKeywordAsset]
 * is the filename (under app/src/main/assets/porcupine/) of the custom "SA"
 * keyword model trained for this app in that same console — Picovoice does
 * not ship a built-in "SA" keyword, and no one can honestly hand you a
 * pre-trained one outside their console, so this project can't embed one
 * for you. Until both are set, [WakeWordListener] falls back to the
 * existing SpeechRecognizer-loop spotting so wake-word detection still
 * works, just less efficiently.
 *
 * [AssistantForegroundService] observes [isEnabled] to start/stop
 * [WakeWordListener]; [com.sa.assistant.ui.settings.SettingsScreen]
 * reads/writes all of this through [com.sa.assistant.ui.settings.SettingsViewModel].
 */
@Singleton
class WakeWordPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val isEnabledKey = booleanPreferencesKey("wake_word_enabled")
    private val wakePhraseKey = stringPreferencesKey("wake_word_phrase")
    private val porcupineAccessKeyKey = stringPreferencesKey("porcupine_access_key")
    private val porcupineKeywordAssetKey = stringPreferencesKey("porcupine_keyword_asset")

    val isEnabled: Flow<Boolean> = context.wakeWordDataStore.data.map { it[isEnabledKey] ?: false }

    val wakePhrase: Flow<String> = context.wakeWordDataStore.data.map {
        it[wakePhraseKey] ?: DEFAULT_WAKE_PHRASE
    }

    /** Blank/null until the user pastes their real Picovoice Console AccessKey in Settings. */
    val porcupineAccessKey: Flow<String?> = context.wakeWordDataStore.data.map { it[porcupineAccessKeyKey] }

    /** Blank/null until the user's trained "SA.ppn" asset filename is set. Defaults to [DEFAULT_KEYWORD_ASSET]. */
    val porcupineKeywordAsset: Flow<String> = context.wakeWordDataStore.data.map {
        it[porcupineKeywordAssetKey] ?: DEFAULT_KEYWORD_ASSET
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.wakeWordDataStore.edit { it[isEnabledKey] = enabled }
    }

    suspend fun setWakePhrase(phrase: String) {
        context.wakeWordDataStore.edit { it[wakePhraseKey] = phrase.ifBlank { DEFAULT_WAKE_PHRASE } }
    }

    suspend fun setPorcupineAccessKey(key: String?) {
        context.wakeWordDataStore.edit {
            if (key.isNullOrBlank()) it.remove(porcupineAccessKeyKey) else it[porcupineAccessKeyKey] = key.trim()
        }
    }

    suspend fun setPorcupineKeywordAsset(assetFileName: String?) {
        context.wakeWordDataStore.edit {
            if (assetFileName.isNullOrBlank()) it.remove(porcupineKeywordAssetKey) else it[porcupineKeywordAssetKey] = assetFileName.trim()
        }
    }

    companion object {
        /** Where the user drops their Picovoice-Console-trained "SA" keyword file, relative to assets/. */
        const val DEFAULT_KEYWORD_ASSET = "porcupine/SA_android.ppn"
    }
}
