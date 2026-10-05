#!/usr/bin/env python3
"""Stream headerless mono 16 kHz PCM16 to an Azure MAI deployment.

Requires websockets. Credentials are read only from AZURE_MAI_* environment variables.
"""
import argparse
import asyncio
import base64
import json
import os
from pathlib import Path
from urllib.parse import urlsplit


async def transcribe(args):
    from websockets.asyncio.client import connect

    endpoint = urlsplit(os.environ.get("AZURE_MAI_ENDPOINT", "").strip())
    if (endpoint.scheme not in {"https", "wss"} or not endpoint.hostname
            or endpoint.username is not None or endpoint.password is not None
            or endpoint.path not in {"", "/"} or endpoint.query or endpoint.fragment):
        raise ValueError("AZURE_MAI_ENDPOINT must be an HTTPS/WSS resource root URL")
    key = os.environ.get("AZURE_MAI_API_KEY", "").strip()
    deployment = os.environ.get("AZURE_MAI_DEPLOYMENT_NAME", "").strip()
    if not key or not deployment:
        raise ValueError("Set AZURE_MAI_API_KEY and AZURE_MAI_DEPLOYMENT_NAME")
    pcm = args.pcm.read_bytes()
    if not pcm or len(pcm) % 2 or pcm.startswith(b"RIFF"):
        raise ValueError("Input must be nonempty headerless mono 16 kHz PCM16")
    if len(pcm) > 32000 * 3600:
        raise ValueError("Input exceeds the one-hour session limit")
    url = endpoint._replace(scheme="wss", path="/mai/v1/realtime", query="intent=transcription").geturl()
    async with connect(url, additional_headers={"api-key": key}, max_size=2**20) as ws:
        async def event():
            result = json.loads(await ws.recv())
            if result.get("type") in {"error", "conversation.item.input_audio_transcription.failed"}:
                # Server errors can echo secrets. Do not print raw errors.
                raise RuntimeError("MAI rejected the session or audio; verify the resource and deployment")
            return result

        async def wait_for(expected):
            async with asyncio.timeout(30):
                while (await event()).get("type") != expected:
                    pass

        await wait_for("session.created")
        await ws.send(json.dumps({"type": "session.update", "session": {"type": "transcription",
            "audio": {"input": {"format": {"type": "audio/pcm", "rate": 16000},
                "transcription": {"model": deployment, "language": args.language or None},
                "turn_detection": None, "noise_reduction": None}}}}))
        await wait_for("session.updated")
        pending = 0
        all_done = asyncio.Event()
        all_done.set()

        async def commit():
            nonlocal pending
            pending += 1
            all_done.clear()
            await ws.send(json.dumps({"type": "input_audio_buffer.commit"}))

        async def upload():
            since_commit = 0
            for offset in range(0, len(pcm), 3200):
                chunk = pcm[offset:offset + 3200]
                await ws.send(json.dumps({"type": "input_audio_buffer.append", "audio": base64.b64encode(chunk).decode("ascii")}))
                since_commit += len(chunk)
                if since_commit >= 32000 * 3:
                    await commit()
                    since_commit = 0
                await asyncio.sleep(len(chunk) / 32000)
            if since_commit:
                await commit()
            async with asyncio.timeout(60):
                await all_done.wait()

        async def receive():
            nonlocal pending
            while True:
                result = await event()
                if result.get("type") == "conversation.item.input_audio_transcription.completed":
                    print(result.get("transcript", ""), flush=True)
                    pending -= 1
                    if pending == 0:
                        all_done.set()

        # A service error must interrupt the sender, including its final drain wait.
        async with asyncio.TaskGroup() as group:
            receiver = group.create_task(receive())
            await upload()
            receiver.cancel()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pcm", type=Path, required=True)
    parser.add_argument("--language", default="", help="Optional language hint, e.g. en")
    args = parser.parse_args()
    try:
        asyncio.run(transcribe(args))
    except KeyboardInterrupt:
        parser.exit(130, "Stopped.\n")
    except Exception:
        # Avoid library exception strings that may include request headers or transcripts.
        parser.exit(1, "MAI smoke test failed. Check input, connectivity, credentials, deployment, and finalization timeout.\n")


if __name__ == "__main__":
    main()
