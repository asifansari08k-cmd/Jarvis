package com.example.jarvis.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ============================================================================
 * J.A.R.V.I.S. DEEPSEEK PROVIDER
 * ============================================================================
 *
 * DeepSeek is used through the installed DeepSeek Android application.
 *
 * Flow:
 * Voice/Text
 *    ↓
 * AIProviderManager
 *    ↓
 * DeepSeekBridge
 *    ↓
 * DeepSeek App
 *    ↓
 * AccessibilityService
 *    ↓
 * DeepSeek Response
 *    ↓
 * JARVIS
 *
 * No Gemini / Grok / ChatGPT API is used here.
 * No API key is required.
 * ============================================================================
 */
class AIProviderManager(
    private val context: Context
) {

    companion object {
        private const val TAG = "AIProviderManager"
    }

    /**
     * Only one AI provider is used now.
     */
    enum class AIModelType {
        DEEPSEEK
    }

    private var currentModel = AIModelType.DEEPSEEK

    /**
     * Set active provider.
     *
     * Kept for compatibility with existing JARVIS code.
     */
    fun setActiveModel(model: AIModelType) {
        currentModel = model
    }

    /**
     * Returns the active AI name.
     */
    fun getActiveModelName(): String {
        return when (currentModel) {
            AIModelType.DEEPSEEK -> "DeepSeek"
        }
    }

    /**
     * Sends a prompt to the installed DeepSeek application.
     *
     * apiKey is intentionally ignored.
     *
     * Kept as an optional parameter temporarily so existing callers
     * do not immediately break. Once MainViewModel/MainActivity are
     * migrated, this parameter can be removed completely.
     */
    suspend fun queryActiveAI(
        prompt: String,
        apiKey: String = ""
    ): String {
        return withContext(Dispatchers.Main.immediate) {

            if (prompt.isBlank()) {
                return@withContext "Sir, I didn't receive a valid command."
            }

            try {
                val bridge = DeepSeekBridge.getInstance(context)

                bridge.sendPrompt(prompt)

            } catch (e: Exception) {
                android.util.Log.e(
                    TAG,
                    "DeepSeek bridge error",
                    e
                )

                "Sir, I couldn't communicate with the DeepSeek application."
            }
        }
    }
}