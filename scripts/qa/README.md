# QA agents

An LLM agent uses the Android app like a real person and judges it against the promises in [personas/common.md](personas/common.md): a developer who wants to see and control everything. It works on the emulator against the fake OpenRouter (`scripts/fakes/fake_openrouter.py`: scripted transcripts and replies, no provider calls).

1. `scripts/qa/reset.sh build`: fake OpenRouter on port 8798 (`QA_PORT`), app and test pad built for it and installed.
2. `scripts/qa/reset.sh fresh` (a first start), then an agent with [personas/common.md](personas/common.md) plus [personas/advanced.md](personas/advanced.md), `QA_RUN=qa-out/round-N/advanced`, operating the phone only through [drive.py](drive.py). From round 2 on, it also checks the previous round's findings.
3. `scripts/qa/gallery.py qa-out/round-N`: `index.html` with every screenshot, the agent's captions, findings and verdict.
4. Fix blockers, majors and clear minors; product decisions go to the owner. Repeat until the agent is happy.

`real-check.py` is the counterpart with real models (a few cents on your own key): recordings through the speech model, then dictations through the request check and the text model, as the app calls OpenRouter.

Output goes to `qa-out/` (not committed).
