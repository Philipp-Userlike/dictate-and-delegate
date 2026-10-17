# Plan: one start, you decide afterwards (floating panel)

Status: built in version 2.2 (see [Outcome](#outcome)).

## The idea

You start a dictation the same way every time (tile or a volume key, your choice), and the transcript always comes first. A small floating panel then lets you decide what happens next, during the recording or right after it:

```
 ┌───────────────────────────────────────────────┐
 │  ✕     Insert     ✨ Refine     🧑‍💼 Chief      │
 └───────────────────────────────────────────────┘
```

| Button | Does | Then turns into |
|---|---|---|
| **✕** | Closes the panel. The note stays in Dictate & Delegate. | – |
| **Insert** | Puts the transcript at the cursor of the app you're in. | – |
| **✨ Refine** | One model call improves the text towards what it's for (message, email, note, reply…) and fills in a template if you named one. | **Insert refined**, once it's ready |
| **🧑‍💼 Chief** | Sends what you said, plus any pasted text, to your chief-of-staff thread in bb. | **Open chat ↗**, which opens the thread |

- **Refine can start by itself.** Jev decides whether refining makes sense, e.g. you asked for something or named a template. Then Refine starts automatically and its button shows that it's working.
- **Chief starts by itself only when you address it explicitly** ("chief of staff, …", "tell my chief of staff…"). Jev never sends there on a guess: tasks without an address stay with you, one tap away.
- **The chief can send text back to you.** If the panel is still open, an **Insert from chief** button appears. If you've closed it, you get a notification through ntfy with a **Copy** action.

## Decisions (from the owner)

1. One start. Tile and volume keys are configurable.
2. The floating panel with ✕, Insert, Refine and Chief.
3. Refine may start automatically (Jev). Chief starts automatically only when you address it explicitly; otherwise on your tap.
4. Refine improves the text towards its purpose and applies templates. The button is called **Refine**.
5. The chief can send text back: into the panel if it's open, otherwise as a notification.

## How it works

### Starting
- **Tile** in Quick Settings (exists).
- **Volume keys** (through the same Accessibility service as Insert): hold a volume key to start; press again (or hold) to stop. A short press still changes the volume. Settings: off, volume down, or volume up; and the hold time (default 0.5 s). Screen on only.
- Both can be on at the same time.

### Recording and the panel
- The panel replaces the current floating button. While recording it shows the timer and 📎; **Refine** and **Chief** can already be tapped then. The choice is remembered and carried out once the transcript is ready, so a slow connection never makes you wait to decide.
- After you stop, the panel stays until you tap ✕. It also hides after a while without use, and is never in the way of the keyboard (draggable, like today).
- The panel never takes focus from the app you're in, so the cursor stays where it is.

### Insert
- Through an Accessibility service: **Insert** pastes at the cursor of the field that has focus **when you tap**. You can move the cursor or switch fields first.
- If refined text arrives after you inserted the transcript and the field still contains it unchanged, **Insert refined** replaces it. Otherwise it inserts at the cursor.
- If there's no field, or the app refuses, the text goes to the clipboard and the panel says "Copied, paste with a long press".
- Without the Accessibility permission, Insert falls back to copying. Setup explains the permission: it's used only to insert text, the app reads nothing, and the code is open.

### Refine and Jev
- **Jev** (`~typesafe/jev-latest` through OpenRouter's `/api/alpha/decisions`) gets the transcript and two questions:
  - `refine`: does this dictation ask for a text to be written or changed, or would it clearly benefit from it?
  - `template`: which of your templates, if any, does it name?
  - `chief`: does the speaker explicitly address their chief of staff (or "my agent") with a task? A bare imperative ("look into why the build failed") is not an address.
- Chief starts automatically only above a high threshold (start: 0.9). Below it, nothing is sent. The panel highlights 🧑‍💼 when Jev was unsure, as a hint.
- If Jev answers above a threshold, or names a template, Refine starts automatically. The threshold is set from a labelled test set.
- About 0.3 s and $0.00003 per dictation. If Jev fails or is slow, nothing starts automatically; the button still works.
- **Refine itself:** one call to the text model (Claude Sonnet 5.5), with a new prompt:
  - Work out what the text is for and improve it towards that purpose.
  - Follow any instructions in the dictation.
  - Use the named template.
  - Keep the language and the facts, and invent nothing.
- The old request check (DeepSeek, routes transcript/text/chief) and the "Act on requests" switch are removed. Settings get two switches: auto-refine, and auto-send to chief when addressed.

### Chief, and text back from the chief
- **Chief** sends the dictation as today, plus a short request ID.
- The chief's standing instructions get a second command: for text meant to be pasted, it sends an ntfy message carrying that request ID and a `talkrouter-paste` tag.
- **While the panel is open,** Dictate & Delegate listens to the ntfy topic itself (a JSON stream, no background service). When a paste message arrives for this request, **Insert from chief** appears.
- **When the panel is closed,** the ntfy app shows the notification. Its **Copy** action sends a broadcast to Dictate & Delegate, which copies the text. Whether ntfy's broadcast action reaches our app needs checking. Fallback: the text is in the notification, and the ntfy app can copy it.
- The chief's normal "done" notification stays.

### Notes and Activity
- The note keeps the transcript, the refined text, the chief's text back, and the link to the thread.
- Activity shows Jev, Refine and bb as steps.

## Steps

1. **Spike:**
   - Holding a volume key: detected in every app, a short press still changes the volume.
   - Accessibility paste in common apps: Signal, WhatsApp, Firefox/Vanadium, Gmail/K-9, Termux, the bb PWA.
   - Replacing inserted text.
   - The ntfy broadcast action.
   - Jev on the phone through OpenRouter.

   Results go into this file: [Spike results](#spike-results).
2. **Starts:** volume-key start and stop through the Accessibility service, with the settings above; the tile stays.
3. **Panel:** replaces the pill, with the states above. Pre-selection works during recording.
4. **Insert:** the Accessibility service, the clipboard fallback, replacing in place.
5. **Refine and Jev:** the new prompt, Jev for auto-refine, templates and explicit chief address, and a labelled test set of about 60 dictations (German and English, with and without an address) to set the thresholds. Remove the old request check.
6. **Chief text back:** the new standing instructions (existing chiefs are told), the ntfy stream while the panel is open, the Copy action.
7. **Setup and docs:**
   - Setup gets the Accessibility page and the choice of start (tile, volume key).
   - Requests settings become Refine settings: auto-refine on/off and the prompt.
   - README, GUIDE and the routes diagram are updated.
8. **Tests and QA:**
   - Unit tests for the Jev request and the decisions, plus the fakes (Jev in the fake OpenRouter, ntfy in a fake).
   - Device tests for the panel, Insert in the test pad app, auto-refine, and chief text back.
   - A real check on this Mac with bb and ntfy.
   - Screenshots, then the APK for the phone.

Hands-free (screen off, earbuds): the explicit spoken address covers the chief; other choices wait for the panel or the note.

## Not in this plan

- More start gestures (assistant role / corner swipe, headset button).
- The passive journal (write dictations readable by the chief without triggering it).
- Live text while you talk.

## Spike results

Done on the Android 16 emulator, and by the user on a phone.

- **Insert and replace in place** work in a native EditText, a Chrome textarea, a contenteditable, Chrome's address bar and the Settings search field: `findFocus(FOCUS_INPUT)`, then `ACTION_SET_TEXT` with the text spliced in at the selection, and the selection moved after it. Chrome builds its web accessibility tree only once a service has asked for events; in the end the service declares none (`res/xml/accessibility.xml`) and the insert still worked in the test. Signal, WhatsApp, Gmail/K-9, Termux and the bb PWA weren't tried (no internet or accounts on the emulator); Vanadium is Chromium, like Chrome. To check on the phone.
- **Volume keys:** the accessibility key filter only sees real keyboard-source key events. Shell-injected keys (`input keyevent`, `input keyboard keyevent`, `emu event send`) never reach `onKeyEvent`, and this AVD has no input device for the volume keys. Device tests hand key events to `TalkAccessibility.onKeyEvent` directly. On the phone, holding the key starts and stops a dictation and a short press still changes the volume.
- **ntfy's broadcast action** (`action=broadcast, intent=…, extras.request=…`) sends an implicit broadcast that the receiver registered by the Accessibility service gets (tested with `am broadcast`). It carries only the request ID; the text is fetched from the user's own topic, so another app can't put text on the clipboard. Without the service on, there's no receiver, and the notification's text can still be copied in the ntfy app.
- **Jev** (`POST /api/alpha/decisions`): yes/no questions are `{"type": "noul", "instructions", "criteria": {"true", "false"}}`, choices are `{"type": "choice", "instructions", "criteria": {option: description}}`; answers come back as `noul` (a probability) and `choice`. About 0.3 s and $0.00003 per call. On the phone through OpenRouter it answers like on the Mac.

## Outcome

Built as planned, with these changes from the phone test of 2.2.0 (the user, on a phone):

- **The chief is addressed by a name** the user sets (Settings → Chief of staff → Your chief's name, saved as you type with a visible ✓ Saved). "Chief of staff, …" cues weren't reliable: The user's own phrasings ("I want to talk to the chief of staff…", "this time it should go to the chief of staff as well") got Refine instead. Now Dictate & Delegate first checks whether the name (or Henri/Henrie/Henry's) comes up; only then Jev is asked, with the name in the question, whether the dictation is meant for the chief or only mentions them. Meant for the chief at ≥ 0.8 sends it there by itself and Refine doesn't start; 0.5–0.8, or Jev failing, only highlights 🧑‍💼. No name, no chief. The threshold went from 0.9 to 0.8 because the name check already filters: on the labelled set (`scripts/qa/jev-check.py`, report in `qa-out/jev-3.md`), 12–13 of 15 addresses are sent across runs, none of 9 mere mentions, and the user's two phrasings score 0.96–0.98.
- **Refine** at ≥ 0.5 or a named template: 57 of 57 right on the labelled set, templates 63 of 63.
- **The panel is compact:** icon buttons (named for TalkBack, long press shows the name) that wrap whole onto a second row if they ever don't fit; while recording only the timer and ⋯ show. The text labels had broken onto a second line in German.
- **What goes to the chief goes as said:** a Refine that Jev started is dropped when the dictation goes to the chief (by name or by tap); a Refine you tapped yourself still runs.
- **✕ while recording cancels**: the audio is deleted, nothing is transcribed or sent. Cancelling during transcription isn't offered: the audio is already uploaded and billed by then.
- **Open chat ↗** sends `Browser.EXTRA_APPLICATION_ID`, so Chromium browsers reuse one tab instead of opening a new one each time. The chief's ntfy notification link opens through the ntfy app, which Dictate & Delegate doesn't control.
- **Text back from the chief** works as planned: the JSON stream while the panel is open, ntfy's Copy broadcast when it's closed.
- **Nothing goes on the clipboard by itself** (the user, after 2.2.1): not the transcript, not the refined text, not the chief's text. Insert sets the field's text directly; the clipboard is used only for an explicit Copy, or when Insert can't reach a field ("Copied instead"), or for an app that only accepts pastes.
- **Refine with your own Anthropic key** (2.2.3): Refine goes to Anthropic's Messages API directly, on the user's Anthropic credits, with OpenRouter as the fallback. Its cost is computed at list price and shown apart from the OpenRouter totals. Not checked against the real API yet: no Anthropic key on the test Mac.
- **The chief's instructions became two bb user skills** (2.2.4): chief-of-staff (the user's wording: flat hierarchy, parallel child threads, delegating context, intent and what done looks like) and dictate-and-delegate (request IDs, the notification and the paste message). Dictate & Delegate fills them in, installs them through a short bb thread and keeps them current with a content hash in each file, checked on connect, on setting changes and after app updates. The chief thread only gets one line. Not installed into the real `~/.bb/skills` on the test Mac; checked against the fake bb.
- **The recording panel shows all its buttons in one row** (■ timer ✨ 🧑‍💼 ✕ 📎); the ⋯ of 2.2.3 needed a second tap.
- **Images and PDFs reach the chief** (2.2.5): uploaded to bb's attachments through its REST API and sent with `bb thread tell --image/--file`, images first; base64 text files through MCP as the fallback when bb refuses the upload. Real check on the Mac (local bb, custom address): a test chief got a red JPEG and answered "Red" back into the panel. Not checked yet: whether bb Connect accepts the app's sign-in for the upload; Activity shows "(as base64)" if it doesn't.
- **Attachments over bb Connect** (2.2.6): on Philipp's phone the base64 fallback failed, because bb's MCP tools take at most 100,000 characters of code and the whole image went in one snippet. The base64 now goes in parts of 80,000 characters that the chief joins and decodes; files over 3 MB are named instead of sent, and the dictation always goes. Real check with the upload refused on purpose: a 359 kB picture went in 6 parts and the chief answered "Red". The direct upload over Connect can't be fixed in the app: the getbb.app relay checks the token, which is issued for `/mcp`.

