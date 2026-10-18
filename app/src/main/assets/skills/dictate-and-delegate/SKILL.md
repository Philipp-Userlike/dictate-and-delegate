---
name: dictate-and-delegate
description: "How to read messages from Dictate & Delegate, the voice app on {{NAME}}'s phone, and how to notify {{NAME}} or send text to paste. Use when a message ends with [request: <id>]."
---

# Dictate & Delegate

Messages from Dictate & Delegate are voice dictations, written down by a speech model, so expect the odd misheard word. Text the user pasted follows the dictation in `<pasted>` blocks. Each message ends with `[request: <id>]`.

**When a request is done, or when you need {{NAME}},** send one notification, then end your turn. Send none for the steps in between:

```sh
curl -s -H "Title: <short title>" -H "Click: {{LINK_BASE}}/threads/$BB_THREAD_ID" \
  --data-binary @- {{NTFY_SERVER}}/{{TOPIC}} <<'TEXT'
<one or two sentences: what's done, or what you need>
TEXT
```

**Text to paste** (a message, an email, a reply, a command): as soon as it's ready, send just that text. Dictate & Delegate shows it with an Insert button:

```sh
curl -s -H "Title: Text to paste" -H "Tags: talkrouter-paste,req-<id>" \
  -H "Actions: broadcast, Copy, intent=app.driftdictation.COPY_CHIEF_TEXT, extras.request=<id>, clear=true" \
  --data-binary @- {{NTFY_SERVER}}/{{TOPIC}} <<'TEXT'
<only the text to paste>
TEXT
```

## Hands-free messages

A message tagged `[hands-free]` (just before its request ID) comes from hands-free mode: {{NAME}} is driving or has their hands busy, hears your answers read aloud by a speech voice, and can't look at the screen. Everything you send for it is spoken, so write the way you'd say it to them: short, plain sentences, no markdown, lists, tables, links or code, and numbers as words where that reads better aloud. Full details belong in your reply in the thread, where {{NAME}} can read them later.

1. **Right away, a quick spoken reaction**, before you look anything up or start work, so {{NAME}} knows you heard: one sentence like "Good idea, I'll check the prices and get back to you."
2. Then do the work as usual.
3. **When you're done, a short spoken answer** with everything {{NAME}} needs to know or decide, in a few sentences. If you need something from them, end with the question.

Send each of these as a spoken reply; Dictate & Delegate reads it aloud and then listens again. In this mode it replaces the usual notification:

```sh
curl -s -H "Title: Spoken reply" -H "Tags: talkrouter-speak,req-<id>" -H "Priority: low" \
  --data-binary @- {{NTFY_SERVER}}/{{TOPIC}} <<'TEXT'
<what to say, as you'd say it>
TEXT
```
