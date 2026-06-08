package com.codex.wordoverlay

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context

object ClipboardTextReader {
    data class Result(
        val text: CharSequence?,
        val timestamp: Long,
        val skippedReason: String? = null
    )

    fun readTextOnly(context: Context, clip: ClipData?): Result {
        if (clip == null) return Result(text = null, timestamp = 0L, skippedReason = "empty")

        val timestamp = clip.description.timestamp
        val text = readTextItem(clip)
        if (text != null) {
            return Result(text = text, timestamp = timestamp)
        }

        val reason = if (clip.description.hasTextMimeType()) {
            "text clipboard was empty"
        } else {
            "non-text clipboard skipped: ${clip.description.mimeTypesSummary()}"
        }
        return Result(text = null, timestamp = timestamp, skippedReason = reason)
    }

    private fun readTextItem(clip: ClipData): CharSequence? {
        for (index in 0 until clip.itemCount) {
            val item = runCatching { clip.getItemAt(index) }.getOrNull() ?: continue
            item.text?.takeIf { it.isNotBlank() }?.let { return it }
            item.htmlText?.takeIf { it.isNotBlank() }?.let { return it.stripHtmlTags() }
        }
        return null
    }

    private fun ClipDescription.hasTextMimeType(): Boolean {
        return hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
            hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) ||
            hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)
    }

    private fun ClipDescription.mimeTypesSummary(): String {
        return (0 until mimeTypeCount)
            .joinToString(separator = ",") { index -> getMimeType(index) }
            .ifBlank { "unknown" }
    }

    private fun CharSequence.stripHtmlTags(): String {
        return toString()
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
