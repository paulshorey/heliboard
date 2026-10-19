#!/usr/bin/env python3
"""Opt-in Muse latency probe. No audio, credentials or transcripts are saved by default."""
import argparse
import asyncio
import json
import os
from pathlib import Path
import time
import wave

MODES = ("DIARIZATION", "ENDPOINTING")
ENDPOINT = "wss://api.meta.ai/v1/asr/realtime"
FINAL_TIMEOUT_MS = 30_000


def read_audio(path):
    with wave.open(str(path), "rb") as audio:
        rate = audio.getframerate()
        if audio.getnchannels() != 1 or audio.getsampwidth() != 2 or rate not in (16000, 24000) or audio.getcomptype() != "NONE":
            raise ValueError("Audio must be mono PCM16 WAV at 16000 or 24000 Hz")
        if not audio.getnframes() or audio.getnframes() / rate > 55 * 60:
            raise ValueError("Use a nonempty recording shorter than 55 minutes")
        return rate, audio.readframes(audio.getnframes())


def read_key(env_file=None):
    values = dict(os.environ)
    if env_file and not (values.get("MUSE_API_KEY") or values.get("MODEL_API_KEY")):
        # Literal assignments only. Never execute a shell file or expand substitutions.
        for line in Path(env_file).read_text().splitlines():
            name, sep, value = line.strip().removeprefix("export ").partition("=")
            if sep and name.strip() in ("MUSE_API_KEY", "MODEL_API_KEY"):
                value = value.strip()
                if len(value) >= 2 and value[0] == value[-1] and value[0] in (chr(34), chr(39)):
                    value = value[1:-1]
                values[name.strip()] = value
    key = (values.get("MUSE_API_KEY") or values.get("MODEL_API_KEY") or "").strip().removeprefix("Bearer ")
    if not key:
        raise ValueError("Set MUSE_API_KEY or MODEL_API_KEY, or pass --env-file")
    return key


def require(condition, message):
    if not condition:
        raise ValueError(message)


