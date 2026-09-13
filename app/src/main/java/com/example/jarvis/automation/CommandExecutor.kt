package com.example.jarvis.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

import com.example.jarvis.accessibility.JarvisAccessibilityService
import com.example.jarvis.ai.JarvisCommand
import com.example.jarvis.ai.JarvisStep

/**
 * JARVIS structured commands ko Android actions me convert karta hai.
 *
 * DeepSeek = Brain
 * CommandExecutor = Action Layer
 * AccessibilityService = Phone UI Control
 */
class CommandExecutor(
    private val context: Context
) {

    fun execute(command: JarvisCommand): Boolean {

        // Confirmation-required command
        if (command.requiresConfirmation) {
            showConfirmationRequired(command)
            return false
        }

        // Multi-step command
        if (command.steps.isNotEmpty()) {

            for (step in command.steps) {

                if (step.requiresConfirmation) {

                    showConfirmationRequired(
                        JarvisCommand(
                            action = step.action,
                            target = step.target,
                            value = step.value,
                            steps = emptyList(),
                            requiresConfirmation = true
                        )
                    )

                    return false
                }

                if (!executeStep(step)) {
                    return false
                }
            }

            return true
        }

        return executeAction(
            action = command.action,
            target = command.target,
            value = command.value
        )
    }

    private fun executeStep(
        step: JarvisStep
    ): Boolean {

        return executeAction(
            action = step.action,
            target = step.target,
            value = step.value
        )
    }

    private fun executeAction(
        action: String,
        target: String?,
        value: String?
    ): Boolean {

        val normalizedAction = action
            .trim()
            .uppercase()

        return when (normalizedAction) {

            // =================================================
            // APP
            // =================================================

            "OPEN_APP",
            "LAUNCH_APP" -> {

                openApp(
                    target = target ?: value
                )
            }

            // =================================================
            // URL
            // =================================================

            "OPEN_URL" -> {

                openUrl(
                    value ?: target ?: ""
                )
            }

            // =================================================
            // WEB SEARCH
            // =================================================

            "WEB_SEARCH",
            "SEARCH" -> {

                val query = value ?: target ?: ""

                if (query.isBlank()) {
                    false
                } else {

                    openUrl(
                        "https://www.google.com/search?q=" +
                            Uri.encode(query)
                    )
                }
            }

            // =================================================
            // YOUTUBE
            // =================================================

            "YOUTUBE" -> {

                if (value.isNullOrBlank()) {

                    openUrl(
                        "https://www.youtube.com"
                    )

                } else {

                    openUrl(
                        "https://www.youtube.com/results?search_query=" +
                            Uri.encode(value)
                    )
                }
            }

            // =================================================
            // INSTAGRAM
            // =================================================

            "INSTAGRAM" -> {

                openInstagram()
            }

            // =================================================
            // WHATSAPP
            // =================================================

            "WHATSAPP" -> {

                openWhatsApp()
            }

            // =================================================
            // DEEPSEEK SEND
            // =================================================

            "SEND" -> {

                performAccessibilityAction(
                    action = "SEND"
                )
            }

            // =================================================
            // SYSTEM NAVIGATION
            // =================================================

            "BACK" -> {

                performAccessibilityAction(
                    action = "BACK"
                )
            }

            "HOME" -> {

                performAccessibilityAction(
                    action = "HOME"
                )
            }

            "RECENTS",
            "RECENT_APPS" -> {

                performAccessibilityAction(
                    action = "RECENTS"
                )
            }

            // =================================================
            // SCROLL
            // =================================================

            "SCROLL_UP" -> {

                performAccessibilityAction(
                    action = "SCROLL_UP"
                )
            }

            "SCROLL_DOWN" -> {

                performAccessibilityAction(
                    action = "SCROLL_DOWN"
                )
            }

            // =================================================
            // CLICK
            // =================================================

            "CLICK" -> {

                performAccessibilityAction(
                    action = "CLICK",
                    target = target,
                    value = value
                )
            }

            // =================================================
            // TYPE
            // =================================================

            "TYPE" -> {

                performAccessibilityAction(
                    action = "TYPE",
                    target = target,
                    value = value
                )
            }

            // =================================================
            // WAIT
            // =================================================

            "WAIT" -> {

                /*
                 * Actual delay future coroutine automation
                 * layer me handle kiya ja sakta hai.
                 */
                true
            }

            // =================================================
            // NO ACTION
            // =================================================

            "NO_ACTION" -> {

                true
            }

            // =================================================
            // CONVERSATION
            // =================================================

            "CONVERSATION",
            "CHAT" -> {

                // Conversation ko phone action ki zarurat nahi.
                true
            }

            // =================================================
            // AUTOMATION
            // =================================================

            "AUTOMATION" -> {

                // Steps execute() me already handle ho chuke hain.
                true
            }

            // =================================================
            // UNKNOWN
            // =================================================

            else -> {

                Toast.makeText(
                    context,
                    "JARVIS: Action not supported: $action",
                    Toast.LENGTH_SHORT
                ).show()

                false
            }
        }
    }

    // =========================================================
    // OPEN APP
    // =========================================================

    private fun openApp(
        target: String?
    ): Boolean {

        val appName = target
            ?.trim()
            ?.lowercase()
            ?: return false

        val packageName = when {

            appName == "instagram" ||
                appName.contains("instagram") ->
                "com.instagram.android"

            appName == "youtube" ||
                appName.contains("youtube") ->
                "com.google.android.youtube"

            appName == "whatsapp" ||
                appName.contains("whatsapp") ->
                "com.whatsapp"

            appName == "chrome" ||
                appName.contains("chrome") ->
                "com.android.chrome"

            appName == "google" ->
                "com.google.android.googlequicksearchbox"

            appName == "settings" ||
                appName.contains("settings") ->
                "com.android.settings"

            appName == "deepseek" ->
                findDeepSeekPackage()

            else ->
                null
        }

        if (packageName == null) {

            Toast.makeText(
                context,
                "JARVIS: App not supported: $target",
                Toast.LENGTH_SHORT
            ).show()

            return false
        }

        return try {

            val intent =
                context.packageManager
                    .getLaunchIntentForPackage(packageName)

            if (intent == null) {

                Toast.makeText(
                    context,
                    "App installed nahi hai: $target",
                    Toast.LENGTH_SHORT
                ).show()

                false

            } else {

                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )

                context.startActivity(intent)

                true
            }

        } catch (_: Exception) {

            Toast.makeText(
                context,
                "App open nahi ho saka: $target",
                Toast.LENGTH_SHORT
            ).show()

            false
        }
    }

    // =========================================================
    // DEEPSEEK PACKAGE
    // =========================================================

    private fun findDeepSeekPackage(): String? {

        val candidates = listOf(
            "com.deepseek.chat"
        )

        for (packageName in candidates) {

            try {

                context.packageManager
                    .getPackageInfo(packageName, 0)

                return packageName

            } catch (_: Exception) {
                // Try next package
            }
        }

        return null
    }

    // =========================================================
    // INSTAGRAM
    // =========================================================

    private fun openInstagram(): Boolean {

        return try {

            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "instagram://user?username=drakoxnaeem"
                )
            )

            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )

            context.startActivity(intent)

            true

        } catch (_: Exception) {

            openUrl(
                "https://www.instagram.com/"
            )
        }
    }

    // =========================================================
    // WHATSAPP
    // =========================================================

    private fun openWhatsApp(): Boolean {

        return try {

            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("whatsapp://")
            )

            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )

            context.startActivity(intent)

            true

        } catch (_: Exception) {

            openUrl(
                "https://web.whatsapp.com"
            )
        }
    }

    // =========================================================
    // OPEN URL
    // =========================================================

    private fun openUrl(
        url: String
    ): Boolean {

        val cleanUrl = url.trim()

        if (cleanUrl.isBlank()) {
            return false
        }

        return try {

            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(cleanUrl)
            )

            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )

            context.startActivity(intent)

            true

        } catch (_: Exception) {

            Toast.makeText(
                context,
                "Link open nahi ho saka",
                Toast.LENGTH_SHORT
            ).show()

            false
        }
    }

    // =========================================================
    // ACCESSIBILITY BRIDGE
    // =========================================================

    private fun performAccessibilityAction(
        action: String,
        target: String? = null,
        value: String? = null
    ): Boolean {

        val service =
            JarvisAccessibilityService.instance

        if (service == null) {

            Toast.makeText(
                context,
                "JARVIS Accessibility Service enabled nahi hai",
                Toast.LENGTH_SHORT
            ).show()

            return false
        }

        return try {

            service.performJarvisAction(
                action = action,
                target = target,
                value = value
            )

        } catch (_: Exception) {

            Toast.makeText(
                context,
                "Accessibility action failed",
                Toast.LENGTH_SHORT
            ).show()

            false
        }
    }

    // =========================================================
    // CONFIRMATION
    // =========================================================

    private fun showConfirmationRequired(
        command: JarvisCommand
    ) {

        Toast.makeText(
            context,
            "Confirmation required: ${command.action}",
            Toast.LENGTH_LONG
        ).show()
    }
}