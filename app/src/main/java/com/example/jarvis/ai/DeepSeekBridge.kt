package com.example.jarvis.ai

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.jarvis.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class DeepSeekBridge private constructor(
    private val context: Context
) {

    companion object {

        private const val TAG = "DeepSeekBridge"

        private const val RESPONSE_TIMEOUT_MS = 60_000L

        @Volatile
        private var instance: DeepSeekBridge? = null

        fun getInstance(
            context: Context
        ): DeepSeekBridge {

            return instance ?: synchronized(this) {

                instance ?: DeepSeekBridge(
                    context.applicationContext
                ).also {
                    instance = it
                }
            }
        }
    }

    // =========================================================
    // INTERNAL STATE
    // =========================================================

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private var pendingResponse:
        CompletableDeferred<String?>? = null

    private var lastResponse: String? = null

    private var waitingForResponse =
        AtomicBoolean(false)

    private var requestStartedAt =
        0L

    // =========================================================
    // SEND PROMPT TO DEEPSEEK
    // =========================================================

    suspend fun sendPrompt(
        prompt: String
    ): String {

        val cleanPrompt =
            prompt.trim()

        if (cleanPrompt.isBlank()) {

            return "Sir, prompt empty hai."
        }

        // -----------------------------------------------------
        // Prevent overlapping requests
        // -----------------------------------------------------

        if (
            waitingForResponse.get()
        ) {

            return "Sir, previous DeepSeek request abhi process ho rahi hai."
        }

        // -----------------------------------------------------
        // Accessibility service required
        // -----------------------------------------------------

        val service =
            JarvisAccessibilityService.instance

        if (service == null) {

            return "Sir, JARVIS Accessibility Service active nahi hai."
        }

        // -----------------------------------------------------
        // Reset response state
        // -----------------------------------------------------

        lastResponse = null

        val deferred =
            CompletableDeferred<String?>()

        pendingResponse =
            deferred

        waitingForResponse.set(true)

        requestStartedAt =
            System.currentTimeMillis()

        try {

            // -------------------------------------------------
            // Open DeepSeek
            // -------------------------------------------------

            if (
                !openDeepSeek()
            ) {

                waitingForResponse.set(false)
                pendingResponse = null

                return "Sir, DeepSeek app open nahi ho saki."
            }

            // -------------------------------------------------
            // Give DeepSeek UI time to load
            // -------------------------------------------------

            delayOnMainThread(
                700L
            )

            // -------------------------------------------------
            // Type prompt
            // -------------------------------------------------

            val typed =
                service.performJarvisAction(
                    action = "TYPE",
                    value = cleanPrompt
                )

            if (!typed) {

                waitingForResponse.set(false)
                pendingResponse = null

                return "Sir, DeepSeek ke input box me text enter nahi ho saka."
            }

            // -------------------------------------------------
            // Small delay before sending
            // -------------------------------------------------

            delayOnMainThread(
                250L
            )

            // -------------------------------------------------
            // Send message
            // -------------------------------------------------

            if (
                !service.performJarvisAction(
                    action = "SEND"
                )
            ) {

                waitingForResponse.set(false)
                pendingResponse = null

                return "Sir, DeepSeek message send nahi ho saka."
            }

            // -------------------------------------------------
            // Wait for assistant response
            // -------------------------------------------------

            val response =
                withTimeoutOrNull(
                    RESPONSE_TIMEOUT_MS
                ) {
                    deferred.await()
                }

            waitingForResponse.set(false)
            pendingResponse = null

            return response
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: "Sir, DeepSeek se response time par nahi mila."

        } catch (e: Exception) {

            Log.e(
                TAG,
                "sendPrompt failed",
                e
            )

            waitingForResponse.set(false)
            pendingResponse = null

            return "Sir, DeepSeek communication me error aa gayi."

        } finally {

            waitingForResponse.set(false)
            pendingResponse = null
        }
    }

    // =========================================================
    // OPEN DEEPSEEK
    // =========================================================

    private fun openDeepSeek(): Boolean {

        return try {

            val packageName =
                findDeepSeekPackage()

            if (packageName == null) {

                Log.e(
                    TAG,
                    "DeepSeek package not found"
                )

                return false
            }

            val launchIntent =
                context.packageManager
                    .getLaunchIntentForPackage(
                        packageName
                    )
                    ?: return false

            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )

            context.startActivity(
                launchIntent
            )

            true

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to launch DeepSeek",
                e
            )

            false
        }
    }

    // =========================================================
    // FIND DEEPSEEK PACKAGE
    // =========================================================

    private fun findDeepSeekPackage(): String? {

        val candidates =
            listOf(
                "com.deepseek.chat"
            )

        for (packageName in candidates) {

            try {

                context.packageManager
                    .getApplicationInfo(
                        packageName,
                        0
                    )

                return packageName

            } catch (_: Exception) {
                // Try next package.
            }
        }

        return null
    }

    // =========================================================
    // ACCESSIBILITY EVENT
    // =========================================================

    fun onAccessibilityEvent(
        event: android.view.accessibility.AccessibilityEvent
    ) {

        if (
            !waitingForResponse.get()
        ) {
            return
        }

        val packageName =
            event.packageName
                ?.toString()
                ?: return

        // Only process DeepSeek events.
        if (
            packageName != findDeepSeekPackage()
        ) {
            return
        }

        // -----------------------------------------------------
        // Do not capture events immediately after sending.
        // This prevents the user's own message from becoming
        // the assistant response.
        // -----------------------------------------------------

        val elapsed =
            System.currentTimeMillis() -
                requestStartedAt

        if (
            elapsed < 800L
        ) {
            return
        }

        when (
            event.eventType
        ) {

            android.view.accessibility.AccessibilityEvent
                .TYPE_WINDOW_CONTENT_CHANGED,

            android.view.accessibility.AccessibilityEvent
                .TYPE_WINDOW_STATE_CHANGED,

            android.view.accessibility.AccessibilityEvent
                .TYPE_VIEW_TEXT_CHANGED -> {

                inspectDeepSeekScreen()
            }
        }
    }

    // =========================================================
    // INSPECT DEEPSEEK SCREEN
    // =========================================================

    private fun inspectDeepSeekScreen() {

        val service =
            JarvisAccessibilityService.instance
                ?: return

        val root =
            service.rootInActiveWindow
                ?: return

        val text =
            collectVisibleText(
                root
            )

        if (
            text.isBlank()
        ) {
            return
        }

        val response =
            extractAssistantResponse(
                text
            )

        if (
            response.isNullOrBlank()
        ) {
            return
        }

        // -----------------------------------------------------
        // Ignore duplicate response.
        // -----------------------------------------------------

        if (
            response == lastResponse
        ) {
            return
        }

        lastResponse =
            response

        deliverResponse(
            response
        )
    }

    // =========================================================
    // COLLECT ACCESSIBILITY TEXT
    // =========================================================

    private fun collectVisibleText(
        node: android.view.accessibility.AccessibilityNodeInfo
    ): String {

        val result =
            StringBuilder()

        collectNodeText(
            node,
            result
        )

        return result
            .toString()
            .trim()
    }

    private fun collectNodeText(
        node: android.view.accessibility.AccessibilityNodeInfo,
        result: StringBuilder
    ) {

        if (
            node.isVisibleToUser
        ) {

            node.text
                ?.toString()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {

                    result.append(
                        it
                    )

                    result.append(
                        '\n'
                    )
                }

            node.contentDescription
                ?.toString()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {

                    result.append(
                        it
                    )

                    result.append(
                        '\n'
                    )
                }
        }

        for (
            i in 0 until node.childCount
        ) {

            val child =
                try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                }
                    ?: continue

            collectNodeText(
                child,
                result
            )
        }
    }

    // =========================================================
    // EXTRACT ASSISTANT RESPONSE
    // =========================================================

    private fun extractAssistantResponse(
        screenText: String
    ): String? {

        val lines =
            screenText
                .lines()
                .map {
                    it.trim()
                }
                .filter {
                    it.isNotBlank()
                }

        if (
            lines.isEmpty()
        ) {
            return null
        }

        /*
         * Accessibility UI differs between DeepSeek versions.
         *
         * Therefore we first look for common assistant markers.
         */

        val markers =
            listOf(
                "DeepSeek",
                "Assistant",
                "AI"
            )

        for (marker in markers) {

            val index =
                lines.indexOfLast {
                    it.equals(
                        marker,
                        ignoreCase = true
                    )
                }

            if (
                index >= 0 &&
                index + 1 < lines.size
            ) {

                val response =
                    lines
                        .subList(
                            index + 1,
                            lines.size
                        )
                        .joinToString(
                            "\n"
                        )
                        .trim()

                if (
                    isValidAssistantResponse(
                        response
                    )
                ) {

                    return response
                }
            }
        }

        /*
         * Generic fallback:
         *
         * The latest sufficiently long visible block
         * may be the assistant response.
         */

        val candidates =
            lines.filter {
                it.length >= 3
            }

        if (
            candidates.isNotEmpty()
        ) {

            val candidate =
                candidates.last()

            if (
                isValidAssistantResponse(
                    candidate
                )
            ) {

                return candidate
            }
        }

        return null
    }

    // =========================================================
    // RESPONSE VALIDATION
    // =========================================================

    private fun isValidAssistantResponse(
        text: String
    ): Boolean {

        if (
            text.isBlank()
        ) {
            return false
        }

        val lower =
            text.lowercase()

        // Ignore obvious UI elements.
        val ignored =
            listOf(
                "send",
                "copy",
                "regenerate",
                "share",
                "stop generating",
                "new chat"
            )

        if (
            ignored.any {
                lower == it
            }
        ) {
            return false
        }

        return true
    }

    // =========================================================
    // DELIVER RESPONSE
    // =========================================================

    private fun deliverResponse(
        response: String
    ) {

        val deferred =
            pendingResponse
                ?: return

        if (
            deferred.isCompleted
        ) {
            return
        }

        deferred.complete(
            response.trim()
        )
    }

    // =========================================================
    // MAIN THREAD DELAY
    // =========================================================

    private suspend fun delayOnMainThread(
        delayMillis: Long
    ) {

        kotlinx.coroutines.suspendCancellableCoroutine<Unit> {
            continuation ->

            mainHandler.postDelayed({

                if (
                    continuation.isActive
                ) {
                    continuation.resume(
                        Unit
                    ) {}
                }

            }, delayMillis)

            continuation.invokeOnCancellation {

                mainHandler.removeCallbacksAndMessages(
                    null
                )
            }
        }
    }

    // =========================================================
    // STATUS
    // =========================================================

    fun isWaitingForResponse(): Boolean {
        return waitingForResponse.get()
    }

    // =========================================================
    // CANCEL
    // =========================================================

    fun cancelCurrentRequest() {

        waitingForResponse.set(
            false
        )

        pendingResponse?.cancel()

        pendingResponse = null
        lastResponse = null
    }
}