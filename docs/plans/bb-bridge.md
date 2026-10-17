# Plan: Drift becomes an open-source voice entry point for bb (Android)

Drift is now called **Talk Router** (app label and texts; package id, class names and stored keys stay). This plan keeps the old name where it describes the past.

Status: in progress (parts 1 and 2 done). Android only. The Mac app was removed and stays in Git history.

## What Drift becomes

Drift is the quickest way to start something with your voice, for technical people. You talk whenever you want (floating button, tile). The request check decides what happens:

| Outcome | What you get |
|---|---|
| **Transcript** (default) | The text right away, with Copy |
| **Text** (one call to a fixed model: rewrite, reply, template, 📎 context) | The text right away, with Copy |
| **Chief of staff** | The dictation goes to your chief-of-staff thread in bb. Drift shows "Sent to chief of staff ↗". When it's done, the chief notifies you through ntfy; tap to open the thread, where the text to copy is |

## Decisions (from the owner)

1. In-app agents and MCP servers go. bb replaces them.
2. One fixed **chief-of-staff thread** in bb receives every agent request. It splits up the work itself (child threads), and notifications come through it.
3. Fully technical: no Simple/Advanced split. We don't hold back for non-technical users; a short tutorial is enough.
4. Open source: a README instead of a website, **no Drift server**, everything runs on the phone. Only bb is supported for now, but the bridge is built so other targets can be added later.
5. No simple/smart choice: one text mode with one fixed model (`anthropic/claude-sonnet-5.5`, a constant in the code).
6. Phone only. The Mac app was deleted.

## How it works

```
Phone ──(your OpenRouter key)──▶ OpenRouter        speech, request check, text
Phone ──(MCP)──────────────────▶ bb  ──▶ chief-of-staff thread ──▶ child threads
                                            └──(curl)──▶ ntfy ──▶ ntfy app ──tap──▶ bb thread (PWA)
```

- **Models:** the app calls OpenRouter directly with the user's own key, stored in Vault. OpenRouter reports the cost of each call (`usage.cost`), so costs per note and in Activity stay.
  - Speech: the existing model choice (catalog bundled with the app).
  - Request check: `deepseek/deepseek-v4.1-flash`.
  - Text: `anthropic/claude-sonnet-5.5`.
- **bb:** Drift talks to bb's built-in MCP server (`bb--mcp`, off by default; the user turns it on).
  - Address: `https://<handle>.getbb.app/mcp` through bb Connect. The user approves Drift once on their bb account page (OAuth).
  - Fallback: bb's local address exposed on the user's tailnet (Tailscale Serve).
  - Sending: the `run` tool with `bb thread tell <chief> --mode queue "<dictation + pasted text>"`.
