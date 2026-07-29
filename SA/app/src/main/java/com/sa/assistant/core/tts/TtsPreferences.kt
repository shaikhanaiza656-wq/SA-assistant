package com.sa.assistant.core.tts

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sa.assistant.data.model.TTS_DEFAULT_PITCH
import com.sa.assistant.data.model.TTS_DEFAULT_RATE
import com.sa.assistant.data.model.TtsPrefsSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.ttsDataStore by preferencesDataStore(name = "sa_tts_settings")

/**
 * Persists whether SA reads its replies aloud, which device voice it uses,
 * and the speech rate/pitch. [voiceName] is nullable/blank until the user
 * picks one in [com.sa.assistant.ui.settings.SettingsScreen] — until then
 * [com.sa.assistant.core.tts.SaTextToSpeech] just uses whatever voice the
 * engine defaults to, which is honest (no fake "auto-selected best voice"
 * claim).
 */
@Singleton
class TtsPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val isEnabledKey = booleanPreferencesKey("tts_enabled")
    private val voiceNameKey = stringPreferencesKey("tts_voice_name")
    private val rateKey = floatPreferencesKey("tts_rate")
    private val pitchKey = floatPreferencesKey("tts_pitch")

    val isEnabled: Flow<Boolean> = context.ttsDataStore.data.map { it[isEnabledKey] ?: false }
    val voiceName: Flow<String?> = context.ttsDataStore.data.map { it[voiceNameKey] }
    val speechRate: Flow<Float> = context.ttsDataStore.data.map { it[rateKey] ?: TTS_DEFAULT_RATE }
    val pitch: Flow<Float> = context.ttsDataStore.data.map { it[pitchKey] ?: TTS_DEFAULT_PITCH }

    val snapshot: Flow<TtsPrefsSnapshot> = context.ttsDataStore.data.map { prefs ->
        TtsPrefsSnapshot(
            isEnabled = prefs[isEnabledKey] ?: false,
            voiceName = prefs[voiceNameKey],
            speechRate = prefs[rateKey] ?: TTS_DEFAULT_RATE,
            pitch = prefs[pitchKey] ?: TTS_DEFAULT_PITCH
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.ttsDataStore.edit { it[isEnabledKey] = enabled }
    }

    suspend fun setVoiceName(name: String?) {
        context.ttsDataStore.edit {
            if (name.isNullOrBlank()) it.remove(voiceNameKey) else it[voiceNameKey] = name
        }
    }

    suspend fun setSpeechRate(rate: Float) {
        context.ttsDataStore.edit { it[rateKey] = rate }
    }

    suspend fun setPitch(pitch: Float) {
        context.ttsDataStore.edit { it[pitchKey] = pitch }
    }
}
