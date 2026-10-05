# Voice data flow

1. The fixed right-edge microphone invokes LatinIME's VoiceInputManager.
2. TranscriptionPreferences supplies the Speech resource key, matching region, and language detection setting. Local preferences configure silence and auto-stop.
3. VoiceRecorder captures mono 16 kHz PCM16. VoiceInputManager queues startup audio while MaiTranscriptionClient starts the SDK session.
4. AzureMaiSpeechSession serializes SDK/JNI operations on a worker. The SDK manages the direct Azure connection, acknowledgements, and recovery.
5. The manager uploads chunks and requests advisory commits at silence and pause. It holds silent audio after a boundary while retaining an onset prefix.
6. The client handles final audio offsets/commit tokens, deduplicates result IDs, and suppresses stale callbacks. LatinIME clears typed-word state, inserts completed text through InputConnection, and runs paragraph cleanup.
7. Stop, rotation, and unconfirmed-boundary fallback close push input and await EOF while finals continue arriving. A mic restart during drain captures into the buffer immediately; a replacement starts after EOF to keep transcripts ordered. Cancel invalidates tokens immediately and releases native resources asynchronously. App retries are limited to failed sessions without unfinished uploaded speech; the SDK handles audio recovery.

The manager owns recorder/UI state. The client owns lifecycle and pending boundaries. The SDK adapter owns native handles and worker calls. The IME owns editor mutation. Settings screens edit preferences. No editor context is sent upstream.
