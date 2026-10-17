# Using Drift

## Set up

Install the APK (Android 14+) and open Drift. You need an [OpenRouter](https://openrouter.ai) account with a few dollars of credits: Drift calls the models with your own key and has no server of its own. Setup also shows you how Drift works:

1. **Your OpenRouter key.** Create one at [openrouter.ai/keys](https://openrouter.ai/keys), paste it, tap **Check and save**. Drift checks it with OpenRouter and keeps it encrypted on the phone.
2. **Microphone and privacy.** Allow the microphone and read what leaves your phone.
3. **Transcript or text.** What Drift makes of a dictation (below).
4. **Start from any app.** Add the Drift tile to Quick Settings in one tap, and optionally allow the floating stop button (display over other apps).
5. **Try it once.** Start, say a sentence, stop, then touch and hold the practice box and paste.

Settings → **OpenRouter key** replaces or removes the key. Without one, Drift doesn't record.

## Record

Tap **Start dictating** in Drift, or the Drift tile in Quick Settings. Speak, switch apps, read, continue. Silence never stops recording. Stop with the tile, the floating stop button (if allowed), or in Drift.

When the note is ready, the text is copied and the floating button says **Ready to paste** for a moment (without it, a short message does). Go to any app, touch and hold a text field, and paste.

Drift needs no language setting: the speech model recognises the language, also when you switch languages in one note. Settings → **Speech model** picks the model (all of them take your words as spelling hints); the default is MAI-Transcribe 2.

## Transcript or text

Drift writes down what you say, and by default that's your note: exactly as you said it. A fast model (DeepSeek V4.1 Flash, about 0.7 s and $0.00003 per note) checks every note for a request to Drift:

- **Nothing asked:** the transcript, unchanged.
- **A request** ("clean this up", "make that more formal", "turn it into bullet points", "translate to German", "reply to this offer", "use my apartment application…", "just a transcript"): the text model, Claude Sonnet 5.5, writes that text, and it becomes your note.

Text that only talks to your reader ("can you call me back?") stays text. Settings → **Requests** turns the check off (then Drift only transcribes) and edits the text model's instructions. If the check fails, you get the transcript; if a request fails, you get what you said and a message saying so, with **Process again** on the note.

## Attachments

Talking about something, like a flat listing you're replying to? While you record, tap the 📎 on the left of the floating button (or **📎 Add** on Home) and **Paste what you copied** or **Attach a file** (images, PDFs, text, up to 10 MB). Attachments belong to that dictation: the request check knows they're there, and the text model gets them (PDFs are read as text by OpenRouter's file parser). With nothing asked, they're simply kept with the note. They stay on the phone with the note for 7 days, like its audio.

## Templates

Settings → **Templates** keeps texts you write again and again, like an application for an apartment: a name and the text. Say which one you want and what's different this time, for example "use my apartment application for the two-room flat on Hauptstraße 5, the viewing is on Saturday, and mention that I work from home". The check sees only your template names; only the template you ask for goes to the text model. Templates are stored encrypted on the phone.

## Notes and costs

Open a note to copy it, edit it, or switch between **Drift's version** and **What you said**. Each note shows what it cost, as OpenRouter reported it for each call; when OpenRouter reports no cost, Drift shows none rather than $0.00. **Redo with a smarter model** transcribes the saved audio again with a more accurate speech model.

Settings → **Activity** lists your latest notes with each step: the transcription (speech model), the request check with what it decided (transcript only or text, and the template), and the text model, each with the time it took and what it cost, or the error if a step failed. **Usage** shows each month's minutes and cost by model; Home shows this month and the last 14 days. Your OpenRouter account has the authoritative numbers.

## Data

Settings → **Keep audio for 7 days** (on by default) keeps recordings for redos, at most 100 MB, and **Delete saved audio** keeps text and costs. **Delete all my data** removes notes, audio, templates, words, About you, your key and settings on the phone. Drift's own language follows the system or Settings → Language.
