# Plan: one start, you decide afterwards (floating panel)

Status: approved, in progress.

## The idea

You start a dictation the same way every time (tile or a volume key, your choice), and the transcript always comes first. A small floating panel then lets you decide what happens next, during the recording or right after it:

```
 ┌───────────────────────────────────────────────┐
 │  ✕     Insert     ✨ Refine     🧑‍💼 Chief      │
 └───────────────────────────────────────────────┘
```

| Button | Does | Then turns into |
|---|---|---|
| **✕** | Closes the panel. The note stays in Talk Router. | – |
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
- **While the panel is open,** Talk Router listens to the ntfy topic itself (a JSON stream, no background service). When a paste message arrives for this request, **Insert from chief** appears.
- **When the panel is closed,** the ntfy app shows the notification. Its **Copy** action sends a broadcast to Talk Router, which copies the text. Whether ntfy's broadcast action reaches our app needs checking. Fallback: the text is in the notification, and the ntfy app can copy it.
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
   - Jev on GrapheneOS through OpenRouter.

   Results go into this file.
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
   - Screenshots, then the APK for the Pixel.

Hands-free (screen off, earbuds): the explicit spoken address covers the chief; other choices wait for the panel or the note.

## Not in this plan

- More start gestures (assistant role / corner swipe, headset button).
- The passive journal (write dictations readable by the chief without triggering it).
- Live text while you talk.
