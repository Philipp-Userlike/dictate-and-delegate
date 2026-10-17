# Privacy and storage

The user-facing policy is `app/src/main/res/raw/privacy.txt` (German: `raw-de`). This page describes the implementation.

Talk Router has no server, analytics, crash reporting, accounts, screen reading or Accessibility service. The network peers are OpenRouter (`OpenRouter.java`, base URL `BuildConfig.OPENROUTER_URL`) and, only if you connect it, your own bb and ntfy server.

- **What is sent:** after you stop, the audio goes over HTTPS to OpenRouter (`/audio/transcriptions`) in chunks of about two minutes, with your words as provider-specific spelling hints, and on to the host of the chosen speech model. The transcript then goes to the request check (DeepSeek V4.1 Flash, `/chat/completions`) with the names of your templates and a short summary of attachments; only if it finds a request, the transcript, About you, your words, the template asked for and the attachments go to the text model (Claude Sonnet 5.5), unless requests are off. Redos are sent the same way. No language setting is sent; the speech model detects it. Redirects are not followed. Requests carry `X-Title: Talk Router` for OpenRouter's app attribution.
- **Who sees it:** OpenRouter and the provider serving each call, under their policies and your OpenRouter account's settings (data retention, training, provider choice). Talk Router adds nothing in between.
- **bb (optional):** when the request check routes a dictation to your chief of staff, the transcript as said and pasted text from 📎 go to your bb through its MCP server (`BbBridge.java`): via bb Connect (`https://<handle>.getbb.app/mcp`, relayed by getbb.app to your computer) or a custom address you entered. Images and PDFs are not sent. Setup sends the chief's standing instructions, which include your ntfy topic. What the chief and its agents then do runs on your computer with the providers you chose in bb.
- **bb sign-in:** OAuth with PKCE against getbb.app (`BbOAuth.java`); the client ID is the public metadata document `oauth/client.json`. Access and refresh tokens, a custom bearer token and the ntfy topic are stored with AES-GCM in `Vault`. Sign out and Delete all my data revoke the tokens at getbb.app (best effort) and delete them.
- **ntfy:** the chief, on your computer, posts notifications to `<server>/<topic>` (default ntfy.sh), and anyone who knows the topic can read them, so the topic is 24 random characters. The phone only talks to ntfy when you tap **Send test**; Talk Router itself posts no notifications and shows none.
- **The key:** your OpenRouter key is checked with `GET /key` and stored with AES-GCM under a non-exportable Android Keystore key (`Vault`), like templates and notes. It is sent only to OpenRouter, as a bearer token.
- **Notes:** encrypted in local SQLite; IDs and dates are plaintext. Backups and device transfer are excluded (`allowBackup="false"`, data extraction rules).
- **Audio:** kept by default for 7 days, at most 100 MB, oldest first, in the app's private no-backup directory, so a note can be redone with another speech model. Turning it off deletes saved audio. Audio of failed recordings is kept until retry or discard. Talk Router does not encrypt audio separately; device encryption applies.
- **Clipboard:** results are copied on purpose and marked sensitive so Android hides previews. Clipboard managers and the app you paste into may keep the text.
- **Screens:** the note list, notes and Activity are hidden from screenshots and Recents (`FLAG_SECURE`).
- **Delete all my data** clears notes, audio, attachments, templates, words, About you, the key, the bb sign-in, the ntfy topic and settings.

The repository holds no real keys, signing material or recordings. Screenshots use sample dictations.
