# MAI Realtime API in HeliBoard

Source: [Microsoft MAI-Transcribe-2-Streaming Realtime guide](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-realtime).

The resource root becomes `wss://<resource>.services.ai.azure.com/mai/v1/realtime?intent=transcription`. The `api-key` header authenticates the resource. After session.created, HeliBoard sends:

```json
{
  "type": "session.update",
  "session": {
    "type": "transcription",
    "audio": {
      "input": {
        "format": {"type": "audio/pcm", "rate": 16000},
        "transcription": {"model": "<your-deployment-name>", "language": "en"},
        "turn_detection": null,
        "noise_reduction": null
      }
    }
  }
}
```

The app waits for session.updated, then sends input_audio_buffer.append with base64 PCM16 in audio. Local silence and user controls send input_audio_buffer.commit. A committed acknowledgement leaves processing pending; the app delivers completed.transcript and waits for all outstanding results before closing on stop.

Delta and intermediate events do not alter the editor. Their provisional delivery is separate from the app's completed-segment insertion. Resource endpoint, deployment, credentials, and language detection are configured in TranscriptionPreferences. The internal endpoint override is for local tests only.
