package com.codex.wordoverlay

object RootClipboardReader {
    data class ReadResult(
        val text: CharSequence?,
        val error: String? = null,
        val rawOutput: String = ""
    )

    fun read(): ReadResult {
        val result = RootShell.run(READ_CLIPBOARD_COMMAND, timeoutMillis = ROOT_READ_TIMEOUT_MILLIS)
        if (!result.isSuccess) {
            return ReadResult(
                text = null,
                error = "exit=${result.exitCode} stdout=${result.stdout} stderr=${result.stderr}",
                rawOutput = result.stdout
            )
        }

        return ReadResult(
            text = parseServiceCallText(result.stdout),
            rawOutput = result.stdout
        )
    }

    private fun parseServiceCallText(output: String): String? {
        val bytes = parcelBytesFrom(output)
        if (bytes.isEmpty()) return null

        val candidates = buildList {
            addAll(extractAsciiStrings(bytes))
            addAll(extractUtf16LeStrings(bytes))
        }

        return candidates
            .asReversed()
            .firstNotNullOfOrNull { candidate -> WordExtractor.normalize(candidate) }
    }

    private fun parcelBytesFrom(output: String): ByteArray {
        val bytes = mutableListOf<Byte>()
        output.lineSequence().forEach { line ->
            val dataPart = line.substringBefore("'")
                .substringAfter(":", missingDelimiterValue = line)
                .substringAfter("Parcel(", missingDelimiterValue = line)

            dataPart
                .trim()
                .split(Regex("\\s+"))
                .filter { it.length == 8 && it.all(::isParcelHexDigit) }
                .forEach { word ->
                    val value = word.toLong(16).toInt()
                    bytes += (value and 0xFF).toByte()
                    bytes += ((value ushr 8) and 0xFF).toByte()
                    bytes += ((value ushr 16) and 0xFF).toByte()
                    bytes += ((value ushr 24) and 0xFF).toByte()
                }
        }
        return bytes.toByteArray()
    }

    private fun extractAsciiStrings(bytes: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        val builder = StringBuilder()

        fun flush() {
            if (builder.isNotEmpty()) {
                strings += builder.toString()
                builder.clear()
            }
        }

        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            if (value in ASCII_PRINTABLE_RANGE) {
                builder.append(value.toChar())
            } else {
                flush()
            }
        }
        flush()

        return strings
    }

    private fun extractUtf16LeStrings(bytes: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        var index = 0

        while (index < bytes.size - 1) {
            val builder = StringBuilder()
            var cursor = index
            while (cursor < bytes.size - 1) {
                val low = bytes[cursor].toInt() and 0xFF
                val high = bytes[cursor + 1].toInt() and 0xFF
                if (high != 0 || low !in ASCII_PRINTABLE_RANGE) break
                builder.append(low.toChar())
                cursor += 2
            }

            if (builder.length >= MIN_UTF16_STRING_LENGTH) {
                strings += builder.toString()
                index = cursor
            } else {
                index += 1
            }
        }

        return strings
    }

    private fun isParcelHexDigit(char: Char): Boolean {
        return char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
    }

    private val ASCII_PRINTABLE_RANGE = 0x20..0x7E
    private const val MIN_UTF16_STRING_LENGTH = 2
    private const val ROOT_READ_TIMEOUT_MILLIS = 2_500L

    // Android 16 / MIUI exposes getPrimaryClip as transaction 4 on android.content.IClipboard.
    private const val READ_CLIPBOARD_COMMAND = "service call clipboard 4 s16 com.android.shell i32 0"
}
