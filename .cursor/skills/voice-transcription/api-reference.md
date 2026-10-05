# MAI Speech SDK in HeliBoard

Source: [Microsoft MAI-Transcribe-2-Streaming Speech SDK guide](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-speech-sdk).

Use Android dependency `com.microsoft.cognitiveservices.speech:client-sdk:1.52.0`. `SpeechConfig.fromEndpoint(URI("wss://<region>.stt.speech.microsoft.com/speech/universal/v2"), key)` authenticates the resource. Call `setModel("MAI-Transcribe-2-Streaming")`; an optional BCP-47 `setSpeechRecognitionLanguage` supplies a hint. Omit the hint for multilingual detection.

Create `AudioStreamFormat.getWaveFormatPCM(16000, 16, 1)`, a `PushAudioInputStream`, `AudioConfig.fromStreamInput`, and `SpeechRecognizer`. Start continuous recognition on the serial worker and push headerless PCM16 after startup. SDK-native buffers handle acknowledgements and recovery.

`PushAudioInputStream.commit()` requests a final without closing input. Positive correlation tokens can appear as `RecognitionResult.getCommitToken()` on final events; zero means the advisory request was rejected. Tokens may never be echoed, so use final audio offsets as well. `NoMatch` is a valid result without text. Deliver only `RecognizedSpeech` results; deduplicate result IDs rather than text.

For stop, close the input and wait for session termination before stopping/disposing recognition. For cancel, invalidate callbacks immediately, then stop and release handles off the main thread. Never promote intermediate text or log raw cancellation details. Preserve the documented 60-second application completion/drain deadline and startup/queue bounds.

Current regions are `centralus`, `swedencentral`, and `southeastasia`. The key must belong to the selected resource region. SDK dictation is gated to API 26+ and native ABIs shipped in the pinned AAR: ARM32, ARM64, and x86-64.
