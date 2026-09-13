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

/**
 * DeepSeek Bridge
 *
 * DeepSeek = JARVIS ka AI brain
 * AccessibilityService = DeepSeek UI control + response reader
 *
 * Flow:
 *
 * JARVIS
 *   ↓
 * DeepSeekBridge
 *   ↓
 * Open DeepSeek
 *   ↓
 * TYPE prompt
 *   ↓
 * SEND
 *   ↓
 * DeepSeek response
 *   ↓
 * AccessibilityEvent
 *   ↓
 * Read response
 *   ↓
 * MainViewModel
 *   ↓
 * JarvisCommandParser
 */
class DeepSeekBridge private constructor(
    private val context: Context
) {

    companion object {

        private const val TAG = "DeepSeekBridge"

        private const val RESPONSE_TIMEOUT_MS = 60_000L

        private const val UI_LOAD_DELAY_MS = 1_500L

        private const val SEND_DELAY_MS = 500L

        private const val RESPONSE_START_DELAY_MS = 1_200L

        /**
         * Streaming response ko immediately return karne ke bajay
         * thoda stable hone ka wait.
         */
        private const val RESPONSE_STABLE_MS = 900L

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

    /**
     * User ne jo prompt bheja tha.
     */
    @Volatile
    private var sentPrompt: String = ""

    /**
     * SEND se pehle screen ka snapshot.
     *
     * Isse old DeepSeek response ko new response samajhne
     * ka chance kam hota hai.
     */
    @Volatile
    private var beforeSendSnapshot: String = ""

    /**
     * Latest detected assistant response.
     */
    @Volatile
    private var lastCandidateResponse: String = ""

    /**
     * Last response snapshot.
     */
    @Volatile
    private var lastScreenSnapshot: String = ""

    /**
     * Response kab start hua.
     */
    @Volatile
    private var responseStartedAt: Long = 0L

    /**
     * Latest candidate kab mila.
     */
    @Volatile
    private var candidateChangedAt: Long = 0L

    /**
     * Request start time.
     */
    @Volatile
    private var requestStartedAt: Long = 0L

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
        // Prevent duplicate requests
        // -----------------------------------------------------

        if (
            waitingForResponse.get()
        ) {

            return "Sir, previous DeepSeek request abhi process ho rahi hai."
        }

        // -----------------------------------------------------
        // Accessibility service check
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

        beforeSendSnapshot = ""

        lastCandidateResponse = ""

        lastScreenSnapshot = ""

        responseStartedAt = 0L

        candidateChangedAt = 0L

        requestStartedAt =
            System.currentTimeMillis()

        try {

            // =================================================
            // OPEN DEEPSEEK
            // =================================================

            if (!openDeepSeek()) {

                return finishWithError(
                    "Sir, DeepSeek app open nahi ho saki."
                )
            }

            // =================================================
            // WAIT FOR DEEPSEEK UI
            // =================================================

            delay(
                UI_LOAD_DELAY_MS
            )

            // =================================================
            // CAPTURE SCREEN BEFORE TYPING
            // =================================================

            beforeSendSnapshot =
                getCurrentScreenText()

            // =================================================
            // TYPE PROMPT
            // =================================================

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

            // =================================================
            // WAIT FOR INPUT UI
            // =================================================

            delay(
                SEND_DELAY_MS
            )

            // =================================================
            // SEND MESSAGE
            // =================================================

            val sent =
                service.performJarvisAction(
                    action = "SEND"
                )

            if (!sent) {

                return finishWithError(
                    "Sir, DeepSeek message send nahi ho saka."
                )
            }

            // =================================================
            // RESPONSE MODE START
            // =================================================

            responseStartedAt =
                System.currentTimeMillis()

            candidateChangedAt = 0L

            lastCandidateResponse = ""

            // =================================================
            // GIVE DEEPSEEK TIME TO START GENERATING
            // =================================================

            delay(
                RESPONSE_START_DELAY_MS
            )

            // =================================================
            // INITIAL RESPONSE CHECK
            // =================================================

            inspectCurrentScreen()

            // =================================================
            // WAIT FOR RESPONSE
            // =================================================

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

            waitingForResponse.set(false)

            pendingResponse = null

            candidateChangedAt = 0L
        }
    }

    // =========================================================
    // ERROR / FINISH
    // =========================================================

    private fun finishWithError(
        message: String
    ): String {

        waitingForResponse.set(false)

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

        /**
         * Official DeepSeek Android package commonly used
         * by the Android application.
         *
         * If the installed application uses another package,
         * this list can be extended later.
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
        // Only DeepSeek events
        // -----------------------------------------------------

        if (
            packageName != findDeepSeekPackage()
        ) {
            return
        }

        // -----------------------------------------------------
        // Response mode not started yet
        // -----------------------------------------------------

        if (
            responseStartedAt <= 0L
        ) {
            return
        }

        val now =
            System.currentTimeMillis()

        if (
            now < responseStartedAt
        ) {
            return
        }

        // -----------------------------------------------------
        // Relevant events only
        // -----------------------------------------------------

        when (
            event.eventType
        ) {

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {

                inspectCurrentScreen()
            }
        }
    }

    // =========================================================
    // INSPECT CURRENT SCREEN
    // =========================================================

    private fun inspectCurrentScreen() {

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

        val screenText =
            collectScreenText(
                root
            )

        if (
            screenText.isBlank()
        ) {
            return
        }

        // -----------------------------------------------------
        // Ignore identical screen updates
        // -----------------------------------------------------

        val normalizedScreen =
            normalize(
                screenText
            )

        if (
            normalizedScreen ==
            normalize(lastScreenSnapshot)
        ) {
            /*
             * Same screen.
             *
             * But if a candidate is already present, check
             * whether it has become stable.
             */
            checkCandidateStability()
            return
        }

        lastScreenSnapshot =
            screenText

        // -----------------------------------------------------
        // Extract possible assistant response
        // -----------------------------------------------------

        val candidate =
            extractAssistantResponse(
                root
            )
                ?: return

        val cleanCandidate =
            candidate.trim()

        if (
            cleanCandidate.isBlank()
        ) {
            return
        }

        // -----------------------------------------------------
        // Never accept user's prompt
        // -----------------------------------------------------

        if (
            normalize(cleanCandidate) ==
            normalize(sentPrompt)
        ) {
            return
        }

        // -----------------------------------------------------
        // Must be newer than pre-send screen
        // -----------------------------------------------------

        if (
            isAlreadyPresentBeforeSend(
                cleanCandidate
            )
        ) {
            return
        }

        // -----------------------------------------------------
        // Validate
        // -----------------------------------------------------

        if (
            !isPossibleAssistantText(
                cleanCandidate
            )
        ) {
            return
        }

        // -----------------------------------------------------
        // New / changed response
        // -----------------------------------------------------

        if (
            normalize(cleanCandidate) !=
            normalize(lastCandidateResponse)
        ) {

            lastCandidateResponse =
                cleanCandidate

            candidateChangedAt =
                System.currentTimeMillis()
        }

        // -----------------------------------------------------
        // Check stability
        // -----------------------------------------------------

        checkCandidateStability()
    }

    // =========================================================
    // CHECK RESPONSE STABILITY
    // =========================================================

    private fun checkCandidateStability() {

        if (
            lastCandidateResponse.isBlank()
        ) {
            return
        }

        if (
            candidateChangedAt <= 0L
        ) {
            return
        }

        val elapsed =
            System.currentTimeMillis() -
                candidateChangedAt

        if (
            elapsed >= RESPONSE_STABLE_MS
        ) {

            deliverResponse(
                lastCandidateResponse
            )
        }
    }

    // =========================================================
    // EXTRACT ASSISTANT RESPONSE
    // =========================================================

    private fun extractAssistantResponse(
        root: AccessibilityNodeInfo
    ): String? {

        val blocks =
            mutableListOf<String>()

        collectTextBlocks(
            node = root,
            result = blocks
        )

        if (
            blocks.isEmpty()
        ) {
            return null
        }

        // -----------------------------------------------------
        // Clean + unique
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
        // Remove obvious user prompt
        // -----------------------------------------------------

        val possibleBlocks =
            uniqueBlocks.filter {

                normalize(it) !=
                    normalize(sentPrompt)
            }

        if (
            possibleBlocks.isEmpty()
        ) {
            return null
        }

        // -----------------------------------------------------
        // Prefer JSON-looking responses
        // -----------------------------------------------------

        val jsonCandidate =
            possibleBlocks
                .asReversed()
                .firstOrNull {
                    looksLikeJson(it)
                }

        if (
            !jsonCandidate.isNullOrBlank()
        ) {

            return jsonCandidate
        }

        // -----------------------------------------------------
        // Prefer blocks containing command fields
        // -----------------------------------------------------

        val commandCandidate =
            possibleBlocks
                .asReversed()
                .firstOrNull {

                    val lower =
                        it.lowercase()

                    lower.contains(
                        "\"action\""
                    ) ||
                    lower.contains(
                        "\"target\""
                    ) ||
                    lower.contains(
                        "\"steps\""
                    )
                }

        if (
            !commandCandidate.isNullOrBlank()
        ) {

            return commandCandidate
        }

        // -----------------------------------------------------
        // Remove UI controls
        // -----------------------------------------------------

        val validCandidates =
            possibleBlocks.filter {
                isPossibleAssistantText(it)
            }

        if (
            validCandidates.isEmpty()
        ) {
            return null
        }

        // -----------------------------------------------------
        // Newest valid block
        // -----------------------------------------------------

        return validCandidates
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
                node = child,
                result = result
            )
        }
    }

    // =========================================================
    // COLLECT FULL SCREEN TEXT
    // =========================================================

    private fun collectScreenText(
        root: AccessibilityNodeInfo
    ): String {

        val blocks =
            mutableListOf<String>()

        collectTextBlocks(
            node = root,
            result = blocks
        )

        return blocks
            .map {
                it.trim()
            }
            .filter {
                it.isNotBlank()
            }
            .distinct()
            .joinToString(
                separator = "\n"
            )
    }

    // =========================================================
    // ALREADY PRESENT BEFORE SEND
    // =========================================================

    private fun isAlreadyPresentBeforeSend(
        candidate: String
    ): Boolean {

        if (
            beforeSendSnapshot.isBlank()
        ) {
            return false
        }

        val candidateNormalized =
            normalize(candidate)

        val oldNormalized =
            normalize(beforeSendSnapshot)

        /*
         * Exact old-screen match.
         */
        if (
            oldNormalized.contains(
                candidateNormalized
            )
        ) {
            return true
        }

        return false
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
        // Exact UI controls
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
                "new conversation",

                "back",
                "menu",
                "more",
                "settings",

                "edit",
                "delete",

                "retry",
                "cancel"
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
        // Common UI text
        // -----------------------------------------------------

        val ignoredContains =
            listOf(

                "stop generating",
                "regenerate response",
                "new conversation",

                "start a new chat",
                "deepseek chat",

                "what can i help you with"
            )

        if (
            ignoredContains.any {
                lower == it ||
                    lower.contains(it)
            }
        ) {
            return false
        }

        return true
    }

    // =========================================================
    // JSON DETECTION
    // =========================================================

    private fun looksLikeJson(
        text: String
    ): Boolean {

        val clean =
            text.trim()

        if (
            clean.length < 2
        ) {
            return false
        }

        if (
            clean.startsWith("{") &&
            clean.endsWith("}")
        ) {
            return true
        }

        if (
            clean.startsWith("[") &&
            clean.endsWith("]")
        ) {
            return true
        }

        return false
    }

    // =========================================================
    // NORMALIZE
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

        val cleanResponse =
            response.trim()

        if (
            cleanResponse.isBlank()
        ) {
            return
        }

        val deferred =
            pendingResponse
                ?: return

        if (
            deferred.isCompleted
        ) {
            return
        }

        deferred.complete(
            cleanResponse
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

        waitingForResponse.set(false)

        pendingResponse?.cancel()

        pendingResponse = null

        sentPrompt = ""

        beforeSendSnapshot = ""

        lastCandidateResponse = ""

        lastScreenSnapshot = ""

        requestStartedAt = 0L

        responseStartedAt = 0L

        candidateChangedAt = 0L
    }
}