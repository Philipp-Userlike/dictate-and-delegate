# Privacy and storage

The user-facing policy is `app/src/main/res/raw/privacy.txt` (German: `raw-de`). This page describes the implementation.

Drift has no server, analytics, crash reporting, accounts, screen reading or Accessibility service. The only network peer is OpenRouter (`OpenRouter.java`, base URL `BuildConfig.OPENROUTER_URL`).

- **What is sent:** after you stop, the audio goes over HTTPS to OpenRouter (`/audio/transcriptions`) in chunks of about two minutes, with your words as provider-specific spelling hints, and on to the host of the chosen speech model. The transcript then goes to the request check (DeepSeek V4.1 Flash, `/chat/completions`) with the names of your templates and a short summary of attachments; only if it finds a request, the transcript, About you, your words, the template asked for and the attachments go to the text model (Claude Sonnet 5.5), unless requests are off. Redos are sent the same way. No language setting is sent; the speech model detects it. Redirects are not followed. Requests carry `X-Title: Drift` for OpenRouter's app attribution.
- **Who sees it:** OpenRouter and the provider serving each call, under their policies and your OpenRouter account's settings (data retention, training, provider choice). Drift adds nothing in between.
- **The key:** your OpenRouter key is checked with `GET /key` and stored with AES-GCM under a non-exportable Android Keystore key (`Vault`), like templates and notes. It is sent only to OpenRouter, as a bearer token.
- **Notes:** encrypted in local SQLite; IDs and dates are plaintext. Backups and device transfer are excluded (`allowBackup="false"`, data extraction rules).
- **Audio:** kept by default for 7 days, at most 100 MB, oldest first, in the app's private no-backup directory, so a note can be redone with another speech model. Turning it off deletes saved audio. Audio of failed recordings is kept until retry or discard. Drift does not encrypt audio separately; device encryption applies.
- **Clipboard:** results are copied on purpose and marked sensitive so Android hides previews. Clipboard managers and the app you paste into may keep the text.
- **Screens:** the note list, notes and Activity are hidden from screenshots and Recents (`FLAG_SECURE`).
- **Delete all my data** clears notes, audio, attachments, templates, words, About you, the key and settings.

The repository holds no real keys, signing material or recordings. Screenshots use sample dictations.