class TurnAudit:
    """Matches the app's start-order/attribution gate; metrics contain no payloads."""
    def __init__(self, mode):
        require(mode in MODES, "Unsupported probe mode")
        self.mode = mode
        self.turns = {}
        self.settled = set()
        self.retired_empty = set()
        self.active = None
        self.selected = None
        self.processed_ms = 0
        self.first_partial_ms = None
        self.first_punctuation_ms = None
        self.first_final_ms = None
        self.counts = dict(completed_turns=0, selected_turns=0, filtered_turns=0)

    def accept(self, event, elapsed_ms):
        kind = event.get("type")
        if kind not in ("speechStart", "speechEnd", "speechComplete", "transcript", "speaker", "audioProgress"):
            return [], None
        progress = event.get("audioProcessedMs")
        require(type(progress) is int and progress >= 0, "Invalid audio processing progress")
        self.processed_ms = max(self.processed_ms, progress)
        turn_id = event.get("turnId") if kind in ("speechStart", "speechEnd", "speechComplete") else self.active
        if kind in ("speechStart", "speechEnd", "speechComplete"):
            require(type(turn_id) is int, "Invalid speech turn identity")
        if kind == "speechStart":
            require(turn_id not in self.turns and turn_id not in self.settled, "Repeated speech start")
            require(self.active not in self.turns or self.turns[self.active]["end_ms"] is not None,
                    "Overlapping speech boundaries")
            require(len(self.turns) < 64 and len(self.settled) < 8192, "Voice turn queue is full")
            self.turns[turn_id] = dict(speaker=None, conflict=False, text=None, words=False,
                                      partial_ms=None, punctuation_ms=None, end_ms=None,
                                      complete_ms=None, speaker_ms=None)
            self.active = turn_id
        turn = self.turns.get(turn_id)
        text = None
        if kind in ("transcript", "speechComplete"):
            text = event.get("transcript")
            require(isinstance(text, str), "Invalid transcript payload")
            if kind == "speechComplete":
                require(turn_id not in self.retired_empty or not text.strip(),
                        "Words arrived after an empty turn expired")
        if kind in ("transcript", "speaker"):
            # A live Muse session can flush an empty final after its last turn.
            empty_flush = kind == "transcript" and not text.strip() and event.get("final") is True
            require(turn is not None or empty_flush, "Event has no active speech turn")
        if kind == "transcript" and turn is not None:
            if text.strip():
                turn["words"] = True
                if turn["partial_ms"] is None:
                    turn["partial_ms"] = elapsed_ms
                if self.first_partial_ms is None:
                    self.first_partial_ms = elapsed_ms
            if any(c in text for c in ".!?"):
                if turn["punctuation_ms"] is None:
                    turn["punctuation_ms"] = elapsed_ms
                if self.first_punctuation_ms is None:
                    self.first_punctuation_ms = elapsed_ms
        elif kind == "speaker":
            require(self.mode == "DIARIZATION" and turn["end_ms"] is None, "Unexpected speaker event")
            label = event.get("label")
            require(isinstance(label, str) and bool(label.strip()), "Invalid speaker label")
            turn["conflict"] |= turn["speaker"] not in (None, label)
            turn["speaker"] = label
            turn["speaker_ms"] = elapsed_ms
        elif kind in ("speechEnd", "speechComplete") and turn_id not in self.settled:
            require(turn is not None, "Unknown speech turn")
            if kind == "speechEnd":
                if turn["end_ms"] is None:
                    turn["end_ms"] = elapsed_ms
            else:
                require(turn["text"] in (None, text), "Completed transcript changed")
                turn["text"] = text
                turn["words"] |= bool(text.strip())
                if turn["complete_ms"] is None:
                    turn["complete_ms"] = elapsed_ms
        metadata = dict(type=kind, turn=turn_id, elapsed_ms=elapsed_ms, audio_processed_ms=progress)
        if text is not None:
            metadata.update(chars=len(text), sentence_marks=sum(c in ".!?" for c in text))
        return self.drain(elapsed_ms), metadata

    def drain(self, elapsed_ms):
        ready = []
        while self.turns:
            identity, current = next(iter(self.turns.items()))
            if current["text"] is None or current["end_ms"] is None:
                break
            nonempty = bool(current["text"].strip())
            if self.mode == "DIARIZATION":
                if current["conflict"] or (nonempty and current["speaker"] is None):
                    break
                if nonempty and self.selected is None:
                    self.selected = current["speaker"]
            keep = nonempty and (self.mode == "ENDPOINTING" or current["speaker"] == self.selected)
            if nonempty:
                self.counts["completed_turns"] += 1
                self.counts["selected_turns" if keep else "filtered_turns"] += 1
                if keep and self.first_final_ms is None:
                    self.first_final_ms = elapsed_ms
            metrics = dict(turn=identity, selected=keep, chars=len(current["text"]),
                           first_partial_ms=current["partial_ms"], first_punctuation_ms=current["punctuation_ms"],
                           speech_end_ms=current["end_ms"], complete_ms=current["complete_ms"],
                           speaker_ms=current["speaker_ms"], release_ms=elapsed_ms,
                           end_to_final_ms=current["complete_ms"] - current["end_ms"],
                           final_to_release_ms=elapsed_ms - current["complete_ms"])
            ready.append((current["text"], metrics))
            del self.turns[identity]
            self.settled.add(identity)
        return ready

    def expire_empty(self, elapsed_ms, closing=False):
        for identity, turn in list(self.turns.items()):
            if turn["end_ms"] is not None and not turn["words"] and (
                    closing or elapsed_ms - turn["end_ms"] >= FINAL_TIMEOUT_MS):
                del self.turns[identity]
                self.settled.add(identity)
                self.retired_empty.add(identity)
        return self.drain(elapsed_ms)

    def receive_timeout(self, elapsed_ms):
        deadlines = [t["end_ms"] + FINAL_TIMEOUT_MS for t in self.turns.values()
                     if t["end_ms"] is not None]
        remaining = min([30_000] + [deadline - elapsed_ms for deadline in deadlines])
        require(remaining > 0, "Muse did not complete or attribute a speech turn within 30 seconds")
        return remaining / 1000

    def verify_finished(self):
        require(not any(t["words"] for t in self.turns.values()),
                "Session ended with unresolved words or speaker attribution")


