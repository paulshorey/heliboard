package helium314.keyboard.latin.voice

import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class MuseLatencyDiagnosticsTest {
    @Test fun separatesProviderCompletionFromLocalOrderingAndNeverPrintsPayloads() {
        var now = 100L
        val logs = mutableListOf<String>()
        val timing = MuseLatencyDiagnostics({ now }, logs::add)
        fun event(type: String, text: String = "") = timing.event(JSONObject().put("type", type)
            .put("turnId", 7).put("audioProcessedMs", 200).put("transcript", text)
            .put("label", "Bearer private-key"), 100, 20)
        event("speechStart")
        now = 200; event("transcript", "private words")
        now = 250; event("transcript", "private words?")
        now = 300; event("speaker")
        now = 400; event("speechEnd")
        now = 600; event("speechComplete", "Private words?")
        now = 800; timing.released(7, true)
        val release = logs.last()
        assertTrue(release.contains("endToFinalMs=200"))
        assertTrue(release.contains("finalToReleaseMs=200"))
        assertTrue(release.contains("firstPartialMs=100 firstPunctuationMs=150"))
        assertFalse(logs.joinToString().contains("private", ignoreCase = true))
        assertFalse(logs.joinToString().contains("Bearer"))
    }
    @Test fun progressIsSampledRatherThanFloodingDiagnostics() {
        var now = 0L
        val logs = mutableListOf<String>()
        val timing = MuseLatencyDiagnostics({ now }, logs::add)
        repeat(31) {
            timing.event(JSONObject().put("type", "audioProgress").put("audioProcessedMs", now), 0, 0)
            now += 100
        }
        assertEquals(4, logs.size)
    }
}
