package com.example.jarvis.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jarvis.ai.ConversationManager
import com.example.jarvis.ai.DeepSeekBridge
import com.example.jarvis.ai.JarvisCommand
import com.example.jarvis.ai.JarvisCommandParser
import com.example.jarvis.automation.CommandExecutor
import com.example.jarvis.models.Message
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel : ViewModel() {

    companion object {
        private const val TAG = "JARVIS_VM"
    }

    // =========================================================
    // CONVERSATION
    // =========================================================

    private val conversationManager =
        ConversationManager()

    // =========================================================
    // COMMAND PARSER
    // =========================================================

    private val commandParser =
        JarvisCommandParser()

    // =========================================================
    // DEEPSEEK BRIDGE
    // =========================================================

    private var deepSeekBridge: DeepSeekBridge? = null

    // =========================================================
    // UI STATE
    // =========================================================

    private val _ui =
        MutableStateFlow(
            UiState()
        )

    val ui: StateFlow<UiState> =
        _ui.asStateFlow()

    // =========================================================
    // COMMAND EXECUTOR
    // =========================================================

    private var commandExecutor: CommandExecutor? = null

    // =========================================================
    // RESPONSE LISTENER
    // =========================================================

    private var responseListener:
        ((String) -> Unit)? = null

    // =========================================================
    // COROUTINE ERROR HANDLER
    // =========================================================

    private val exceptionHandler =
        CoroutineExceptionHandler { _, exception ->

            Log.e(
                TAG,
                "Critical error",
                exception
            )

            setThinking(false)

            respond(
                "Sir, system me ek error aa gayi hai."
            )
        }

    // =========================================================
    // INITIALIZE EXECUTOR
    // =========================================================

    fun initializeExecutor(
        context: Context
    ) {

        if (commandExecutor == null) {

            commandExecutor =
                CommandExecutor(
                    context.applicationContext
                )
        }

        if (deepSeekBridge == null) {

            deepSeekBridge =
                DeepSeekBridge.getInstance(
                    context.applicationContext
                )
        }
    }

    // =========================================================
    // RESPONSE LISTENER
    // =========================================================

    fun setResponseListener(
        listener: ((String) -> Unit)?
    ) {

        responseListener =
            listener
    }

    // =========================================================
    // SEND MESSAGE
    // =========================================================

    fun send(
        text: String
    ) {

        val message =
            text.trim()

        if (message.isBlank()) {
            return
        }

        // -----------------------------------------------------
        // Add user message to UI
        // -----------------------------------------------------

        addUserMessage(
            message
        )

        // -----------------------------------------------------
        // Save conversation
        // -----------------------------------------------------

        conversationManager
            .addUserMessage(
                message
            )

        setThinking(true)

        // -----------------------------------------------------
        // Local commands
        // -----------------------------------------------------

        val localCommand =
            detectLocalCommand(
                message
            )

        if (localCommand != null) {

            executeCommand(
                localCommand
            )

            return
        }

        // -----------------------------------------------------
        // DeepSeek
        // -----------------------------------------------------

        viewModelScope.launch(
            exceptionHandler
        ) {

            try {

                val bridge =
                    deepSeekBridge

                if (bridge == null) {

                    respond(
                        "DeepSeek bridge ready nahi hai."
                    )

                    setThinking(false)

                    return@launch
                }

                // -------------------------------------------------
                // Previous conversation context
                // -------------------------------------------------

                val previousContext =
                    conversationManager
                        .buildContext()

                // -------------------------------------------------
                // Build JARVIS prompt
                // -------------------------------------------------

                val prompt =
                    buildPrompt(
                        previousContext,
                        message
                    )

                // -------------------------------------------------
                // Send to DeepSeek app
                // -------------------------------------------------

                val response =
                    bridge.sendPrompt(
                        prompt
                    )

                handleDeepSeekResponse(
                    response
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "DeepSeek error",
                    e
                )

                respond(
                    "Sir, DeepSeek se response nahi mila."
                )

                setThinking(false)
            }
        }
    }

    // =========================================================
    // BUILD DEEPSEEK PROMPT
    // =========================================================

    private fun buildPrompt(
        previousContext: String,
        currentMessage: String
    ): String {

        return buildString {

            append(
                """
                You are JARVIS, an Android personal AI assistant.

                LANGUAGE:
                - Understand Hindi, Hinglish and English.
                - Reply naturally in the user's language.
                - Be concise unless detailed explanation is requested.

                CONVERSATION:
                - Use recent conversation context.
                - Understand follow-up requests.
                - Understand words like:
                  "haan", "woh", "usko", "ise", "pehle wala",
                  "continue karo", "phir se", "ab ye karo".

                IMPORTANT:
                You are the intelligence/understanding layer.
                JARVIS Android will execute commands separately.

                NORMAL CONVERSATION:
                If the user is asking a normal question,
                return normal natural text.

                ANDROID COMMANDS:
                If the user asks JARVIS to control the Android device,
                return ONLY ONE valid JSON object.

                SUPPORTED ACTIONS:

                OPEN_APP
                OPEN_URL
                WEB_SEARCH
                YOUTUBE
                INSTAGRAM
                WHATSAPP
                BACK
                HOME
                RECENTS
                SCROLL_UP
                SCROLL_DOWN
                CLICK
                TYPE
                WAIT
                AUTOMATION
                NO_ACTION
                CONVERSATION

                JSON FORMAT:

                {
                  "action": "ACTION_NAME",
                  "target": "TARGET",
                  "value": "VALUE",
                  "steps": [],
                  "requiresConfirmation": false
                }

                MULTI-STEP FORMAT:

                {
                  "action": "AUTOMATION",
                  "steps": [
                    {
                      "action": "OPEN_APP",
                      "target": "YouTube"
                    },
                    {
                      "action": "CLICK",
                      "target": "Search"
                    },
                    {
                      "action": "TYPE",
                      "value": "Iron Man"
                    }
                  ],
                  "requiresConfirmation": false
                }

                RULES:
                - Never invent unsupported actions.
                - Never invent app package names.
                - Do not put Markdown around command JSON.
                - For normal conversation, do not return JSON.
                - For an Android command, return valid JSON only.
                - Do not execute the action yourself.
                - JARVIS will execute the parsed command.

                """.trimIndent()
            )

            // -------------------------------------------------
            // Conversation context
            // -------------------------------------------------

            if (
                previousContext.isNotBlank()
            ) {

                append(
                    "\n\nRECENT CONVERSATION:\n"
                )

                append(
                    previousContext
                )
            }

            // -------------------------------------------------
            // Current request
            // -------------------------------------------------

            append(
                "\n\nCURRENT USER REQUEST:\n"
            )

            append(
                currentMessage
            )
        }
    }

    // =========================================================
    // DEEPSEEK RESPONSE
    // =========================================================

    private fun handleDeepSeekResponse(
        response: String?
    ) {

        val rawText =
            response
                ?.trim()
                .orEmpty()

        if (rawText.isBlank()) {

            respond(
                "Sir, mujhe DeepSeek se koi response nahi mila."
            )

            setThinking(false)

            return
        }

        // -----------------------------------------------------
        // Parse response
        // -----------------------------------------------------

        val command =
            try {

                commandParser.parse(
                    rawText
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Command parser error",
                    e
                )

                null
            }

        if (
            command != null &&
            command.action.uppercase() != "CONVERSATION"
        ) {

            executeCommand(
                command
            )

            return
        }

        // -----------------------------------------------------
        // Normal conversation
        // -----------------------------------------------------

        respond(
            extractConversationText(
                rawText,
                command
            )
        )

        setThinking(false)
    }

    // =========================================================
    // CONVERSATION TEXT
    // =========================================================

    private fun extractConversationText(
        rawText: String,
        command: JarvisCommand?
    ): String {

        if (
            command != null &&
            command.action.uppercase() == "CONVERSATION"
        ) {

            return command.target
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: rawText
        }

        return rawText
    }

    // =========================================================
    // EXECUTE COMMAND
    // =========================================================

    private fun executeCommand(
        command: JarvisCommand
    ) {

        val executor =
            commandExecutor

        if (executor == null) {

            respond(
                "Sir, JARVIS executor ready nahi hai."
            )

            setThinking(false)

            return
        }

        // -----------------------------------------------------
        // Confirmation
        // -----------------------------------------------------

        if (
            command.requiresConfirmation
        ) {

            respond(
                "Sir, is action ke liye confirmation required hai."
            )

            setThinking(false)

            return
        }

        viewModelScope.launch(
            exceptionHandler
        ) {

            try {

                val executed =
                    executor.execute(
                        command
                    )

                if (executed) {

                    respond(
                        commandResponse(
                            command
                        )
                    )

                } else {

                    respond(
                        "Sir, ye command execute nahi ho saki."
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Command execution error",
                    e
                )

                respond(
                    "Sir, command execute karte waqt error aayi."
                )
            }

            setThinking(false)
        }
    }

    // =========================================================
    // COMMAND RESPONSE
    // =========================================================

    private fun commandResponse(
        command: JarvisCommand
    ): String {

        return when (
            command.action
                .trim()
                .uppercase()
        ) {

            "OPEN_APP" -> {

                val app =
                    command.target
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: "app"

                "$app open kar diya."
            }

            "OPEN_URL" -> {
                "Website open kar di."
            }

            "WEB_SEARCH" -> {
                "Web par search kar diya."
            }

            "YOUTUBE" -> {

                if (
                    command.value
                        ?.isNotBlank() == true
                ) {
                    "YouTube par search kar diya."
                } else {
                    "YouTube open kar diya."
                }
            }

            "INSTAGRAM" -> {
                "Instagram open kar diya."
            }

            "WHATSAPP" -> {
                "WhatsApp open kar diya."
            }

            "BACK" -> {
                "Back kar diya."
            }

            "HOME" -> {
                "Home screen par aa gaya."
            }

            "RECENTS",
            "RECENT_APPS" -> {
                "Recent apps open kar diye."
            }

            "SCROLL_UP" -> {
                "Upar scroll kar diya."
            }

            "SCROLL_DOWN" -> {
                "Neeche scroll kar diya."
            }

            "CLICK" -> {
                "Click kar diya."
            }

            "TYPE" -> {
                "Text enter kar diya."
            }

            "WAIT" -> {
                "Theek hai."
            }

            "AUTOMATION" -> {
                "Task complete kar diya."
            }

            "NO_ACTION" -> {
                "Theek hai."
            }

            else -> {
                "Done."
            }
        }
    }

    // =========================================================
    // LOCAL COMMAND DETECTION
    // =========================================================

    private fun detectLocalCommand(
        text: String
    ): JarvisCommand? {

        val command =
            text
                .lowercase()
                .replace(",", " ")
                .replace(".", " ")
                .replace("!", " ")
                .replace("?", " ")
                .trim()

        return when {

            isOpenCommand(
                command,
                "instagram"
            ) -> {

                JarvisCommand(
                    action = "OPEN_APP",
                    target = "instagram"
                )
            }

            isOpenCommand(
                command,
                "youtube"
            ) -> {

                JarvisCommand(
                    action = "OPEN_APP",
                    target = "youtube"
                )
            }

            isOpenCommand(
                command,
                "whatsapp"
            ) -> {

                JarvisCommand(
                    action = "OPEN_APP",
                    target = "whatsapp"
                )
            }

            isOpenCommand(
                command,
                "chrome"
            ) -> {

                JarvisCommand(
                    action = "OPEN_APP",
                    target = "chrome"
                )
            }

            isOpenCommand(
                command,
                "settings"
            ) -> {

                JarvisCommand(
                    action = "OPEN_APP",
                    target = "settings"
                )
            }

            command == "back" ||
            command == "go back" ||
            command == "peeche jao" -> {

                JarvisCommand(
                    action = "BACK"
                )
            }

            command == "home" ||
            command == "home screen" ||
            command == "ghar jao" -> {

                JarvisCommand(
                    action = "HOME"
                )
            }

            command == "recent apps" ||
            command == "recents" -> {

                JarvisCommand(
                    action = "RECENTS"
                )
            }

            else -> null
        }
    }

    // =========================================================
    // OPEN COMMAND CHECK
    // =========================================================

    private fun isOpenCommand(
        command: String,
        appName: String
    ): Boolean {

        if (
            !command.contains(
                appName
            )
        ) {
            return false
        }

        return command.contains("open") ||
            command.contains("khol") ||
            command.contains("kholo") ||
            command.contains("launch") ||
            command.contains("chala") ||
            command.contains("chalao")
    }

    // =========================================================
    // RESPOND
    // =========================================================

    private fun respond(
        text: String
    ) {

        val cleanText =
            text.trim()

        if (cleanText.isBlank()) {
            return
        }

        addAssistantMessage(
            cleanText
        )

        conversationManager
            .addAssistantMessage(
                cleanText
            )

        responseListener?.invoke(
            cleanText
        )
    }

    // =========================================================
    // ADD USER MESSAGE
    // =========================================================

    private fun addUserMessage(
        text: String
    ) {

        val messages =
            _ui.value.messages
                .toMutableList()

        messages.add(
            Message(
                text = text,
                isUser = true
            )
        )

        _ui.value =
            _ui.value.copy(
                messages = messages,
                error = null
            )
    }

    // =========================================================
    // ADD ASSISTANT MESSAGE
    // =========================================================

    private fun addAssistantMessage(
        text: String
    ) {

        val messages =
            _ui.value.messages
                .toMutableList()

        messages.add(
            Message(
                text = text,
                isUser = false
            )
        )

        _ui.value =
            _ui.value.copy(
                messages = messages
            )
    }

    // =========================================================
    // THINKING STATE
    // =========================================================

    private fun setThinking(
        thinking: Boolean
    ) {

        _ui.value =
            _ui.value.copy(
                isThinking = thinking
            )
    }

    // =========================================================
    // CLEAR CONVERSATION
    // =========================================================

    fun clearConversation() {

        conversationManager.clear()

        _ui.value =
            _ui.value.copy(
                messages = emptyList(),
                error = null,
                isThinking = false
            )
    }

    // =========================================================
    // CLEANUP
    // =========================================================

    override fun onCleared() {

        responseListener = null
        deepSeekBridge = null

        conversationManager.clear()

        super.onCleared()
    }
}