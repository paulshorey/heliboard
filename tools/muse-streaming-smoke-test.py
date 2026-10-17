#!/usr/bin/env python3
"""Opt-in Muse protocol probe. No credentials, audio or transcripts are saved by default."""
import argparse
import asyncio
import json
import os
import time
import wave


def read_audio(path):
    with wave.open(path, "rb") as audio:
        rate = audio.getframerate()
        if audio.getnchannels() != 1 or audio.getsampwidth() != 2 or rate not in (16000, 24000) or audio.getcomptype() != "NONE":
            raise ValueError("Audio must be mono PCM16 WAV at 16000 or 24000 Hz")
        if audio.getnframes() / rate > 55 * 60:
            raise ValueError("Use a recording shorter than 55 minutes")
        return rate, audio.readframes(audio.getnframes())


async def run(args):
    try:
        import websockets
    except ImportError:
        raise ValueError("Install the optional websockets package in a virtual environment first") from None
    key = (os.environ.get("MUSE_API_KEY") or os.environ.get("MODEL_API_KEY") or "").strip().removeprefix("Bearer ")
    if not key:
        raise ValueError("Set MUSE_API_KEY or MODEL_API_KEY in your environment")
    rate, pcm = read_audio(args.audio)
    handshake = {"authorization": {"accessToken": f"Bearer {key}"}, "model": "muse-voice-transcribe-1.0",
                 "audioEncoding": f"PCM_{rate // 1000}KHZ", "mode": "DIARIZATION",
                 "partialMode": "CUMULATIVE", "emitAudioProgress": True}
    if args.language:
        handshake["languageBias"] = args.language
    if args.keyword:
        handshake["keywords"] = args.keyword
    async with websockets.connect("wss://api.meta.ai/v1/asr/realtime", open_timeout=12, close_timeout=5) as ws:
        await ws.send(json.dumps(handshake))
        ack = json.loads(await asyncio.wait_for(ws.recv(), timeout=12))
        if not ack.get("sessionId") or "type" in ack:
            raise ValueError("Muse rejected the handshake; verify key, billing and model access")
        print("Handshake accepted; DIARIZATION. Streaming private audio at real-time speed.")
        turns = {}
        active = None
        selected = None
        counts = {"completed_turns": 0, "selected_turns": 0, "filtered_turns": 0}
        started = time.monotonic()
        first_final_ms = None

        async def receive():
            nonlocal active, selected, first_final_ms
            async for raw in ws:
                event = json.loads(raw)
                kind = event.get("type")
                if kind == "error":
                    raise ValueError("Muse returned a service error; verify account/configuration")
                if kind == "speechStart":
                    active = event["turnId"]
                    turns[active] = {"speaker": None, "text": None, "conflict": False}
                elif kind == "speaker":
                    if active not in turns:
                        raise ValueError("Speaker event has no active turn")
                    turn = turns[active]
                    if turn["speaker"] not in (None, event["label"]):
                        turn["conflict"] = True
                    turn["speaker"] = event["label"]
                elif kind == "speechComplete":
                    if event["turnId"] not in turns:
                        raise ValueError("Completion has no matching speech start")
                    turns[event["turnId"]]["text"] = event["transcript"]
                while turns:
                    turn_id, turn = next(iter(turns.items()))
                    if turn["text"] is None or turn["conflict"] or (turn["text"].strip() and not turn["speaker"]):
                        break
                    if turn["text"].strip():
                        selected = selected or turn["speaker"]
                        keep = selected == turn["speaker"]
                        counts["completed_turns"] += 1
                        counts["selected_turns" if keep else "filtered_turns"] += 1
                        if first_final_ms is None:
                            first_final_ms = round((time.monotonic() - started) * 1000)
                        print(f"Completed turn {turn_id}: speaker={turn['speaker']} selected={keep} chars={len(turn['text'])}")
                        if args.show_transcripts:
                            print(turn["text"])
                    del turns[turn_id]
            if turns:
                raise ValueError("Session ended with unresolved turns or speaker attribution")

        receiver = asyncio.create_task(receive())
        frame_bytes = rate * 2 // 10
        try:
            for offset in range(0, len(pcm), frame_bytes):
                if receiver.done():
                    await receiver
                    raise ValueError("Muse closed before all audio was sent")
                await ws.send(pcm[offset:offset + frame_bytes])
                delay = started + min(offset + frame_bytes, len(pcm)) / (rate * 2) - time.monotonic()
                if delay > 0:
                    await asyncio.sleep(delay)
            await ws.send(json.dumps({"type": "endStream"}))
            stop_at = time.monotonic()
            await asyncio.wait_for(receiver, timeout=60)
            if ws.close_code != 1000:
                raise ValueError("Muse did not close normally")
            print(json.dumps({**counts, "first_final_ms": first_final_ms,
                              "stop_drain_ms": round((time.monotonic() - stop_at) * 1000)}))
        finally:
            if not receiver.done():
                receiver.cancel()
            await asyncio.gather(receiver, return_exceptions=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audio", required=True, help="Private mono PCM16 WAV at 16 or 24 kHz")
    parser.add_argument("--language", action="append", help="Language name, e.g. English; repeat for several")
    parser.add_argument("--keyword", action="append", help="Recognition vocabulary hint; repeat for several")
    parser.add_argument("--show-transcripts", action="store_true", help="Print private text to this terminal; off by default")
    args = parser.parse_args()
    try:
        asyncio.run(run(args))
    except (ValueError, OSError) as error:
        # Locally authored errors contain neither the key nor raw service payloads.
        if isinstance(error, ValueError):
            parser.exit(1, f"Probe failed: {error}\n")
        parser.exit(1, "Probe failed: file or network access unavailable.\n")
    except Exception:
        parser.exit(1, "Probe failed: connection, protocol or service failure; no credentials or payload logged.\n")


if __name__ == "__main__":
    main()
