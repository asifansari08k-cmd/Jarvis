package com.example.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject

class JarvisCommandParser {

    fun parse(rawResponse: String?): JarvisCommand {

        if (rawResponse.isNullOrBlank()) {
            return JarvisCommand(
                action = "CONVERSATION",
                target = null
            )
        }

        val response = rawResponse.trim()

        // ---------------------------------------------------------
        // 1. Direct JSON
        // ---------------------------------------------------------
        parseJson(response)?.let {
            return it
        }

        // ---------------------------------------------------------
        // 2. JSON inside Markdown code block
        // Example:
        // ```json
        // {"action":"OPEN_APP","target":"YouTube"}
        // ```
        // ---------------------------------------------------------
        val markdownJson = extractMarkdownJson(response)

        if (markdownJson != null) {
            parseJson(markdownJson)?.let {
                return it
            }
        }

        // ---------------------------------------------------------
        // 3. JSON embedded inside normal text
        // Example:
        // "Sure sir, execute this:
        // {"action":"OPEN_APP","target":"YouTube"}"
        // ---------------------------------------------------------
        val embeddedJson = extractEmbeddedJson(response)

        if (embeddedJson != null) {
            parseJson(embeddedJson)?.let {
                return it
            }
        }

        // ---------------------------------------------------------
        // 4. Safe plain-text fallback
        // ---------------------------------------------------------
        return parsePlainText(response)
    }

    // =============================================================
    // JSON PARSER
    // =============================================================

    private fun parseJson(jsonText: String): JarvisCommand? {

        return try {

            val json = JSONObject(jsonText)

            val action = json
                .optString("action", "CONVERSATION")
                .trim()
                .uppercase()

            val target = json.optNullableString("target")
            val value = json.optNullableString("value")

            val requiresConfirmation =
                json.optBoolean(
                    "requiresConfirmation",
                    false
                )

            val steps = parseSteps(
                json.optJSONArray("steps")
            )

            JarvisCommand(
                action = action,
                target = target,
                value = value,
                steps = steps,
                requiresConfirmation = requiresConfirmation
            )

        } catch (_: Exception) {
            null
        }
    }

    // =============================================================
    // STEPS
    // =============================================================

    private fun parseSteps(
        stepsArray: JSONArray?
    ): List<JarvisStep> {

        if (stepsArray == null) {
            return emptyList()
        }

        val steps = mutableListOf<JarvisStep>()

        for (i in 0 until stepsArray.length()) {

            try {

                val stepObj = stepsArray.optJSONObject(i)
                    ?: continue

                val action = stepObj
                    .optString("action", "CLICK")
                    .trim()
                    .uppercase()

                val target =
                    stepObj.optNullableString("target")

                val value =
                    stepObj.optNullableString("value")

                val requiresConfirmation =
                    stepObj.optBoolean(
                        "requiresConfirmation",
                        false
                    )

                steps.add(
                    JarvisStep(
                        action = action,
                        target = target,
                        value = value,
                        requiresConfirmation = requiresConfirmation
                    )
                )

            } catch (_: Exception) {
                // Invalid step ko ignore karo,
                // pura command fail mat karo.
            }
        }

        return steps
    }

    // =============================================================
    // MARKDOWN JSON EXTRACTION
    // =============================================================

    private fun extractMarkdownJson(
        text: String
    ): String? {

        val regex = Regex(
            pattern = """```(?:json)?\s*(\{[\s\S]*?})\s*```""",
            options = setOf(RegexOption.IGNORE_CASE)
        )

        return regex
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
    }

    // =============================================================
    // EMBEDDED JSON EXTRACTION
    // =============================================================

    private fun extractEmbeddedJson(
        text: String
    ): String? {

        val start = text.indexOf('{')

        if (start < 0) {
            return null
        }

        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until text.length) {

            val char = text[i]

            if (escaped) {
                escaped = false
                continue
            }

            if (char == '\\' && inString) {
                escaped = true
                continue
            }

            if (char == '"') {
                inString = !inString
                continue
            }

            if (inString) {
                continue
            }

            when (char) {

                '{' -> {
                    depth++
                }

                '}' -> {
                    depth--

                    if (depth == 0) {
                        return text.substring(
                            start,
                            i + 1
                        ).trim()
                    }
                }
            }
        }

        return null
    }

    // =============================================================
    // PLAIN TEXT FALLBACK
    // =============================================================

    private fun parsePlainText(
        text: String
    ): JarvisCommand {

        val cleanText = text.trim()
        val upperText = cleanText.uppercase()

        // OPEN YOUTUBE
        if (
            upperText.startsWith("OPEN ") ||
            upperText.startsWith("LAUNCH ")
        ) {

            val target = when {

                upperText.startsWith("OPEN ") ->
                    cleanText.substring(5).trim()

                upperText.startsWith("LAUNCH ") ->
                    cleanText.substring(7).trim()

                else -> cleanText
            }

            if (target.isNotBlank()) {

                return JarvisCommand(
                    action = "OPEN_APP",
                    target = target
                )
            }
        }

        // SEARCH SOMETHING
        if (
            upperText.startsWith("SEARCH ") ||
            upperText.startsWith("SEARCH FOR ")
        ) {

            val value = when {

                upperText.startsWith("SEARCH FOR ") ->
                    cleanText.substring(11).trim()

                upperText.startsWith("SEARCH ") ->
                    cleanText.substring(7).trim()

                else -> cleanText
            }

            if (value.isNotBlank()) {

                return JarvisCommand(
                    action = "SEARCH",
                    value = value
                )
            }
        }

        // Everything else = normal conversation
        return JarvisCommand(
            action = "CONVERSATION",
            target = cleanText
        )
    }

    // =============================================================
    // NULLABLE JSON STRING
    // =============================================================

    private fun JSONObject.optNullableString(
        key: String
    ): String? {

        if (!has(key) || isNull(key)) {
            return null
        }

        return optString(key)
            .trim()
            .takeIf { it.isNotEmpty() }
    }
}