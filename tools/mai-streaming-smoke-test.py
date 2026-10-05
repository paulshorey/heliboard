#!/usr/bin/env python3
"""Credentialed MAI-Transcribe-2-Streaming Speech SDK check with PCM16 input."""
import argparse
import os
from pathlib import Path
import sys
import threading


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pcm', type=Path, required=True)
    parser.add_argument('--language', help='Optional BCP-47 recognition hint, e.g. en-US')
    args = parser.parse_args()
    key = os.environ.get('SPEECH_KEY', '').strip()
    region = os.environ.get('SPEECH_REGION', '').strip().lower()
    if not key or region not in {'centralus', 'swedencentral', 'southeastasia'}:
        parser.error('Set SPEECH_KEY and a supported SPEECH_REGION outside the repository.')
    data = args.pcm.read_bytes()
    if not data or len(data) % 2 or data.startswith(b'RIFF'):
        parser.error('Input must be nonempty headerless mono 16 kHz PCM16.')
    import azure.cognitiveservices.speech as speechsdk
    if speechsdk.__version__ != '1.52.0':
        parser.error('Install azure-cognitiveservices-speech==1.52.0 for this check.')
    config = speechsdk.SpeechConfig(subscription=key,
        endpoint=f'wss://{region}.stt.speech.microsoft.com/speech/universal/v2')
    config.model = 'MAI-Transcribe-2-Streaming'
    if args.language:
        config.speech_recognition_language = args.language
    stream = speechsdk.audio.PushAudioInputStream(
        stream_format=speechsdk.audio.AudioStreamFormat(samples_per_second=16000, bits_per_sample=16, channels=1))
    recognizer = speechsdk.SpeechRecognizer(speech_config=config,
        audio_config=speechsdk.audio.AudioConfig(stream=stream))
    done = threading.Event()
    errors = []
    def recognized(event):
        if event.result.reason == speechsdk.ResultReason.RecognizedSpeech:
            print('Final:', event.result.text, flush=True)
    def canceled(event):
        if event.cancellation_details.reason == speechsdk.CancellationReason.Error:
            # Do not expose raw service details, which can contain secrets or transcript text.
            errors.append(str(event.cancellation_details.code))
        done.set()
    recognizer.recognized.connect(recognized)
    recognizer.canceled.connect(canceled)
    recognizer.session_stopped.connect(lambda event: done.set())
    input_closed = False
    try:
        recognizer.start_continuous_recognition_async().get()
        for start in range(0, len(data), 3200):
            if done.is_set():
                break
            chunk = data[start:start + 3200]
            stream.write(chunk)
            done.wait(len(chunk) / 32000)
        stream.close()
        input_closed = True
        if not done.wait(60):
            print('Azure Speech finalization timed out.', file=sys.stderr)
            return 1
        if errors:
            print('Azure Speech failed:', ', '.join(errors), file=sys.stderr)
            return 1
        return 0
    finally:
        if not input_closed:
            stream.close()
        recognizer.stop_continuous_recognition_async().get()


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception:
        print('Speech SDK check failed. Verify the input, credentials, SDK version, and connection.', file=sys.stderr)
        sys.exit(1)
