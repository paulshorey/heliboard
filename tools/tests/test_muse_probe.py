"""Muse protocol, latency metrics and secret handling without a provider account."""
import asyncio
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import wave

spec = importlib.util.spec_from_file_location("muse_probe", Path(__file__).parents[1] / "muse-streaming-smoke-test.py")
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def event(kind, turn=1, text="Hello.", label="A", progress=0):
    return dict(type=kind, turnId=turn, transcript=text, label=label, audioProcessedMs=progress)


class TurnAuditTest(unittest.TestCase):
    def test_diarization_selects_start_order_not_completion_order(self):
        audit = probe.TurnAudit("DIARIZATION")
        for item in [event("speechStart", 7), event("speaker", label="A"), event("speechEnd", 7),
                     event("speechStart", 2), event("speaker", label="B"), event("speechEnd", 2)]:
            audit.accept(item, 0)
        self.assertFalse(audit.accept(event("speechComplete", 2, "Guest."), 10)[0])
        ready, _ = audit.accept(event("speechComplete", 7, "Mine."), 20)
        self.assertEqual([r[1]["selected"] for r in ready], [True, False])
        self.assertEqual([r[0] for r in ready], ["Mine.", "Guest."])
        self.assertEqual(ready[1][1]["final_to_release_ms"], 10)

    def test_endpointing_keeps_all_completed_turns_without_labels(self):
        audit = probe.TurnAudit("ENDPOINTING")
        audit.accept(event("speechStart"), 0)
        self.assertFalse(audit.accept(event("transcript", text="draft?"), 10)[0])
        self.assertFalse(audit.accept(event("speechComplete"), 20)[0])
        ready, _ = audit.accept(event("speechEnd"), 30)
        self.assertTrue(ready[0][1]["selected"])
        self.assertEqual(audit.first_punctuation_ms, 10)
        self.assertEqual(audit.first_final_ms, 30)
        self.assertFalse(audit.accept(event("speechComplete"), 40)[0])
        audit.verify_finished()

    def test_missing_or_conflicting_attribution_never_releases_words(self):
        for labels in ([], ["A", "B"]):
            audit = probe.TurnAudit("DIARIZATION")
            audit.accept(event("speechStart"), 0)
            for label in labels:
                audit.accept(event("speaker", label=label), 0)
            audit.accept(event("speechEnd"), 100)
            self.assertFalse(audit.accept(event("speechComplete"), 200)[0])
            with self.assertRaisesRegex(ValueError, "unresolved"):
                audit.verify_finished()

    def test_progress_cannot_extend_ended_turn_deadline(self):
        audit = probe.TurnAudit("DIARIZATION")
        audit.accept(event("speechStart"), 0)
        audit.accept(event("transcript"), 100)
        audit.accept(event("speechEnd"), 200)
        audit.accept(event("audioProgress", progress=30_000), 30_000)
        with self.assertRaisesRegex(ValueError, "30 seconds"):
            audit.receive_timeout(30_201)

    def test_unknowns_are_ignored_but_known_invalid_events_fail(self):
        audit = probe.TurnAudit("ENDPOINTING")
        self.assertEqual(audit.accept(dict(type="future"), 0), ([], None))
        with self.assertRaises(ValueError):
            audit.accept(event("speechStart", turn="private payload"), 0)

    def test_only_empty_final_can_flush_after_settled_turn(self):
        for mode in probe.MODES:
            audit = probe.TurnAudit(mode)
            audit.accept(event("speechStart"), 0)
            if mode == "DIARIZATION":
                audit.accept(event("speaker"), 0)
            audit.accept(event("speechEnd"), 10)
            audit.accept(event("speechComplete"), 20)
            flush = dict(event("transcript", text=""), final=True)
            self.assertFalse(audit.accept(flush, 21)[0])
            for invalid in (dict(flush, transcript="Unattributed."), dict(flush, final=False)):
                with self.assertRaisesRegex(ValueError, "no active speech turn"):
                    audit.accept(invalid, 22)
            audit.verify_finished()

    def test_turn_bound_and_normal_silence_close(self):
        audit = probe.TurnAudit("DIARIZATION")
        for i in range(64):
            audit.accept(event("speechStart", i), 0)
            audit.accept(event("speechEnd", i), 0)
        audit.verify_finished()
        with self.assertRaisesRegex(ValueError, "queue is full"):
            audit.accept(event("speechStart", 65), 0)

    def test_progress_is_monotonic_even_for_late_completions(self):
        audit = probe.TurnAudit("ENDPOINTING")
        audit.accept(event("speechStart", progress=100), 0)
        audit.accept(event("audioProgress", progress=500), 1)
        audit.accept(event("speechEnd", progress=100), 2)
        self.assertEqual(audit.processed_ms, 500)