async def run_session(args, mode, key, rate, pcm):
    import websockets
    handshake = dict(authorization=dict(accessToken=f"Bearer {key}"), model="muse-voice-transcribe-1.0",
                     audioEncoding=f"PCM_{rate // 1000}KHZ", mode=mode,
                     partialMode="CUMULATIVE", emitAudioProgress=True)
    if args.language:
        handshake["languageBias"] = args.language
    if args.keyword:
        handshake["keywords"] = args.keyword
    async with websockets.connect(ENDPOINT, open_timeout=12, close_timeout=5) as ws:
        await ws.send(json.dumps(handshake))
        ack = json.loads(await asyncio.wait_for(ws.recv(), timeout=12))
        require(ack.get("sessionId") and "type" not in ack, "Muse rejected the handshake; verify key, billing and model access")
        print(f"Handshake accepted; {mode}. Streaming audio at real-time speed.")
        audit = TurnAudit(mode)
        started = time.monotonic()
        elapsed = lambda: round((time.monotonic() - started) * 1000)
        progress_changed = asyncio.Event()
        last_progress_log = -1000

        async def receive():
            nonlocal last_progress_log
            def publish(ready):
                for text, metrics in ready:
                    print(json.dumps(dict(mode=mode, phase="turn", **metrics)))
                    if args.show_transcripts:
                        print(text)
            iterator = ws.__aiter__()
            pending_read = None
            try:
                while True:
                    publish(audit.expire_empty(elapsed()))
                    if pending_read is None:
                        pending_read = asyncio.create_task(iterator.__anext__())
                    # A turn deadline must not cancel the async event iterator.
                    done, _ = await asyncio.wait({pending_read}, timeout=audit.receive_timeout(elapsed()))
                    if not done:
                        now = elapsed()
                        require(any(t["end_ms"] is not None and now - t["end_ms"] >= FINAL_TIMEOUT_MS
                                    for t in audit.turns.values()), "Muse did not respond within 30 seconds")
                        publish(audit.expire_empty(now))
                        audit.receive_timeout(now)  # Recognized missing finals still fail.
                        continue
                    try:
                        raw = pending_read.result()
                    except StopAsyncIteration:
                        break
                    finally:
                        pending_read = None
                    event = json.loads(raw)
                    if event.get("type") == "error":
                        raise ValueError("Muse returned a service error; verify account/configuration")
                    ready, metadata = audit.accept(event, elapsed())
                    progress_changed.set()
                    if args.timings and metadata:
                        if metadata["type"] != "audioProgress" or elapsed() - last_progress_log >= 1000:
                            if metadata["type"] == "audioProgress":
                                last_progress_log = elapsed()
                            print(json.dumps(dict(mode=mode, phase="event", **metadata)))
                    publish(ready)
                publish(audit.expire_empty(elapsed(), closing=True))
                audit.verify_finished()
            finally:
                if pending_read is not None:
                    pending_read.cancel()
                    await asyncio.gather(pending_read, return_exceptions=True)

        receiver = asyncio.create_task(receive())
        receiver.add_done_callback(lambda _: progress_changed.set())
        frame_bytes = rate * 2 // 10
        try:
            for offset in range(0, len(pcm), frame_bytes):
                deadline = time.monotonic() + 30
                next_ms = min(offset + frame_bytes, len(pcm)) * 1000 / (rate * 2)
                while next_ms - audit.processed_ms > 2000:
                    if receiver.done():
                        await receiver
                        raise ValueError("Muse closed before all audio was sent")
                    progress_changed.clear()
                    remaining = deadline - time.monotonic()
                    require(remaining > 0, "Muse audio processing stalled")
                    await asyncio.wait_for(progress_changed.wait(), remaining)
                if receiver.done():
                    await receiver
                    raise ValueError("Muse closed before all audio was sent")
                await ws.send(pcm[offset:offset + frame_bytes])
                delay = started + next_ms / 1000 - time.monotonic()
                if delay > 0:
                    await asyncio.sleep(delay)
            await ws.send(json.dumps(dict(type="endStream")))
            stop_at = time.monotonic()
            await asyncio.wait_for(receiver, timeout=60)
            require(ws.close_code == 1000, "Muse did not close normally")
            summary = dict(mode=mode, **audit.counts, first_partial_ms=audit.first_partial_ms,
                           first_punctuation_ms=audit.first_punctuation_ms, first_final_ms=audit.first_final_ms,
                           input_duration_ms=round(len(pcm) * 1000 / (rate * 2)),
                           stop_drain_ms=round((time.monotonic() - stop_at) * 1000))
            print(json.dumps(dict(phase="summary", **summary)))
            return summary
        finally:
            if not receiver.done():
                receiver.cancel()
            await asyncio.gather(receiver, return_exceptions=True)


async def run(args):
    try:
        import websockets  # noqa: F401
    except ImportError:
        raise ValueError("Install the optional websockets package in a virtual environment first") from None
    key = read_key(args.env_file)
    rate, pcm = read_audio(args.audio)
    for mode in MODES if args.compare_modes else (args.mode or "DIARIZATION",):
        await run_session(args, mode, key, rate, pcm)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audio", required=True, help="Private mono PCM16 WAV at 16 or 24 kHz")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--mode", choices=MODES, help="Defaults to DIARIZATION (first-speaker filter)")
    modes.add_argument("--compare-modes", action="store_true", help="Stream the same recording twice; two billable sessions")
    parser.add_argument("--env-file", help="Read a literal MUSE_API_KEY/MODEL_API_KEY assignment; environment takes priority")
    parser.add_argument("--language", action="append", help="Language name, e.g. English; repeat for several")
    parser.add_argument("--keyword", action="append", help="Recognition vocabulary hint; repeat for several")
    parser.add_argument("--timings", action="store_true", help="Print event receipt times and punctuation counts, without payloads")
    parser.add_argument("--show-transcripts", action="store_true", help="Print private text to this terminal; off by default")
    args = parser.parse_args()
    try:
        asyncio.run(run(args))
    except (ValueError, OSError) as error:
        if isinstance(error, ValueError):
            parser.exit(1, f"Probe failed: {error}\n")
        parser.exit(1, "Probe failed: file or network access unavailable.\n")
    except Exception:
        parser.exit(1, "Probe failed: connection, protocol or service failure; no credentials or payload logged.\n")


if __name__ == "__main__":
    main()
