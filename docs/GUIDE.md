# Using Talk Router

## Set up

Install the APK (Android 14+) and open Talk Router. You need an [OpenRouter](https://openrouter.ai) account with a few dollars of credits: Talk Router calls the models with your own key and has no server of its own. Setup also shows you how Talk Router works:

1. **Your OpenRouter key.** Create one at [openrouter.ai/keys](https://openrouter.ai/keys), paste it, tap **Check and save**. Talk Router checks it with OpenRouter and keeps it encrypted on the phone.
2. **Microphone and privacy.** Allow the microphone and read what leaves your phone.
3. **Transcript or text.** What Talk Router makes of a dictation (below).
4. **Start from any app.** Add the Talk Router tile to Quick Settings in one tap, and optionally allow the floating stop button (display over other apps).
5. **Try it once.** Start, say a sentence, stop, then touch and hold the practice box and paste.
6. **Connect bb, Chief of staff, Notifications** (optional, **Later** skips them): see [Your chief of staff in bb](#your-chief-of-staff-in-bb). Without bb, Talk Router is a dictation app.

Settings → **OpenRouter key** replaces or removes the key. Without one, Talk Router doesn't record.

## Record

Tap **Start dictating** in Talk Router, or the Talk Router tile in Quick Settings. Speak, switch apps, read, continue. Silence never stops recording. Stop with the tile, the floating stop button (if allowed), or in Talk Router.

When the note is ready, the text is copied and the floating button says **Ready to paste** for a moment (without it, a short message does). Go to any app, touch and hold a text field, and paste.

Talk Router needs no language setting: the speech model recognises the language, also when you switch languages in one note. Settings → **Speech model** picks the model (all of them take your words as spelling hints); the default is MAI-Transcribe 2.

## Transcript or text

Talk Router writes down what you say, and by default that's your note: exactly as you said it. A fast model (DeepSeek V4.1 Flash, about 0.7 s and $0.00003 per note) checks every note for a request to Talk Router:

- **Nothing asked:** the transcript, unchanged.
- **A request** ("clean this up", "make that more formal", "turn it into bullet points", "translate to German", "reply to this offer", "use my apartment application…", "just a transcript"): the text model, Claude Sonnet 5.5, writes that text, and it becomes your note.

Text that only talks to your reader ("can you call me back?") stays text. Settings → **Requests** turns the check off (then Talk Router only transcribes) and edits the text model's instructions. If the check fails, you get the transcript; if a request fails, you get what you said and a message saying so, with **Process again** on the note.

## Your chief of staff in bb

[bb](https://getbb.app) runs coding agents (threads) on your own computer. Talk Router can hand a task you speak to one bb thread, your **chief of staff**, which handles it or starts child threads for bigger work. When it's done, or when it needs you, it notifies you through [ntfy](https://ntfy.sh); tapping the notification opens the thread in the bb web app, where the answer (and any text to paste) is.

With a chief set up, the request check has a third outcome. It picks it when you clearly hand over a task: "tell my chief of staff…", "chief of staff, …", "let the agent do this", "ask my agent…", or "research …" when it's a task rather than a text to write. The dictation as said, plus pasted text from 📎, goes to the chief (images and PDFs don't, yet). Nothing is copied; the floating button says **Sent to chief of staff**, and the note shows **Sent to chief of staff ↗**, which opens the thread, and **What you said**. If bb can't be reached, the note keeps what you said (copied as usual), says why, and offers **Send again**.

Set it up in setup or Settings → **bb**:

1. **Connection.** bb's MCP server (plugin `bb--mcp`) must be on.
   - **bb Connect:** enter your handle (`<handle>.getbb.app`) and tap **Sign in with bb**. Your browser opens bb's account page; approve Talk Router there and you come back signed in. **Sign out** revokes the sign-in.
   - **Custom address:** an MCP address and an optional bearer token, for example bb's local address `http://127.0.0.1:38886/api/v1/plugins/bb--mcp/http/mcp` reached through your tailnet (Tailscale Serve, HTTPS) or `adb reverse tcp:38886 tcp:38886`. Plain HTTP is allowed only to 127.0.0.1. Also enter the bb web address used for thread links (`https://<handle>.getbb.app`).
   - **Test connection** / **Save and test** checks that bb answers and offers its `run` tool.
2. **Chief of staff.** **Create chief of staff** starts a thread called "Chief of staff" in Personal (bb's "no project") with standing instructions: handle requests or delegate them with `bb thread spawn --parent-self`, put text to paste in its own block, and send exactly one ntfy notification when a request is done or needs you. Or paste the link or id of a thread you already have; Talk Router tells it where to send notifications.
3. **Notifications.** Talk Router makes a long random ntfy topic once (anyone who knows it can read the notifications). **Open in ntfy** subscribes in the ntfy app (Google Play or F-Droid), **Send test** posts a test message from the phone. Self-hosting ntfy? Enter your server; the chief is told.

Talk Router itself sends no notifications: the chief runs `curl` to ntfy on your computer.

## Attachments

Talking about something, like a flat listing you're replying to? While you record, tap the 📎 on the left of the floating button (or **📎 Add** on Home) and **Paste what you copied** or **Attach a file** (images, PDFs, text, up to 10 MB). Attachments belong to that dictation: the request check knows they're there, and the text model gets them (PDFs are read as text by OpenRouter's file parser). With nothing asked, they're simply kept with the note. They stay on the phone with the note for 7 days, like its audio.

## Templates

Settings → **Templates** keeps texts you write again and again, like an application for an apartment: a name and the text. Say which one you want and what's different this time, for example "use my apartment application for the two-room flat on Hauptstraße 5, the viewing is on Saturday, and mention that I work from home". The check sees only your template names; only the template you ask for goes to the text model. Templates are stored encrypted on the phone.

## Notes and costs

Open a note to copy it, edit it, or switch between **Talk Router's version** and **What you said**. Each note shows what it cost, as OpenRouter reported it for each call; when OpenRouter reports no cost, Talk Router shows none rather than $0.00. **Redo with a smarter model** transcribes the saved audio again with a more accurate speech model.

Settings → **Activity** lists your latest notes with each step: the transcription (speech model), the request check with what it decided (transcript only, text and the template, or chief of staff), the text model, and the hand-over to bb, each with the time it took and what it cost (bb costs nothing here), or the error if a step failed. **Usage** shows each month's minutes and cost by model; Home shows this month and the last 14 days. Your OpenRouter account has the authoritative numbers.

## Data

Settings → **Keep audio for 7 days** (on by default) keeps recordings for redos, at most 100 MB, and **Delete saved audio** keeps text and costs. **Delete all my data** removes notes, audio, templates, words, About you, your key, your bb sign-in and settings on the phone. Talk Router's own language follows the system or Settings → Language.
