package com.codex.wordoverlay

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DictionaryApiClient {
    suspend fun lookup(query: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (WordExtractor.isSingleWord(query)) {
                fetchYoudaoMeaning(query)
                    ?: fetchIcibaMeaning(query)
                    ?: fetchMyMemoryTranslation(query)
                    ?: fetchEnglishDictionaryMeaning(query)
            } else {
                fetchMyMemoryTranslation(query)
                    ?: fetchYoudaoMeaning(query)
                    ?: fetchIcibaMeaning(query)
            }
        }.onFailure { error ->
            Log.w(TAG, "Dictionary lookup failed for query=$query", error)
        }.getOrNull()
    }

    private fun fetchYoudaoMeaning(word: String): String? {
        val encodedWord = encode(word)
        val url = URL("https://dict.youdao.com/suggest?q=$encodedWord&doctype=json")
        val body = get(url) ?: return null
        val root = JSONObject(body)
        val entries = root.optJSONObject("data")?.optJSONArray("entries") ?: return null

        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val entryWord = entry.optString("entry")
            if (entryWord.equals(word, ignoreCase = true)) {
                return entry.optString("explain").cleanMeaning()
            }
        }

        return null
    }

    private fun fetchIcibaMeaning(word: String): String? {
        val encodedWord = encode(word)
        val url = URL("https://dict.iciba.com/dictionary/word/suggestion?word=$encodedWord")
        val body = get(url) ?: return null
        val entries = JSONObject(body).optJSONArray("message") ?: return null

        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val entryWord = entry.optString("key")
            if (entryWord.equals(word, ignoreCase = true)) {
                return entry.optString("paraphrase").cleanMeaning()
            }
        }

        return null
    }

    private fun fetchEnglishDictionaryMeaning(word: String): String? {
        val encodedWord = encode(word)
        val url = URL("https://api.dictionaryapi.dev/api/v2/entries/en/$encodedWord")
        val body = get(url) ?: return null
        val entries = JSONArray(body)
        if (entries.length() == 0) return null

        val firstEntry = entries.getJSONObject(0)
        val phonetic = firstEntry.optString("phonetic")
        val meanings = firstEntry.optJSONArray("meanings") ?: return phonetic.ifBlank { null }

        val lines = buildList {
            if (phonetic.isNotBlank()) {
                add(phonetic)
            }

            for (i in 0 until minOf(2, meanings.length())) {
                val meaning = meanings.getJSONObject(i)
                val partOfSpeech = meaning.optString("partOfSpeech")
                val definitions = meaning.optJSONArray("definitions") ?: continue
                if (definitions.length() == 0) continue

                val definitionText = definitions.getJSONObject(0).optString("definition")
                if (definitionText.isNotBlank()) {
                    val prefix = if (partOfSpeech.isNotBlank()) "[$partOfSpeech] " else ""
                    add(prefix + definitionText)
                }
            }
        }

        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun fetchMyMemoryTranslation(query: String): String? {
        val encodedQuery = encode(query)
        val url = URL("https://api.mymemory.translated.net/get?q=$encodedQuery&langpair=en|zh-CN")
        val body = get(url) ?: return null
        val root = JSONObject(body)
        val translatedText = root
            .optJSONObject("responseData")
            ?.optString("translatedText")
            ?.cleanMeaning()

        return translatedText
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { !it.equals(query, ignoreCase = true) }
    }

    private fun get(url: URL): String? {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            requestMethod = "GET"
        }

        return try {
            val code = connection.responseCode
            Log.d(TAG, "GET $url responseCode=$code")
            if (code !in 200..299) {
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun String.cleanMeaning(): String? {
        return trim()
            .replace("；", "\n")
            .replace(";", "\n")
            .replace("...", "")
            .takeIf { it.isNotBlank() }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private const val TAG = "DictionaryApi"
    }
}
