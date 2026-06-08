package com.codex.wordoverlay

object WordExtractor {
    private val singleWordRegex = Regex("[A-Za-z][A-Za-z'-]{0,63}")
    private val englishTextRegex = Regex("[A-Za-z]")
    private val allowedTextRegex = Regex("[A-Za-z0-9\\s.,!?;:'\"()\\[\\]{}\\-_/]+")
    private val whitespaceRegex = Regex("\\s+")

    fun normalize(text: CharSequence?): String? {
        val normalized = text
            ?.toString()
            ?.replace('\u00A0', ' ')
            ?.replace(whitespaceRegex, " ")
            ?.trim()
            .orEmpty()
        if (normalized.isBlank()) return null
        if (!englishTextRegex.containsMatchIn(normalized)) return null
        if (!allowedTextRegex.matches(normalized)) return null

        return normalized
            .take(MAX_QUERY_LENGTH)
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { query ->
                if (isSingleWord(query)) query.lowercase() else query
            }
    }

    fun isSingleWord(text: String): Boolean = singleWordRegex.matches(text.trim())

    private const val MAX_QUERY_LENGTH = 300
}
