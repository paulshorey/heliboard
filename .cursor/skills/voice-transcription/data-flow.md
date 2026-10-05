# Voice data flow

1. The fixed right-edge microphone invokes LatinIME's VoiceInputManager.
2. TranscriptionPreferences supplies the Azure resource root, API key, deployment name, and language-detection setting. Local preferences configure silence and auto-stop.
3. VoiceRecorder starts mono 16 kHz PCM16 capture. VoiceInputManager queues startup audio while MaiTranscriptionClient completes the MAI Realtime handshake.
4. The manager uploads chunks, requests explicit commits at silence and user pause/stop, and holds silent audio after a speech boundary while retaining an onset prefix.
5. MaiTranscriptionClient matches completed results to pending commits, preserves FIFO delivery, suppresses stale socket events, and drains the finalization queue before closing.
6. LatinIME clears typed-word state, inserts the completed TranscriptSegment through InputConnection, and runs local paragraph cleanup in the batch edit.
7. Stop and rotation retain the receiving socket until all finals arrive. Cancel invalidates all tokens immediately. Failures with uploaded unfinished speech report incomplete dictation; recoverable startup failures use bounded backoff.

The manager owns recorder/UI state. The client owns protocol and pending commits. The IME owns editor mutation. Settings screens only edit preferences. No editor context is sent upstream.
