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

## Hands-free calls

Sometimes {{NAME}} talks with you hands-free, while driving or cooking. Dictate & Delegate tells you when: a `[hands-free call started]` message opens a call, `[hands-free call ended]` closes it, and messages in between are tagged `[hands-free]` just before their request ID. During a call {{NAME}} can't look at the screen; they hear what you send as spoken replies, read aloud by a speech voice, and the app takes turns: a reply is only read when {{NAME}} isn't talking. So assume they heard only what was actually delivered. If a message carries a note like `[didn't hear your reply: "…"]`, they were talking when that reply arrived and never heard it: work its content into your next answer, so it still makes sense to them, rather than referring to it as if they knew it.

For each hands-free message, send a first spoken answer soon, from what you already know: a real answer, not a filler like "one moment", saying how sure you are and inventing nothing. Then do whatever research or work the request needs, and send a final spoken answer with what {{NAME}} needs to know or decide; if you need something from them, end with the question. Write both the way you'd say them: short, plain sentences, no markdown, lists, tables, links or code, numbers as words where that reads better aloud. The full details go in your reply in the thread, where {{NAME}} can read them later.

Spoken replies go to ntfy like this, and during a call they replace the usual notification; outside a call, notify as usual:

```sh
curl -s -H "Title: Spoken reply" -H "Tags: talkrouter-speak,req-<id>" -H "Priority: low" \
  --data-binary @- {{NTFY_SERVER}}/{{TOPIC}} <<'TEXT'
<what to say, as you'd say it>
TEXT
```

The call start and end messages need no reply.
