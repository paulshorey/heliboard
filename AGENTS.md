# HeliBoard

HeliBoard is an Android app, open-source project based on AOSP / OpenBoard keyboard.

## This project rewrites HeliBoard with custom experimental features

1. Voice to text (using Deepgram Nova-3 transcription + Anthropic Claude cleanup)
2. Smart auto-capitalization
3. UI features

## Voice to text UI

1. User taps microphone button in the top right corner of the keyboard
2. Recording starts
3. After a period of silence, recording is chunked (stops to save the file and start processing, but restarts immediately)
4. Send recorded audio chunk to Deepgram API for transcription
5. Received transcribed text, apply post-processing
6. Send transcribed text to Anthropic Claude API for cleanup. Important: Not only the transcribed text is sent, but also the last few sentences (context).
7. Received cleaned up text. Do not simply add it at the end of the text area, but replace the exact previous text with new transcribed and cleaned text.

- Find the previous text (few sentences that was sent to the cleanup API as context)
- Replace that with the new cleaned up text (context + new transcription)

## Handling chunked audio recordings

1 ChunkA audio → Deepgram
2 ChunkB audio queued in VoiceInputManager
3 ChunkA transcription received → onTranscriptionResult(textA)
4 mCleanupInProgress=false → processTranscriptionResult(textA) called
5 getRecentContext() called NOW for ChunkA → captures current text
6 Sent to Anthropic → mCleanupInProgress=true
7 processNextSegment() → ChunkB sent to Deepgram
8 ChunkB transcription arrives → mCleanupInProgress=true → buffered
10 processPendingVoiceInput() → processTranscriptionResult(textB)
11 getRecentContext() called NOW for ChunkB → captures text after A's
