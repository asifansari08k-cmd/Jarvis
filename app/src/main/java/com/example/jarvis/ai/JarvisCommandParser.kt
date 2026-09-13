package com.example.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * DeepSeek response ko JARVIS structured command me convert karta hai.
 *
 * Supported:
 * 1. Direct JSON
 * 2. Markdown JSON
 * 3. Embedded JSON
 * 4. Plain text commands
 * 5. Normal conversation
 */
class JarvisCommandParser {

    fun parse(
        rawResponse: String?
    ): JarvisCommand {

        if (rawResponse.isNullOrBlank()) {

            return JarvisCommand(
                action = "CONVERSATION",
                target = null
            )
        }

        val response =
            rawResponse.trim()

        // =====================================================
        // 1. DIRECT JSON
        // =====================================================

        parseJson(response)?.let {
            return it
        }

        // =====================================================
        // 2. MARKDOWN JSON
        // =====================================================

        extractMarkdownJson(response)?.let { json ->

            parseJson(json)?.let {
                return it
            }
        }

        // =====================================================
        // 3. EMBEDDED JSON
        // =====================================================

        extractEmbeddedJson(response)?.let { json ->

            parseJson(json)?.let {
                return it
            }
        }

        // =====================================================
        // 4. PLAIN TEXT
        // =====================================================

        return parsePlainText(
            response
        )
    }

    // =========================================================
    // JSON PARSER
    // =========================================================

    private fun parseJson(
        jsonText: String
    ): JarvisCommand? {

        return try {

            val json =
                JSONObject(
                    jsonText.trim()
                )

            val action =
                json.optString(
                    "action",
                    ""
                )
                    .trim()
                    .uppercase()

            /*
             * JSON hai lekin action missing hai.
             *
             * Aise response ko execute nahi karna.
             */
            if (action.isBlank()) {

                return null
            }

            val target =
                json.optNullableString(
                    "target"
                )

            val value =
                json.optNullableString(
                    "value"
                )

            val requiresConfirmation =
                json.optBoolean(
                    "requiresConfirmation",
                    false
                )

            val steps =
                parseSteps(
                    json.optJSONArray(
                        "steps"
                    )
                )

            JarvisCommand(
                action = normalizeAction(
                    action
                ),
                target = target,
                value = value,
                steps = steps,
                requiresConfirmation =
                    requiresConfirmation
            )

        } catch (_: Exception) {

            null
        }
    }

    // =========================================================
    // NORMALIZE ACTION
    // =========================================================

    private fun normalizeAction(
        action: String
    ): String {

        return when (
            action.trim().uppercase()
        ) {

            "OPEN",
            "LAUNCH",
            "START" ->
                "OPEN_APP"

            "SEARCH",
            "SEARCH_WEB",
            "GOOGLE_SEARCH" ->
                "WEB_SEARCH"

            "OPEN_YT",
            "OPEN_YOUTUBE" ->
                "YOUTUBE"

            "OPEN_INSTAGRAM" ->
                "INSTAGRAM"

            "OPEN_WHATSAPP" ->
                "WHATSAPP"

            "RECENT",
            "RECENTS",
            "RECENT_APPS" ->
                "RECENTS"

            "SCROLLUP",
            "UP" ->
                "SCROLL_UP"

            "SCROLLDOWN",
            "DOWN" ->
                "SCROLL_DOWN"

            "TALK",
            "SPEAK",
            "ANSWER" ->
                "CONVERSATION"

            else ->
                action.trim().uppercase()
        }
    }

    // =========================================================
    // STEPS
    // =========================================================

    private fun parseSteps(
        stepsArray: JSONArray?
    ): List<JarvisStep> {

        if (
            stepsArray == null ||
            stepsArray.length() == 0
        ) {
            return emptyList()
        }

        val steps =
            mutableListOf<JarvisStep>()

        for (
            i in 0 until stepsArray.length()
        ) {

            val stepObject =
                try {

                    stepsArray.optJSONObject(
                        i
                    )

                } catch (_: Exception) {

                    null
                }
                    ?: continue

            val rawAction =
                stepObject
                    .optString(
                        "action",
                        ""
                    )
                    .trim()

            if (
                rawAction.isBlank()
            ) {
                continue
            }

            val action =
                normalizeAction(
                    rawAction
                )

            val target =
                stepObject.optNullableString(
                    "target"
                )

            val value =
                stepObject.optNullableString(
                    "value"
                )

            val requiresConfirmation =
                stepObject.optBoolean(
                    "requiresConfirmation",
                    false
                )

            steps.add(
                JarvisStep(
                    action = action,
                    target = target,
                    value = value,
                    requiresConfirmation =
                        requiresConfirmation
                )
            )
        }

        return steps
    }

    // =========================================================
    // MARKDOWN JSON
    // =========================================================

    private fun extractMarkdownJson(
        text: String
    ): String? {

        /*
         * Supports:
         *
         * ```json
         * {...}
         * ```
         *
         * and:
         *
         * ```
         * {...}
         * ```
         */

        val regex =
            Regex(
                pattern =
                    """```(?:json)?\s*([\s\S]*?)\s*```""",
                options =
                    setOf(
                        RegexOption.IGNORE_CASE
                    )
            )

        val matches =
            regex.findAll(
                text
            )

        for (
            match in matches
        ) {

            val content =
                match.groupValues
                    .getOrNull(1)
                    ?.trim()
                    ?: continue

            if (
                content.startsWith("{") &&
                content.endsWith("}")
            ) {

                return content
            }
        }

        return null
    }

