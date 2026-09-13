package com.example.jarvis.data

import android.content.Context
import com.example.jarvis.utils.Constants

class PreferencesManager(
    context: Context
) {

    private val preferences =
        context.getSharedPreferences(
            Constants.PREFS_NAME,
            Context.MODE_PRIVATE
        )

    companion object {
        private const val KEY_VOICE_WAKE_ENABLED =
            "voice_wake_enabled"
    }

    /*
     * Voice Wake Mode
     *
     * true  = background wake mode enabled
     * false = background wake mode disabled
     */
    fun setVoiceWakeEnabled(
        enabled: Boolean
    ) {
        preferences
            .edit()
            .putBoolean(
                KEY_VOICE_WAKE_ENABLED,
                enabled
            )
            .apply()
    }

    fun isVoiceWakeEnabled(): Boolean {
        return preferences.getBoolean(
            KEY_VOICE_WAKE_ENABLED,
            false
        )
    }
}