# Voice-to-Text Setup Guide

This document explains how to set up and use the voice-to-text (dictation) feature in HeliBoard.

## Overview

HeliBoard includes a built-in voice-to-text feature that uses OpenAI's Whisper API to transcribe your speech into text. This feature allows you to dictate messages, notes, or any text input using your voice.

## How It Works

1. **Recording**: When you tap the microphone button, HeliBoard starts recording audio using your device's microphone.
2. **Transcription**: When you tap the button again to stop recording, the audio is sent to OpenAI's Whisper API for transcription.
3. **Text Input**: The transcribed text is automatically inserted into the text field you're typing in.

## Setup Instructions

### Step 1: Get an OpenAI API Key

1. Go to [OpenAI's platform](https://platform.openai.com/)
2. Sign up for an account or log in if you already have one
3. Navigate to **API Keys** section (https://platform.openai.com/api-keys)
4. Click **"Create new secret key"**
5. Copy the API key (it starts with `sk-`)

> **Important**: Keep your API key secure and never share it publicly. You will be charged for API usage based on OpenAI's pricing.

### Step 2: Configure HeliBoard

1. Open HeliBoard Settings
2. Navigate to **Advanced** settings
3. Find **"OpenAI API Key"**
4. Paste your API key and save

### Step 3: Configure Style Prompt (Optional)

The **Voice Input Style Prompt** setting allows you to customize how Whisper transcribes your speech:

1. In **Advanced** settings, find **"Voice Input Style Prompt"**
2. Enter a prompt to guide the transcription style

**Example prompts:**
- `Use proper capitalization and punctuation.` (default)
- `Transcribe in all lowercase with no punctuation.`
- `Use formal business English with proper grammar.`
- `Include technical terms like API, JSON, HTTP, SDK.`
- `This is a text message conversation. Use casual language.`

The prompt helps Whisper understand context and produce better results for your specific use case.

### Step 4: Grant Microphone Permission

When you first tap the microphone button, Android will ask for microphone permission. Grant this permission to enable voice recording.

## Usage

1. **Start Recording**: Tap the microphone icon in the top-right corner of the keyboard toolbar. The icon will turn **red** to indicate recording is in progress.

2. **Stop Recording**: Tap the microphone icon again to stop recording. The icon will turn **orange/amber** while the audio is being transcribed.

3. **Text Insertion**: Once transcription is complete, the text will be automatically inserted into the current text field.

## Visual Indicators

| State | Microphone Icon Color |
|-------|----------------------|
| Idle (ready) | Gray (default) |
| Recording | Red |
| Transcribing | Orange/Amber |

## Troubleshooting

### "OpenAI API key not configured"
- Make sure you've entered your API key in Settings > Advanced > OpenAI API Key

### "Microphone permission required"
- Go to Android Settings > Apps > HeliBoard > Permissions
- Enable the Microphone permission

### "API request failed"
- Check your internet connection
- Verify your API key is correct and has not expired
- Ensure you have credits in your OpenAI account

### "No speech detected"
- Speak clearly and close to the microphone
- Reduce background noise
- Try recording for a longer duration

## API Costs

The Whisper API charges based on audio duration:
- Current pricing: ~$0.006 per minute of audio

Check [OpenAI's pricing page](https://openai.com/pricing) for the most up-to-date information.

## Privacy

- Audio recordings are sent to OpenAI's servers for transcription
- Recordings are not stored locally after transcription
- Temporary audio files are deleted immediately after use
- OpenAI's data usage policies apply to the transcription service

## Language Support

The Whisper API automatically detects the spoken language. For best results, the keyboard will send the current keyboard language as a hint to the API.

Whisper supports 50+ languages including:
- English, Spanish, French, German, Italian, Portuguese
- Chinese, Japanese, Korean
- Arabic, Hindi, Russian
- And many more

## Technical Details

- Audio Format: WAV (PCM 16-bit, 16kHz mono)
- API Endpoint: `https://api.openai.com/v1/audio/transcriptions`
- Model: `whisper-1` (OpenAI's Whisper model)
