# Using Talk Router

## Set up

Install the APK (Android 14+) and open Talk Router. You need an [OpenRouter](https://openrouter.ai) account with a few dollars of credits: Talk Router calls the models with your own key and has no server of its own. Setup also shows you how Talk Router works:

1. **Your OpenRouter key.** Create one at [openrouter.ai/keys](https://openrouter.ai/keys), paste it, tap **Check and save**. Talk Router checks it with OpenRouter and keeps it encrypted on the phone.
2. **Microphone and privacy.** Allow the microphone and read what leaves your phone.
3. **Transcript first, then you decide.** What the floating panel offers (below).
4. **Start and insert.** Add the Talk Router tile to Quick Settings in one tap, or choose to start by holding a volume key. Allow the floating panel (display over other apps).
5. **Accessibility.** Lets **Insert** put text at the cursor and lets Talk Router notice the volume key. Android opens its Accessibility settings; tap Talk Router and turn it on. If Android says the setting is restricted: App info → ⋮ → Allow restricted settings, then try again. Without it, Insert copies instead.
6. **Try it once.** Start, say a sentence, stop, tap the practice box, then **Insert** on the panel.
7. **Connect bb, Chief of staff, Notifications** (optional, **Later** skips them): see [Your chief of staff in bb](#your-chief-of-staff-in-bb). Without bb, Talk Router is a dictation app.

Settings → **OpenRouter key** replaces or removes the key. Without one, Talk Router doesn't record.

## Record

Start with the Talk Router tile in Quick Settings, by holding the volume key you chose, or with **Start dictating** in Talk Router. Speak, switch apps, read, continue. Silence never stops recording. Stop with ■ on the floating panel, the tile, or by holding (or pressing) the volume key again.

**Volume key:** Settings → **Start** picks off, volume down or volume up, and how long to hold (0.5 s by default). A short press still changes the volume. It works while the screen is on and unlocked, not during calls.

Talk Router needs no language setting: the speech model recognises the language, also when you switch languages in one note. Settings → **Speech model** picks the model (all of them take your words as spelling hints); the default is MAI-Transcribe 2.

## The floating panel

While you record, the panel shows 📎, the timer with ■ (stop), ✕ (cancel), **✨ Refine** and **🧑‍💼 Chief**. Tapping Refine or Chief now means "do this when the transcript is ready" (✓); tap again to undo. ✕ throws the recording away: nothing is transcribed or sent, so it costs nothing.

After you stop, your words are transcribed and copied, and the panel offers:

- **Insert:** puts the transcript at the cursor of the field you're typing in, when you tap. You can move the cursor or switch fields first. With no field, or without the Accessibility permission, the text is copied and the panel says "Copied, paste with a long press".
- **✨ Refine:** one call to the text model (Claude Sonnet 5.5) makes the text you need: it works out what the text is for (message, email, note, reply…) and improves it towards that, follows what you asked ("more formal", "turn it into bullet points", "translate to German"), fills in a template you named, and keeps your language and the facts. Then the button says **Insert refined**: if the transcript you inserted is still unchanged in the field, it's replaced in place; otherwise the refined text goes at the cursor. Refined text is copied too.
- **🧑‍💼 Chief:** hands what you said to your chief of staff in bb (below). Then the button says **Open chat ↗**.
- **✕:** closes the panel. The note stays in Talk Router.

The panel never takes focus, so the cursor stays in your app; drag it anywhere. It hides after a minute without a tap (five while Refine runs or the chief's text is awaited).

**What starts by itself.** Jev, a fast decision model (about 0.3 s and $0.00003 per note), reads the transcript:

- When you ask for a text or name a template, **Refine** starts by itself.
- When your chief's name comes up (Settings → **Refine** → **Your chief's name**, for example "Ada"), Jev decides whether the dictation is meant for the chief: "Ada, …", "this is for Ada", "I want to talk to Ada about…", "send this to Ada". Then it goes to the chief by itself, and isn't refined. A name that only comes up in what you say ("say hi to Ada from me") stays with you. If Jev isn't sure, or fails, 🧑‍💼 is highlighted instead. The name also matches common mishearings (Henry: Henri, Henrie, Henry's).

Settings → **Refine** turns both off and edits Refine's instructions. If Jev fails, nothing starts by itself; the buttons still work. If Refine fails, the panel and the note say why and offer to try again.

## Your chief of staff in bb

[bb](https://getbb.app) runs coding agents (threads) on your own computer. Talk Router can hand a task you speak to one bb thread, your **chief of staff**, which handles it or starts child threads for bigger work. When it's done, or when it needs you, it notifies you through [ntfy](https://ntfy.sh); tapping the notification opens the thread in the bb web app, where the answer (and any text to paste) is.

Tap **🧑‍💼 Chief** on the panel, or say it's for your chief by name (above). The dictation as said, plus pasted text from 📎 and a short request ID, goes to the chief (images and PDFs don't, yet). The panel then offers **Open chat ↗**, and the note shows **Sent to chief of staff ↗** and **What you said**. If bb can't be reached, the note keeps what you said, says why, and offers **Send again**.

**Text back from the chief.** When you ask for something to paste ("draft a reply to Sarah and send me the text"), the chief posts it to your ntfy topic, tagged with the request ID. While the panel is open, Talk Router reads the topic and shows **Insert from chief**. Otherwise the ntfy notification has a **Copy** button, which hands the request ID to Talk Router; Talk Router fetches the text from your topic and copies it (this needs the Accessibility service on, since that's what receives it). The note keeps the chief's text.

Set it up in setup or Settings → **bb**:

1. **Connection.** bb's MCP server (plugin `bb--mcp`) must be on.
   - **bb Connect:** enter your handle (`<handle>.getbb.app`) and tap **Sign in with bb**. Your browser opens bb's account page; approve Talk Router there and you come back signed in. **Sign out** revokes the sign-in.
   - **Custom address:** an MCP address and an optional bearer token, for example bb's local address `http://127.0.0.1:38886/api/v1/plugins/bb--mcp/http/mcp` reached through your tailnet (Tailscale Serve, HTTPS) or `adb reverse tcp:38886 tcp:38886`. Plain HTTP is allowed only to 127.0.0.1. Also enter the bb web address used for thread links (`https://<handle>.getbb.app`).
   - **Test connection** / **Save and test** checks that bb answers and offers its `run` tool.
2. **Chief of staff.** **Create chief of staff** starts a thread called "Chief of staff" in Personal (bb's "no project") with standing instructions: handle requests or delegate them with `bb thread spawn --parent-self`, send text to paste as an ntfy message tagged with the request ID, and send exactly one ntfy notification when a request is done or needs you. Or paste the link or id of a thread you already have; Talk Router tells it where to send notifications (a chief from before version 2.2 is told once how to send text back).
3. **Notifications.** Talk Router makes a long random ntfy topic once (anyone who knows it can read the notifications). **Open in ntfy** subscribes in the ntfy app (Google Play or F-Droid), **Send test** posts a test message from the phone. Self-hosting ntfy? Enter your server; the chief is told.

Talk Router itself sends no notifications: the chief runs `curl` to ntfy on your computer.

## Attachments

Talking about something, like a flat listing you're replying to? While you record, tap the 📎 on the floating panel (or **📎 Add** on Home) and **Paste what you copied** or **Attach a file** (images, PDFs, text, up to 10 MB). Attachments belong to that dictation: Refine gets them (PDFs are read as text by OpenRouter's file parser). Without Refine, they're simply kept with the note. They stay on the phone with the note for 7 days, like its audio.

## Templates

Settings → **Templates** keeps texts you write again and again, like an application for an apartment: a name and the text. Say which one you want and what's different this time, for example "use my apartment application for the two-room flat on Hauptstraße 5, the viewing is on Saturday, and mention that I work from home". Jev sees only your template names; only the template you ask for goes to the text model, and naming one starts Refine by itself. Templates are stored encrypted on the phone.

## Notes and costs

Open a note to copy it, edit it, or switch between **Talk Router's version** and **What you said**. Each note shows what it cost, as OpenRouter reported it for each call; when OpenRouter reports no cost, Talk Router shows none rather than $0.00. **Redo with a smarter model** transcribes the saved audio again with a more accurate speech model.

Settings → **Activity** lists your latest notes with each step: the transcription (speech model), Jev with what it answered (e.g. "refine 0.82 · chief 0.97"), Refine, and the hand-over to bb, each with the time it took and what it cost (bb costs nothing here), or the error if a step failed. **Usage** shows each month's minutes and cost by model; Home shows this month and the last 14 days. Your OpenRouter account has the authoritative numbers.

## Data

Settings → **Keep audio for 7 days** (on by default) keeps recordings for redos, at most 100 MB, and **Delete saved audio** keeps text and costs. **Delete all my data** removes notes, audio, templates, words, About you, your key, your bb sign-in and settings on the phone. Talk Router's own language follows the system or Settings → Language.
