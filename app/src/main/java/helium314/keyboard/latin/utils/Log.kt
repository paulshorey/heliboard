package helium314.keyboard.latin.utils

import android.os.Build
import java.time.LocalDateTime
import java.util.Date

/**
 * Logger that does the android logging, but also allows reading the log in the app.
 * It's only a little slower than the android logger, but since both are used we end up at
 * half performance (still fast enough to not be noticeable, unless spamming thousands of log lines)
 */
object Log {
    @JvmStatic
    fun wtf(tag: String?, message: String) {
        log(LogLine('F', tag, message))
        android.util.Log.wtf(tag, message)
    }

    @JvmStatic
    fun e(tag: String?, message: String, e: Throwable?) {
        log(LogLine('E', tag, "$message\n${e?.stackTraceToString()}"))
        android.util.Log.e(tag, message, e)
    }

    @JvmStatic
    fun e(tag: String?, message: String) {
        log(LogLine('E', tag, message))
        android.util.Log.e(tag, message)
    }

    @JvmStatic
    fun w(tag: String?, message: String, e: Throwable?) {
        log(LogLine('W', tag, "$message\n${e?.stackTraceToString()}"))
        android.util.Log.w(tag, message, e)
    }

    @JvmStatic
    fun w(tag: String?, message: String) {
        log(LogLine('W', tag, message))
        android.util.Log.w(tag, message)
    }

    @JvmStatic
    fun i(tag: String?, message: String, e: Throwable?) {
        log(LogLine('I', tag, "$message\n${e?.stackTraceToString()}"))
        android.util.Log.i(tag, message, e)
    }

    @JvmStatic
    fun i(tag: String?, message: String) {
        log(LogLine('I', tag, message))
        android.util.Log.i(tag, message)
    }

    @JvmStatic
    fun d(tag: String?, message: String, e: Throwable?) {
        log(LogLine('D', tag, "$message\n${e?.stackTraceToString()}"))
        android.util.Log.d(tag, message, e)
    }

    @JvmStatic
    fun d(tag: String?, message: String) {
        log(LogLine('D', tag, message))
        android.util.Log.d(tag, message)
    }

    @JvmStatic
    fun v(tag: String?, message: String) {
        log(LogLine('V', tag, message))
        android.util.Log.v(tag, message)
    }

    private fun log(line: LogLine) {
        synchronized(logLines) {
            if (logLines.size > 12000) // clear oldest entries if list gets too long
                logLines.subList(0, 2000).clear()
            logLines.add(line)
        }
    }

    private val logLines: MutableList<LogLine> = ArrayList(2000)

    /** returns a copy of [logLines] */
    fun getLog(maxLines: Int = logLines.size) = synchronized(logLines) { logLines.takeLast(maxLines) }

    private val VOICE_DIAGNOSTIC_TAGS = setOf(
        "VoiceInputManager",
        "VoiceRecorder",
        "SonioxTranscription",
    )

    private const val LATIN_IME_TAG = "LatinIME"

    private val LATIN_IME_VOICE_MESSAGE_MARKERS = listOf(
        "VOICE_",
        "Voice input",
        "voice input",
        "voice work",
        "Voice wake lock",
        "voice error toast",
        "transcription",
        "Microphone permission",
        "Gracefully stopping voice",
        "discarding voice",
        "editor context for Soniox",
    )

    const val DEFAULT_VOICE_DIAGNOSTICS_MAX_LINES = 500

    private val RAW_TRANSCRIPT_PATTERN = Regex("""VOICE raw transcript=\[(.*)]""", RegexOption.DOT_MATCHES_ALL)
    private val API_KEY_PATTERN = Regex("""api_key\s*[:=]\s*"?[^\s,"}\]]+"?""", RegexOption.IGNORE_CASE)

    @JvmStatic
    fun isVoiceDiagnosticLine(line: LogLine): Boolean {
        val tag = line.tag ?: return false
        if (tag in VOICE_DIAGNOSTIC_TAGS) return true
        if (tag != LATIN_IME_TAG) return false
        val message = line.message
        return LATIN_IME_VOICE_MESSAGE_MARKERS.any { marker -> message.contains(marker, ignoreCase = false) }
    }

    @JvmStatic
    fun redactVoiceDiagnosticMessage(message: String): String {
        var result = RAW_TRANSCRIPT_PATTERN.replace(message) { match ->
            val content = match.groupValues[1]
            "VOICE raw transcript=[${content.length} chars]"
        }
        result = API_KEY_PATTERN.replace(result, "api_key=[redacted]")
        return result
    }

    fun getVoiceDiagnosticsLog(maxLines: Int = DEFAULT_VOICE_DIAGNOSTICS_MAX_LINES): List<LogLine> =
        synchronized(logLines) { filterVoiceDiagnosticsLines(logLines, maxLines) }

    internal fun filterVoiceDiagnosticsLines(lines: List<LogLine>, maxLines: Int): List<LogLine> {
        val result = ArrayList<LogLine>(minOf(maxLines, 64))
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            if (isVoiceDiagnosticLine(line)) {
                result.add(line)
                if (result.size >= maxLines) break
            }
        }
        result.reverse()
        return result
    }

    @JvmStatic
    fun formatVoiceDiagnosticsExport(lines: List<LogLine>, appVersion: String): String {
        val header = buildString {
            appendLine("HeliBoard voice diagnostics")
            appendLine("App version: $appVersion")
            appendLine("Lines: ${lines.size} (oldest first)")
            appendLine()
        }
        return header + lines.joinToString("\n") { it.formatLine(redact = true) }
    }
}

data class LogLine(val level: Char, val tag: String?, val message: String) {

    // time can be Date or LocalDateTime, doesn't matter because but it's used for toString only
    private val time = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        LocalDateTime.now()
    } else {
        Date(System.currentTimeMillis())
    }

    fun formatLine(redact: Boolean = false): String {
        val formattedMessage = if (redact) Log.redactVoiceDiagnosticMessage(message) else message
        return "${time.toString().replace('T', ' ')} $level $tag: $formattedMessage"
    }

    override fun toString(): String = formatLine(redact = false)
}
