# Active voice data flow

See `docs/grok-transcription.md` for current behavior and `latin/voice/AGENTS.md`
for implementation constraints. The flow is recorder → paced binary Grok socket
→ attributed utterance assembler → bounded manager FIFO → verified InputConnection
commit. Provisional text is never inserted. Speaker filtering precedes insertion;
all recorded voices still reach the provider. Ordinary local silence never forces
Smart Turn. Explicit Stop drains; cancellation invalidates all remaining work.

The former Gemini flow is documented as historical in
`docs/gemini-transcription.md` and its inactive client/tests remain available.