    // =========================================================
    // EMBEDDED JSON
    // =========================================================

    private fun extractEmbeddedJson(
        text: String
    ): String? {

        var startIndex = -1

        for (
            i in text.indices
        ) {

            if (
                text[i] == '{'
            ) {

                startIndex = i
                break
            }
        }

        if (
            startIndex < 0
        ) {
            return null
        }

        var depth = 0

        var insideString = false

        var escaped = false

        for (
            i in startIndex until text.length
        ) {

            val char =
                text[i]

            if (
                escaped
            ) {

                escaped = false
                continue
            }

            if (
                char == '\\' &&
                insideString
            ) {

                escaped = true
                continue
            }

            if (
                char == '"'
            ) {

                insideString =
                    !insideString

                continue
            }

            if (
                insideString
            ) {
                continue
            }

            when (char) {

                '{' -> {
                    depth++
                }

                '}' -> {

                    depth--

                    if (
                        depth == 0
                    ) {

                        return text.substring(
                            startIndex,
                            i + 1
                        ).trim()
                    }
                }
            }
        }

        return null
    }

    // =========================================================
    // PLAIN TEXT PARSER
    // =========================================================

    private fun parsePlainText(
        text: String
    ): JarvisCommand {

        val clean =
            text.trim()

        val upper =
            clean.uppercase()

        // =====================================================
        // OPEN / LAUNCH APP
        // =====================================================

        val openPrefixes =
            listOf(
                "OPEN ",
                "LAUNCH ",
                "START "
            )

        for (
            prefix in openPrefixes
        ) {

            if (
                upper.startsWith(prefix)
            ) {

                val target =
                    clean.substring(
                        prefix.length
                    ).trim()

                if (
                    target.isNotBlank()
                ) {

                    return JarvisCommand(
                        action = "OPEN_APP",
                        target = target
                    )
                }
            }
        }

        // =====================================================
        // SEARCH
        // =====================================================

        val searchPrefixes =
            listOf(
                "SEARCH FOR ",
                "SEARCH ",
                "GOOGLE "
            )

        for (
            prefix in searchPrefixes
        ) {

            if (
                upper.startsWith(prefix)
            ) {

                val query =
                    clean.substring(
                        prefix.length
                    ).trim()

                if (
                    query.isNotBlank()
                ) {

                    return JarvisCommand(
                        action = "WEB_SEARCH",
                        value = query
                    )
                }
            }
        }

        // =====================================================
        // BACK
        // =====================================================

        if (
            upper == "GO BACK" ||
            upper == "BACK"
        ) {

            return JarvisCommand(
                action = "BACK"
            )
        }

        // =====================================================
        // HOME
        // =====================================================

        if (
            upper == "GO HOME" ||
            upper == "HOME"
        ) {

            return JarvisCommand(
                action = "HOME"
            )
        }

        // =====================================================
        // RECENTS
        // =====================================================

        if (
            upper == "RECENTS" ||
            upper == "RECENT APPS" ||
            upper == "OPEN RECENTS"
        ) {

            return JarvisCommand(
                action = "RECENTS"
            )
        }

        // =====================================================
        // SCROLL
        // =====================================================

        if (
            upper == "SCROLL UP"
        ) {

            return JarvisCommand(
                action = "SCROLL_UP"
            )
        }

        if (
            upper == "SCROLL DOWN"
        ) {

            return JarvisCommand(
                action = "SCROLL_DOWN"
            )
        }

        // =====================================================
        // CLICK
        // =====================================================

        if (
            upper.startsWith("CLICK ")
        ) {

            val target =
                clean.substring(
                    6
                ).trim()

            if (
                target.isNotBlank()
            ) {

                return JarvisCommand(
                    action = "CLICK",
                    target = target
                )
            }
        }

        // =====================================================
        // TYPE
        // =====================================================

        if (
            upper.startsWith("TYPE ")
        ) {

            val value =
                clean.substring(
                    5
                ).trim()

            if (
                value.isNotBlank()
            ) {

                return JarvisCommand(
                    action = "TYPE",
                    value = value
                )
            }
        }

        // =====================================================
        // SEND
        // =====================================================

        if (
            upper == "SEND" ||
            upper == "SEND MESSAGE"
        ) {

            return JarvisCommand(
                action = "SEND"
            )
        }

        // =====================================================
        // NORMAL CONVERSATION
        // =====================================================

        return JarvisCommand(
            action = "CONVERSATION",
            target = clean
        )
    }

    // =========================================================
    // NULLABLE JSON STRING
    // =========================================================

    private fun JSONObject.optNullableString(
        key: String
    ): String? {

        if (
            !has(key) ||
            isNull(key)
        ) {
            return null
        }

        return try {

            optString(
                key
            )
                .trim()
                .takeIf {
                    it.isNotEmpty()
                }

        } catch (_: Exception) {

            null
        }
    }
}