package helium314.keyboard.latin.utils

import android.os.Build
import helium314.keyboard.latin.BuildConfig
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
            if (isVoiceDiagnosticLine(line)) {
                if (voiceLogLines.size == DEFAULT_VOICE_DIAGNOSTICS_MAX_LINES) voiceLogLines.removeFirst()
                voiceLogLines.addLast(line)
            }
        }
    }

    private val logLines: MutableList<LogLine> = ArrayList(2000)
    // Keyboard geometry/key traces must not evict an entire dictation failure.
    private val voiceLogLines = ArrayDeque<LogLine>()

    /** returns a copy of [logLines] */
    fun getLog(maxLines: Int = Int.MAX_VALUE) = synchronized(logLines) { logLines.takeLast(maxLines.coerceAtLeast(0)) }

    private val VOICE_DIAGNOSTIC_TAGS = setOf(
        "VoiceInputManager",
        "VoiceRecorder",
        "GeminiTranscription",
        "VoiceNetwork",
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
        "editor context for voice vocabulary",
    )

    const val DEFAULT_VOICE_DIAGNOSTICS_MAX_LINES = 500

    private val RAW_TRANSCRIPT_PATTERN = Regex("""VOICE raw transcript=\[(.*)]""", RegexOption.DOT_MATCHES_ALL)
    private val API_KEY_PATTERN = Regex("""api_key\s*[:=]\s*"?[^\s,"}\]]+"?""", RegexOption.IGNORE_CASE)

    /** Gemini passes the API key in the WebSocket query string (`?key=...`). */
    private val URL_KEY_QUERY_PATTERN = Regex("""([?&])key=[^\s&"]+""", RegexOption.IGNORE_CASE)

    @JvmStatic
    fun isVoiceDiagnosticLine(line: LogLine): Boolean {
        val tag = line.tag ?: return false
        if (tag in VOICE_DIAGNOSTIC_TAGS) return true
        if (tag == "RichInputConnection") return line.message.startsWith("VOICE ")
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
        result = URL_KEY_QUERY_PATTERN.replace(result, "$1key=[redacted]")
        return result
    }

    fun getVoiceDiagnosticsLog(maxLines: Int = DEFAULT_VOICE_DIAGNOSTICS_MAX_LINES): List<LogLine> =
        synchronized(logLines) { voiceLogLines.takeLast(maxLines.coerceAtLeast(0)) }

    internal fun filterVoiceDiagnosticsLines(lines: List<LogLine>, maxLines: Int): List<LogLine> {
        if (maxLines <= 0) return emptyList()
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
            appendLine("Build: ${BuildConfig.BUILD_TYPE}; Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Lines: ${lines.size} (oldest first)")
            appendLine()
        }
        return header + lines.joinToString("\n") { it.formatLine(redact = true) }
    }

    /** Focused voice history first, then bounded warnings without duplicated app logcat. */
    fun formatDebugLogExport(
        lines: List<LogLine>,
        voiceLines: List<LogLine>,
        appVersion: String,
        systemWarnings: String,
    ): String = buildString {
        appendLine(formatVoiceDiagnosticsExport(voiceLines, appVersion))
        appendLine()
        appendLine("Other app warnings/errors (newest 500; consecutive repeats collapsed)")
        val warnings = lines.filter { it.level in "WEF" && !isVoiceDiagnosticLine(it) }.takeLast(500)
        appendLine(compactWarnings(warnings))
        appendLine()
        appendLine("Recent Android warnings/errors (app duplicates excluded)")
        val appTags = (lines + voiceLines).mapNotNull { it.tag }.toSet()
        val tagPattern = Regex("""\s[VDIWEF]\s+([^:]+):""")
        append(systemWarnings.lineSequence().filter { line ->
            tagPattern.find(line)?.groupValues?.get(1)?.trim() !in appTags
        }.joinToString("\n") { redactVoiceDiagnosticMessage(it) })
    }

    internal fun compactWarnings(lines: List<LogLine>): String = buildString {
        var index = 0
        while (index < lines.size) {
            val first = lines[index]
            var end = index + 1
            while (end < lines.size && lines[end].level == first.level &&
                lines[end].tag == first.tag && lines[end].message == first.message) end++
            // Use the last occurrence's timestamp, retaining the repeat count.
            append(lines[end - 1].formatLine(redact = true))
            if (end - index > 1) append(" [repeated ${end - index} times]")
            appendLine()
            index = end
        }
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
