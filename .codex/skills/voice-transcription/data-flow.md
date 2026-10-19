# Muse dictation data flow

1. A fixed-strip mic tap snapshots mode/diagnostic preferences and validates permissions, personal key and network route.
2. Capture starts locally, selecting initialized 24 kHz / fallback 16 kHz audio.
3. Manager starts one Muse WebSocket and buffers every PCM chunk, including quiet.
4. Send JSON handshake, wait for sessionId acknowledgment, then pace binary audio.
5. Partials update internal evidence only. Turn assembler holds complete text until
   attributed in default DIARIZATION, then releases in observed speech-start order.
   First speaker wins; other-speaker turns settle without insertion. Explicit
   Responsive dictation uses ENDPOINTING and releases all completed ended turns
   without attribution. Timing diagnostics observe receipt/release, never payloads.
6. Manager keeps each selected TranscriptSegment until LatinIME reports local
   dispatch/cleanup success. LatinIME clears typed composing state and uses
   InputConnection; explicit spoken commands use verified single replacements.
7. Pause stops mic capture; a barrier queues its posted tail before generated
   silence. Resume removes the generator. The speaker/session stays the same.
8. Explicit stop joins capture, drains posted tail and paced PCM, sends endStream,
   accepts late completed turns, and waits for close 1000. A 60-second overall
   stop deadline cannot be reset by progress, EOF or a late final.
9. Failure invalidates generations and preserves confirmed editor text. Startup
   rejection and explicit cancellation leave no marker; actual post-ready/capture
   failure attempts one literal interruption marker, except after editor rejection.
10. Interactive hide/target loss/verified host clears cancel capture and drain
    immediately; screen-off hiding alone preserves recording. Restart is explicit.

See `docs/muse-transcription.md` for limits, settings, privacy, source links,
protocol sample discrepancies and live/phone acceptance.
