# Voice-to-Text Setup Guide

This document explains how to set up and use the voice-to-text (dictation) feature in HeliBoard.

## Overview

HeliBoard includes a built-in voice-to-text feature that uses Deepgram's Nova-3 model to transcribe your speech into text. After transcription, the text can be cleaned up and polished using Anthropic's Claude AI.

## How It Works

1. **Recording**: When you tap the microphone button, HeliBoard starts recording audio instantly using your device's microphone.
2. **Silence Detection**: The recorder automatically detects pauses in speech (3 seconds of silence).
3. **Transcription**: Each audio segment is sent to Deepgram's API for transcription.
4. **Text Insertion**: The transcribed text is automatically inserted into the text field.
5. **Cleanup**: After insertion, the current paragraph is sent to Anthropic's Claude for intelligent cleanup (capitalization, punctuation, grammar).

## Setup Instructions

### Step 1: Get a Deepgram API Key

1. Go to [Deepgram's console](https://console.deepgram.com/)
2. Sign up for an account or log in
3. Navigate to **API Keys**
4. Click **"Create a New API Key"**
5. Copy the API key

> **Note**: Deepgram offers a free tier with $200 in credits. After that, pricing is ~$0.0043/minute for Nova-3.

### Step 2: Get an Anthropic API Key (Optional — for text cleanup)

1. Go to [Anthropic's console](https://console.anthropic.com/)
2. Sign up for an account or log in
3. Navigate to **API Keys**
4. Create and copy a new API key

> This is optional. Without it, transcription still works but text cleanup (auto-capitalization, punctuation, grammar) is disabled.

### Step 3: Configure HeliBoard

1. Open HeliBoard Settings
2. Navigate to **Transcription** settings
3. Paste your **Deepgram API Key**
4. Paste your **Anthropic API Key** (optional)
5. (Optional) Tune:
   - **Chunk silence duration** (default 3s)
   - **Silence threshold** (RMS loudness floor)
   - **New paragraph silence duration** (default 12s)

### Step 4: Grant Microphone Permission

When you first tap the microphone button, Android will ask for microphone permission. Grant this permission to enable voice recording.

## Usage

1. **Start Recording**: Tap the microphone icon. The icon turns **red** immediately — recording starts instantly with no delay.

2. **Speak**: Talk naturally. After you pause for the configured chunk-silence duration (default ~3s), the audio segment is sent for transcription and the text appears.

3. **Stop Recording**: Tap the microphone icon again to stop. Any remaining audio is transcribed.

4. **Pause/Resume**: Use the pause button to temporarily pause without stopping the session.

## Visual Indicators

| State | Microphone Icon Color |
|-------|----------------------|
| Idle (ready) | Default (gray) |
| Recording | Red |
| Paused | Yellow |

## Processing Pipeline

```
Speech → [configured chunk silence] → Audio segment (WAV)
  → Deepgram transcription → Insert text
  → [3s more silence] → Anthropic cleanup → Replace paragraph
```

1. **Transcription**: Each audio segment is transcribed independently via Deepgram
2. **Text insertion**: Transcribed text is inserted with smart capitalization
3. **Cleanup**: After 3 more seconds of silence, the entire current paragraph is sent to Claude for cleanup
4. **New paragraph**: After the configured new-paragraph silence duration (default 12s), a new paragraph is started

## Troubleshooting

### "Deepgram API key not configured"
- Make sure you've entered your API key in Settings > Transcription > Deepgram API Key

### "Microphone permission required"
- Go to Android Settings > Apps > HeliBoard > Permissions > Enable Microphone

### Transcription seems wrong
- Speak clearly and close to the microphone
- Reduce background noise
- Check your internet connection

### No cleanup happening
- Make sure your Anthropic API key is set
- Cleanup only runs after 3 seconds of silence following transcription

## API Costs

| Service | Cost | What it does |
|---------|------|-------------|
| Deepgram Nova-3 | ~$0.0043/min | Speech-to-text transcription |
| Anthropic Claude Haiku | ~$0.25/1M input tokens | Text cleanup and polishing |

## Privacy

- Audio segments are sent to Deepgram for transcription
- Text paragraphs are sent to Anthropic for cleanup (if enabled)
- No audio is stored locally after transcription
- No data is stored by HeliBoard beyond the current session

## Language Support

Deepgram Nova-3 supports 30+ languages. The keyboard automatically sends the current language as a hint. Supported languages include English, Spanish, French, German, Italian, Portuguese, Chinese, Japanese, Korean, and many more.

## Technical Details

- Audio Format: WAV (PCM 16-bit, 16kHz, mono)
- Transcription API: `POST https://api.deepgram.com/v1/listen`
- Transcription Model: `nova-3` with `smart_format=true`
- Cleanup API: Anthropic Messages API (`claude-haiku-4-5`)
- Silence Detection: Adaptive RMS threshold (user-configurable floor)
- Silence Duration: User-configurable (default 3 seconds)