class ProbeInputTest(unittest.TestCase):
    def test_env_file_is_literal_and_environment_takes_priority(self):
        with tempfile.TemporaryDirectory() as folder:
            p = Path(folder) / ".env"
            p.write_text('export MUSE_API_KEY="Bearer file-key"\nUNRELATED=$(not-executed)\n')
            with patch.dict(os.environ, {}, clear=True):
                self.assertEqual(probe.read_key(p), "file-key")
            with patch.dict(os.environ, dict(MUSE_API_KEY="environment-key"), clear=True):
                self.assertEqual(probe.read_key(p), "environment-key")
            with patch.dict(os.environ, dict(MODEL_API_KEY="model-environment-key"), clear=True):
                self.assertEqual(probe.read_key(p), "model-environment-key")
            with patch.dict(os.environ, dict(MUSE_API_KEY=""), clear=True):
                self.assertEqual(probe.read_key(p), "file-key")

    def test_audio_format_validation_rejects_empty_stereo_and_wrong_rate(self):
        with tempfile.TemporaryDirectory() as folder:
            p = Path(folder) / "test.wav"
            for channels, rate, frames in ((1, 24000, 0), (2, 24000, 10), (1, 48000, 10)):
                with wave.open(str(p), "wb") as w:
                    w.setparams((channels, 2, rate, 0, "NONE", "not compressed"))
                    w.writeframes(b"\0" * frames * channels * 2)
                with self.assertRaises(ValueError):
                    probe.read_audio(p)


class FakeSocket:
    def __init__(self):
        self.messages = asyncio.Queue()
        self.sent = []
        self.close_code = None
        self.mode = None

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False

    async def recv(self):
        return '{"sessionId":"private-session"}'

    async def send(self, data):
        self.sent.append(data)
        if isinstance(data, bytes):
            await self.messages.put(event("audioProgress", progress=100))
        elif json.loads(data).get("type") == "endStream":
            for i in (1, 2):
                items = [event("speechStart", i)]
                if self.mode == "DIARIZATION":
                    items.append(event("speaker", label="A" if i == 1 else "B"))
                items.extend([event("speechEnd", i), event("speechComplete", i, "private transcript?")])
                for item in items:
                    await self.messages.put(item)
            await self.messages.put(dict(event("transcript", text=""), final=True))
            await self.messages.put(None)
        else:
            self.mode = json.loads(data)["mode"]

    async def __aiter__(self):
        while True:
            item = await self.messages.get()
            if item is None:
                self.close_code = 1000
                return
            yield json.dumps(item)


class ProbeSessionTest(unittest.IsolatedAsyncioTestCase):
    async def test_both_modes_drain_after_eof_with_safe_default_output(self):
        args = SimpleNamespace(language=["English"], keyword=None, timings=True, show_transcripts=False)
        for mode, expected in (("DIARIZATION", 1), ("ENDPOINTING", 2)):
            socket = FakeSocket()
            fake = SimpleNamespace(connect=lambda *a, **kw: socket)
            output = io.StringIO()
            with patch.dict("sys.modules", {"websockets": fake}), contextlib.redirect_stdout(output):
                summary = await probe.run_session(args, mode, "private-key", 24000, bytes(4800))
            self.assertEqual(summary["selected_turns"], expected)
            self.assertEqual(json.loads(socket.sent[0])["authorization"]["accessToken"], "Bearer private-key")
            self.assertEqual(json.loads(socket.sent[-1]), dict(type="endStream"))
            self.assertTrue(any(isinstance(frame, bytes) for frame in socket.sent))
            self.assertNotIn("private", output.getvalue())
            self.assertEqual(socket.close_code, 1000)


if __name__ == "__main__":
    unittest.main()
