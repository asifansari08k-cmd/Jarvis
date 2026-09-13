package com.example.jarvis.ai

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.jarvis.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class DeepSeekBridge private constructor(
    private val context: Context
) {

    companion object {

        private const val TAG = "DeepSeekBridge"

        private const val RESPONSE_TIMEOUT_MS = 60_000L

        private const val UI_LOAD_DELAY_MS = 1_200L

        private const val SEND_DELAY_MS = 400L

        private const val RESPONSE_START_DELAY_MS = 1_000L

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
    // STATE
    // =========================================================

    private val waitingForResponse =
        AtomicBoolean(false)

    @Volatile
    private var pendingResponse:
        CompletableDeferred<String?>? = null

    @Volatile
    private var lastResponse: String? = null

    @Volatile
    private var sentPrompt: String = ""

    @Volatile
    private var requestStartedAt: Long = 0L

    @Volatile
    private var responseStartedAt: Long = 0L

    // =========================================================
    // SEND PROMPT
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
        // Prevent multiple requests
        // -----------------------------------------------------

        if (
            waitingForResponse.get()
        ) {

            return "Sir, previous DeepSeek request abhi process ho rahi hai."
        }

        // -----------------------------------------------------
        // Accessibility service
        // -----------------------------------------------------

        val service =
            JarvisAccessibilityService.instance

        if (service == null) {

            return "Sir, JARVIS Accessibility Service active nahi hai."
        }

        // -----------------------------------------------------
        // Prepare state
        // -----------------------------------------------------

        val deferred =
            CompletableDeferred<String?>()

        pendingResponse =
            deferred

        waitingForResponse.set(true)

        sentPrompt =
            cleanPrompt

        lastResponse = null

        responseStartedAt = 0L

        requestStartedAt =
            System.currentTimeMillis()

        try {

            // -------------------------------------------------
            // Open DeepSeek
            // -------------------------------------------------

            if (!openDeepSeek()) {

                return finishWithError(
                    "Sir, DeepSeek app open nahi ho saki."
                )
            }

            // -------------------------------------------------
            // Wait for UI
            // -------------------------------------------------

            delay(
                UI_LOAD_DELAY_MS
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

                return finishWithError(
                    "Sir, DeepSeek ke input box me text enter nahi ho saka."
                )
            }

            // -------------------------------------------------
            // Allow UI to update
            // -------------------------------------------------

            delay(
                SEND_DELAY_MS
            )

            // -------------------------------------------------
            // Send
            // -------------------------------------------------

            val sent =
                service.performJarvisAction(
                    action = "SEND"
                )

            if (!sent) {

                return finishWithError(
                    "Sir, DeepSeek message send nahi ho saka."
                )
            }

            // -------------------------------------------------
            // Give response UI time to start
            // -------------------------------------------------

            responseStartedAt =
                System.currentTimeMillis()

            delay(
                RESPONSE_START_DELAY_MS
            )

            // -------------------------------------------------
            // Wait for response
            // -------------------------------------------------

            val response =
                withTimeoutOrNull(
                    RESPONSE_TIMEOUT_MS
                ) {
                    deferred.await()
                }

            if (
                !response.isNullOrBlank()
            ) {

                return response
                    .trim()
            }

            return finishWithError(
                "Sir, DeepSeek se response time par nahi mila."
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "DeepSeek request failed",
                e
            )

            return finishWithError(
                "Sir, DeepSeek communication me error aa gayi."
            )

        } finally {

            waitingForResponse.set(
                false
            )

            pendingResponse = null
        }
    }

    // =========================================================
    // ERROR / FINISH
    // =========================================================

    private fun finishWithError(
        message: String
    ): String {

        waitingForResponse.set(
            false
        )

        pendingResponse = null

        return message
    }

    // =========================================================
    // OPEN DEEPSEEK
    // =========================================================

    private fun openDeepSeek(): Boolean {

        return try {

            val packageName =
                findDeepSeekPackage()

            if (
                packageName == null
            ) {

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

        /*
         * This package name must match the DeepSeek Android
         * application installed on the device.
         *
         * If DeepSeek does not open, verify the package name.
         */

        val candidates =
            listOf(
                "com.deepseek.chat"
            )

        val packageManager =
            context.packageManager

        for (
            packageName in candidates
        ) {

            try {

                packageManager.getApplicationInfo(
                    packageName,
                    0
                )

                return packageName

            } catch (_: Exception) {
                // Try next candidate.
            }
        }

        return null
    }

    // =========================================================
    // ACCESSIBILITY EVENT
    // =========================================================

    fun onAccessibilityEvent(
        event: AccessibilityEvent
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

        // -----------------------------------------------------
        // Ignore non-DeepSeek applications
        // -----------------------------------------------------

        if (
            packageName != findDeepSeekPackage()
        ) {
            return
        }

        // -----------------------------------------------------
        // Ignore events before request is actually sent
        // -----------------------------------------------------

        val now =
            System.currentTimeMillis()

        if (
            responseStartedAt <= 0L
        ) {
            return
        }

        if (
            now < responseStartedAt
        ) {
            return
        }

        // -----------------------------------------------------
        // Relevant accessibility events
        // -----------------------------------------------------

        when (
            event.eventType
        ) {

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {

                inspectDeepSeekScreen()
            }
        }
    }

    // =========================================================
    // INSPECT SCREEN
    // =========================================================

    private fun inspectDeepSeekScreen() {

        if (
            !waitingForResponse.get()
        ) {
            return
        }

        val service =
            JarvisAccessibilityService.instance
                ?: return

        val root =
            service.rootInActiveWindow
                ?: return

        val response =
            extractAssistantResponse(
                root
            )
                ?: return

        if (
            response.isBlank()
        ) {
            return
        }

        // -----------------------------------------------------
        // Never return user's own prompt
        // -----------------------------------------------------

        if (
            normalize(response) ==
            normalize(sentPrompt)
        ) {
            return
        }

        // -----------------------------------------------------
        // Ignore duplicate response
        // -----------------------------------------------------

        if (
            normalize(response) ==
            normalize(lastResponse ?: "")
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
    // EXTRACT RESPONSE FROM NODE TREE
    // =========================================================

    private fun extractAssistantResponse(
        root: AccessibilityNodeInfo
    ): String? {

        val blocks =
            mutableListOf<String>()

        collectTextBlocks(
            root,
            blocks
        )

        if (
            blocks.isEmpty()
        ) {
            return null
        }

        // -----------------------------------------------------
        // Remove duplicates while preserving order
        // -----------------------------------------------------

        val uniqueBlocks =
            blocks
                .map {
                    it.trim()
                }
                .filter {
                    it.isNotBlank()
                }
                .distinct()

        if (
            uniqueBlocks.isEmpty()
        ) {
            return null
        }

        // -----------------------------------------------------
        // Find likely assistant content
        // -----------------------------------------------------

        val candidates =
            uniqueBlocks.filter {
                isPossibleAssistantText(it)
            }

        if (
            candidates.isEmpty()
        ) {
            return null
        }

        /*
         * In most chat UIs the newest assistant message is
         * located toward the end of the accessibility tree.
         *
         * We therefore prefer the latest valid block.
         */

        return candidates
            .asReversed()
            .firstOrNull()
            ?.trim()
    }

    // =========================================================
    // COLLECT TEXT BLOCKS
    // =========================================================

    private fun collectTextBlocks(
        node: AccessibilityNodeInfo,
        result: MutableList<String>
    ) {

        if (
            node.isVisibleToUser
        ) {

            val text =
                node.text
                    ?.toString()
                    ?.trim()

            if (
                !text.isNullOrBlank()
            ) {

                result.add(
                    text
                )
            }

            val description =
                node.contentDescription
                    ?.toString()
                    ?.trim()

            if (
                !description.isNullOrBlank() &&
                description != text
            ) {

                result.add(
                    description
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

            collectTextBlocks(
                child,
                result
            )
        }
    }

    // =========================================================
    // RESPONSE VALIDATION
    // =========================================================

    private fun isPossibleAssistantText(
        text: String
    ): Boolean {

        val clean =
            text.trim()

        if (
            clean.length < 3
        ) {
            return false
        }

        val lower =
            clean.lowercase()

        // -----------------------------------------------------
        // UI controls
        // -----------------------------------------------------

        val ignoredExact =
            setOf(
                "send",
                "copy",
                "share",
                "regenerate",
                "stop",
                "stop generating",
                "new chat",
                "back",
                "menu",
                "more",
                "settings"
            )

        if (
            lower in ignoredExact
        ) {
            return false
        }

        // -----------------------------------------------------
        // User prompt
        // -----------------------------------------------------

        if (
            normalize(clean) ==
            normalize(sentPrompt)
        ) {
            return false
        }

        // -----------------------------------------------------
        // Common UI labels
        // -----------------------------------------------------

        val ignoredContains =
            listOf(
                "stop generating",
                "regenerate response",
                "new conversation"
            )

        if (
            ignoredContains.any {
                lower.contains(it)
            }
        ) {
            return false
        }

        return true
    }

    // =========================================================
    // NORMALIZE TEXT
    // =========================================================

    private fun normalize(
        text: String
    ): String {

        return text
            .trim()
            .lowercase()
            .replace(
                Regex("\\s+"),
                " "
            )
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

        sentPrompt = ""

        requestStartedAt = 0L

        responseStartedAt = 0L
    }
}