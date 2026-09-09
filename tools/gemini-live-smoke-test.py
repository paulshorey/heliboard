#!/usr/bin/env python3
"""Verify the Gemini Live transcription wire protocol HeliBoard speaks.

The Android client in `app/src/main/java/helium314/keyboard/latin/voice/
GeminiTranscriptionClient.kt` talks to the Live API over a raw WebSocket, so the
only way to confirm the exact `setup` schema, the audio framing and the shape of
the transcription responses is to run them against the real endpoint. This script
sends the same payloads the app sends, at each of the app's setup tiers, and
prints what the server does with them.

Usage:

    export GEMINI_API_KEY=...
    # Check the key and that the model is reachable, then probe each setup tier:
    tools/gemini-live-smoke-test.py --probe-setup

    # Full run: stream a WAV file and print interim + finalized transcripts.
    tools/gemini-live-smoke-test.py --audio speech.wav

The WAV file must be 16-bit PCM, 16 kHz, mono. Convert anything else with:

    ffmpeg -i input.m4a -ar 16000 -ac 1 -c:a pcm_s16le speech.wav

Requires the `websockets` package (`pip install websockets`).
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request
import wave

MODEL = "gemini-3.5-transcribe-live"
MODEL_RESOURCE_NAME = f"models/{MODEL}"
HOST = "generativelanguage.googleapis.com"
WS_PATH = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
REST_BASE = f"https://{HOST}/v1beta"

SAMPLE_RATE = 16000
CHANNELS = 1
SAMPLE_WIDTH_BYTES = 2
# 100 ms per chunk, matching VoiceRecorder's read interval and Google's guidance.
CHUNK_MS = 100
CHUNK_BYTES = SAMPLE_RATE * SAMPLE_WIDTH_BYTES * CHUNK_MS // 1000

# Kept in sync with GeminiTranscriptionClient.SYSTEM_INSTRUCTION.
SYSTEM_INSTRUCTION = (
    "You are transcribing dictation typed into a mobile keyboard. "
    "Transcribe only what the speaker says, with natural sentence "
    "structure, capitalization and punctuation. Prefer waiting for "
    "a complete phrase over guessing a word. Never add commentary, "
    "answers, translations or text the speaker did not say."
)

SETUP_TIERS = ("FULL", "NO_SYSTEM_INSTRUCTION", "NO_REALTIME_CONFIG", "MINIMAL")


def build_setup(tier: str, language_codes, vocabulary, silence_ms: int) -> dict:
    """Mirror GeminiTranscriptionClient.buildSetupMessage for the given tier."""
    transcription = {"languageCodes": list(language_codes)}
    if tier != "MINIMAL":
        transcription["mode"] = "SMART"
        if vocabulary:
            transcription["customVocabulary"] = list(vocabulary)

    setup = {
        "model": MODEL_RESOURCE_NAME,
        "generationConfig": {"responseModalities": ["TEXT"]},
        # Sibling of generationConfig, never nested inside it.
        "inputAudioTranscription": transcription,
    }
    if tier in ("FULL", "NO_SYSTEM_INSTRUCTION"):
        setup["realtimeInputConfig"] = {
            "automaticActivityDetection": {
                "disabled": False,
                "startOfSpeechSensitivity": "START_SENSITIVITY_HIGH",
                "prefixPaddingMs": 300,
                "endOfSpeechSensitivity": "END_SENSITIVITY_LOW",
                "silenceDurationMs": silence_ms,
            }
        }
    if tier == "FULL":
        setup["systemInstruction"] = {"parts": [{"text": SYSTEM_INSTRUCTION}]}
    return {"setup": setup}


def check_key(api_key: str) -> int:
    """Confirm the key works and that the Live transcription model is listed."""
    request = urllib.request.Request(
        f"{REST_BASE}/models?pageSize=1000",
        headers={"x-goog-api-key": api_key},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", "replace")
        print(f"FAIL  ListModels returned HTTP {error.code}\n{body}", file=sys.stderr)
        return 1
    except OSError as error:
        print(f"FAIL  could not reach {HOST}: {error}", file=sys.stderr)
        return 1

    names = [model.get("name", "") for model in payload.get("models", [])]
    print(f"OK    API key accepted ({len(names)} models visible)")
    if MODEL_RESOURCE_NAME in names:
        print(f"OK    {MODEL} is available to this key")
        return 0
    live_models = [name for name in names if "live" in name or "transcribe" in name]
    print(f"WARN  {MODEL} is not in ListModels output.")
    print("      Live/transcribe models visible to this key:")
    for name in sorted(live_models) or ["      (none)"]:
        print(f"        {name}")
    return 0


def read_pcm(path: str) -> bytes:
    with wave.open(path, "rb") as wav:
        if wav.getnchannels() != CHANNELS:
            raise SystemExit(f"{path}: need mono, got {wav.getnchannels()} channels")
        if wav.getsampwidth() != SAMPLE_WIDTH_BYTES:
            raise SystemExit(f"{path}: need 16-bit PCM, got {wav.getsampwidth() * 8}-bit")
        if wav.getframerate() != SAMPLE_RATE:
            raise SystemExit(f"{path}: need {SAMPLE_RATE} Hz, got {wav.getframerate()} Hz")
        return wav.readframes(wav.getnframes())


async def run_session(
    api_key: str,
    tier: str,
    pcm: bytes,
    language_codes,
    vocabulary,
    silence_ms: int,
    realtime: bool,
) -> bool:
    """Open one session at `tier`. Returns True when the setup was accepted."""
    import websockets

    url = f"wss://{HOST}{WS_PATH}?key={api_key}"
    setup = build_setup(tier, language_codes, vocabulary, silence_ms)
    print(f"\n=== setup tier {tier}")
    print(json.dumps(setup, indent=2, sort_keys=True))

    accepted = False
    finals: list[str] = []
    try:
        async with websockets.connect(url, max_size=None, ping_interval=20) as socket:
            await socket.send(json.dumps(setup))

            async def read_messages() -> None:
                nonlocal accepted
                async for raw in socket:
                    message = json.loads(raw)
                    if "setupComplete" in message:
                        accepted = True
                        print("OK    setupComplete")
                        continue
                    content = message.get("serverContent") or {}
                    interim = (content.get("interimInputTranscription") or {}).get("text")
                    if interim:
                        print(f"      [interim] {interim}")
                    final = (content.get("inputTranscription") or {}).get("text")
                    if final:
                        finals.append(final)
                        print(f"OK    [final]   {final}")
                    if content.get("turnComplete"):
                        print("      [turnComplete]")
                    model_turn = content.get("modelTurn")
                    if model_turn:
                        print(f"WARN  unexpected modelTurn: {json.dumps(model_turn)}")
                    if "goAway" in message:
                        print(f"WARN  goAway: {json.dumps(message['goAway'])}")
                    if "error" in message:
                        print(f"FAIL  error: {json.dumps(message['error'])}")

            reader = asyncio.create_task(read_messages())

            deadline = time.monotonic() + 15
            while not accepted and not reader.done() and time.monotonic() < deadline:
                await asyncio.sleep(0.05)
            if not accepted:
                print("FAIL  no setupComplete before timeout")
                reader.cancel()
                return False

            if pcm:
                sent = 0
                for offset in range(0, len(pcm), CHUNK_BYTES):
                    chunk = pcm[offset:offset + CHUNK_BYTES]
                    await socket.send(json.dumps({
                        "realtimeInput": {
                            "audio": {
                                "data": base64.b64encode(chunk).decode("ascii"),
                                "mimeType": f"audio/pcm;rate={SAMPLE_RATE}",
                            }
                        }
                    }))
                    sent += len(chunk)
                    if realtime:
                        await asyncio.sleep(CHUNK_MS / 1000)
                print(f"      sent {sent} bytes of PCM "
                      f"({sent / (SAMPLE_RATE * SAMPLE_WIDTH_BYTES):.1f}s)")

                # Hybrid VAD: finalize the turn instead of waiting out the
                # server's silence window, then keep reading for the tail.
                await socket.send(json.dumps({"realtimeInput": {"audioStreamEnd": True}}))
                print("      sent audioStreamEnd")
                await asyncio.sleep(8)

            reader.cancel()
    except Exception as error:  # noqa: BLE001 - report whatever the server did
        detail = getattr(error, "reason", "") or str(error)
        code = getattr(error, "code", None)
        print(f"FAIL  socket closed (code={code}): {detail}")
        return False

    if pcm and not finals:
        print("WARN  session accepted but no finalized transcript arrived")
    return accepted


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--api-key", default=os.environ.get("GEMINI_API_KEY", ""),
                        help="defaults to $GEMINI_API_KEY")
    parser.add_argument("--audio", help="16 kHz mono 16-bit PCM WAV file to transcribe")
    parser.add_argument("--probe-setup", action="store_true",
                        help="open one session per setup tier to see which the server accepts")
    parser.add_argument("--tier", choices=SETUP_TIERS, default="FULL",
                        help="setup tier for a single run (default: FULL)")
    parser.add_argument("--language", action="append", default=[],
                        help="BCP-47 language code; repeat for several, omit for auto-detect")
    parser.add_argument("--vocabulary", action="append", default=[],
                        help="customVocabulary term; repeat for several")
    parser.add_argument("--silence-ms", type=int, default=1500,
                        help="automaticActivityDetection.silenceDurationMs (default: 1500)")
    parser.add_argument("--realtime", action="store_true",
                        help="pace audio at wall-clock speed instead of sending as fast as possible")
    args = parser.parse_args()

    if not args.api_key:
        print("Set GEMINI_API_KEY or pass --api-key.", file=sys.stderr)
        return 2

    if check_key(args.api_key) != 0:
        return 1

    pcm = read_pcm(args.audio) if args.audio else b""
    tiers = SETUP_TIERS if args.probe_setup else (args.tier,)

    results = {}
    for tier in tiers:
        results[tier] = asyncio.run(run_session(
            api_key=args.api_key,
            tier=tier,
            pcm=pcm,
            language_codes=args.language,
            vocabulary=args.vocabulary,
            silence_ms=args.silence_ms,
            realtime=args.realtime,
        ))

    print("\n=== summary")
    for tier, accepted in results.items():
        print(f"{'OK   ' if accepted else 'FAIL '} setup tier {tier}")
    print("\nThe app starts at the highest accepted tier and degrades on close "
          "code 1007, so any FAIL above is handled at runtime — but a FAIL for "
          "FULL means the accuracy knobs are not actually reaching the model.")
    return 0 if any(results.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
