package com.codex.wordoverlay

import java.util.concurrent.TimeUnit

object RootShell {
    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val isSuccess: Boolean = exitCode == 0
    }

    fun run(command: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Result {
        return runCatching {
            val process = ProcessBuilder("su", "-c", command).start()
            val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                return Result(
                    exitCode = TIMEOUT_EXIT_CODE,
                    stdout = "",
                    stderr = "Timed out after ${timeoutMillis}ms"
                )
            }

            Result(
                exitCode = process.exitValue(),
                stdout = process.inputStream.bufferedReader().readText().trim(),
                stderr = process.errorStream.bufferedReader().readText().trim()
            )
        }.getOrElse { error ->
            Result(
                exitCode = EXECUTION_ERROR_EXIT_CODE,
                stdout = "",
                stderr = error.message ?: error::class.java.simpleName
            )
        }
    }

    fun runScript(script: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Result {
        return runCatching {
            val process = ProcessBuilder("su", "-c", "sh").start()
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(script)
                writer.newLine()
            }

            val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                return Result(
                    exitCode = TIMEOUT_EXIT_CODE,
                    stdout = "",
                    stderr = "Timed out after ${timeoutMillis}ms"
                )
            }

            Result(
                exitCode = process.exitValue(),
                stdout = process.inputStream.bufferedReader().readText().trim(),
                stderr = process.errorStream.bufferedReader().readText().trim()
            )
        }.getOrElse { error ->
            Result(
                exitCode = EXECUTION_ERROR_EXIT_CODE,
                stdout = "",
                stderr = error.message ?: error::class.java.simpleName
            )
        }
    }

    private const val DEFAULT_TIMEOUT_MILLIS = 8_000L
    private const val TIMEOUT_EXIT_CODE = -2
    private const val EXECUTION_ERROR_EXIT_CODE = -1
}