- **Chief-of-staff thread:** setup creates it (`bb thread spawn`, title "Chief of staff", in the user's chosen project) with standing instructions:
  - Answer directly or hand larger work to child threads (`--parent-self`).
  - When a request is done, or when it needs the user, send one notification: `curl -d "<short summary>" -H "Title: <topic>" -H "Click: <link to this thread>" https://ntfy.sh/<topic>`. The chief picks the text itself.
  - Put text meant to be pasted in its own block in the final reply.
- **Notifications:** through **ntfy** (mature app on Google Play and F-Droid, free, can be self-hosted), sent by the chief as described above. Tapping one opens the thread in the bb PWA. Neither the bb mobile app nor a Drift server is needed, and Drift itself sends no notifications.
  - The topic is a long random name that Drift generates during setup, because anyone who knows the topic can read it. Setup shows it with a button to subscribe in the ntfy app. Users who self-host ntfy enter their own server address.
- **Link:** after sending, the note shows "Sent to chief of staff ↗" and opens the thread in the bb PWA.

## Part 1: without bb (done)

- [x] **Remove:**
  - The server (`server/`), the website, marketing, the Mac app.
  - Accounts, balance, top-ups, ledger, the money setup page, the 5% fee, privacy and legal texts about the Drift server.
  - Simple/Advanced mode, the simple/smart choice and the text model picker.
  - Agents, MCP servers, AgentRunner.
- [x] **OpenRouter client** instead of `DriftApi`, for transcription (`/audio/transcriptions`) and chat completions (with 📎 attachments). Costs come from `usage`.
- [x] **Request check:** routes become transcript / text. The chief route comes in part 2. Templates stay.
- [x] **Setup:**
  1. OpenRouter key, with a link to create one, checked with `GET /api/v1/key`.
  2. Microphone and overlay permissions.
  3. How it works.
  4. Try it.
- [x] **Tests:** `scripts/fakes/fake_openrouter.py` replaces the fake server for device tests and the QA runs.

**Outcome:** Drift 2.0.0 calls OpenRouter directly (`OpenRouter.java`) with the key from setup, checked with `GET /key` and kept in Vault. Speech goes to `/audio/transcriptions` in two-minute chunks with word hints per host; the request check and the text call (Claude Sonnet 5.5, attachments as parts, PDFs through `file-parser`) go to `/chat/completions`, and each note keeps OpenRouter's `usage.cost` per step. Setup is welcome → key → microphone and privacy → transcript or text → start from any app → try it. `Router` returns `transcript` or `text` (old `simple`/`smart` read as text); a new route is one constant plus a branch in `DictationService.process()`. `Mcp.java` stays, unused, for part 2. Unit tests, lint and the instrumented tests against the fake pass.

## Part 2: bb (after a short test with real bb)

Check first, with real bb:
1. Can Drift sign in to bb's remote MCP from the phone (dynamic client registration, redirect to `driftdictation://…`), and does the sign-in keep working?
2. Sending through `run`: message length, quoting, `--mode queue`.
3. A thread link that opens in the bb PWA.
4. Whether the chief can run `curl` to ntfy.sh, and whether the click link opens the thread.

Then build:
- The chief route in the request check, with voice cues such as "tell my chief of staff…" and "let the agent do this…".
- The setup steps "Connect bb", "Chief of staff" and "Notifications (ntfy)".
- The note page and Activity for chief notes.
- 📎 to the chief: pasted text goes along inside the message. Files aren't supported in v1.

**Outcome:** `BbBridge` reaches bb's MCP server (`bb--mcp`) with `Mcp` and does everything through the `run` tool: `bb thread spawn --project proj_personal --title "Chief of staff"` with standing instructions (handle or delegate with `--parent-self`, text to paste in its own block, exactly one ntfy notification with `Click:` = `<link base>/threads/$BB_THREAD_ID` when done or when it needs the user, the first turn only acknowledges), `bb thread show` to adopt an existing thread (it is then told where to notify), and `bb thread tell <id> --mode queue --message-file -` to send. Checked with real bb: `--mode queue` on an idle thread starts a turn at once (`delivery: sent`); quotes, backticks, `$(…)` and line breaks arrive intact because the text goes as stdin. Connection: bb Connect (OAuth 2.1 + PKCE, client ID = the URL of `oauth/client.json`, redirect `talkrouter://oauth/callback`, endpoints discovered from metadata, refresh on expiry or 401, revoke on sign-out) or a custom MCP address with an optional bearer token (tailnet, or `adb reverse` to `127.0.0.1:38886`). The request check offers a third route, `chief`, only when bb is connected and a chief is set; on it the dictation as said plus pasted text goes to the chief, the note shows "Sent to chief of staff ↗" (opens the thread in the bb web app) and "What you said", nothing is copied, and Activity shows a `bb` step. Failures keep and copy what was said, say why, and offer "Send again". ntfy: a random topic (`talkrouter-` + 24 characters) made once, "Open in ntfy" (`ntfy://<host>/<topic>`, else Google Play / F-Droid), "Send test" from the phone, editable server (the chief is told). Setup got three optional pages (Connect bb with "Later", Chief of staff, Notifications) and Settings a bb section. Tests: unit tests (PKCE per RFC 7636, the authorize URL, handles, links, snippets, the router), `scripts/fakes/fake_bb_mcp.py` with `BbFlowTest` in custom-address mode, `real-check.py` (9 of 9 chief cues right with the real request check), and a real end-to-end run on the emulator against the local bb through `adb reverse` (transcript and route queued through the fake OpenRouter): the chief was created and acknowledged without notifying; a dictation with pasted text reached it in 0.3 s, it answered and its one ntfy notification arrived about 5 s later with `Click:` set to its thread; a delegated request went to a child thread and was notified once the child reported; with bb unreachable the note kept what was said and Send again delivered it. Not verified yet: the bb Connect sign-in from a phone (the emulator has no internet; the authorize page loads for our client ID before sign-in) (whether getbb.app accepts the custom-scheme redirect and the `text/plain` client metadata from raw.githubusercontent.com); failures show bb's error text.

## Part 3: open source and release

- **README:** what Drift is, its values, setup, building, and privacy in a few sentences. Then LICENSE and a secret check of the Git history. Making the repo public is the owner's step.
- **Proposed values:**
  1. **Talk anywhere.** Start with your voice from any app.
  2. **Say it once, get it right.** The best speech and text models, so the text is right the first time.
  3. **One entry point.** Drift decides: the text right away, or handed to your agent.
  4. **Yours.** Open source, your own keys, no Drift server in between.
- **QA:** a technical persona runs setup → text → chief → notification → thread until they're satisfied.
- **Release:** APK as a GitHub release. The old Drift server is shut down only when the owner confirms.

## Not in this plan (possible later)

- Other targets as more adapters: Orca (`orca` CLI), Hermes, OpenClaw, Claude Code directly…
- The Mac app again.
- Drift receiving notifications itself, or a Copy button in the notification.
- Files from 📎 to the chief.
- Choosing other bb threads by voice.
