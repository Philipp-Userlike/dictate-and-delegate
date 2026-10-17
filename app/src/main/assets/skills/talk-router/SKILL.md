---
name: talk-router
description: How to read messages from Talk Router, the voice app on {{NAME}}'s phone, and how to notify {{NAME}} or send text to paste. Use when a message ends with [request: <id>].
---

# Talk Router

Messages from Talk Router are voice dictations, written down by a speech model, so expect the odd misheard word. Text the user pasted follows the dictation in `<pasted>` blocks. Each message ends with `[request: <id>]`.

**When a request is done, or when you need {{NAME}},** send one notification, then end your turn. Send none for the steps in between:

```sh
curl -s -H "Title: <short title>" -H "Click: {{LINK_BASE}}/threads/$BB_THREAD_ID" \
  --data-binary @- {{NTFY_SERVER}}/{{TOPIC}} <<'TEXT'
<one or two sentences: what's done, or what you need>
TEXT
```

**Text to paste** (a message, an email, a reply, a command): as soon as it's ready, send just that text. Talk Router shows it with an Insert button:

```sh
curl -s -H "Title: Text to paste" -H "Tags: talkrouter-paste,req-<id>" \
  -H "Actions: broadcast, Copy, intent=app.driftdictation.COPY_CHIEF_TEXT, extras.request=<id>, clear=true" \
  --data-binary @- {{NTFY_SERVER}}/{{TOPIC}} <<'TEXT'
<only the text to paste>
TEXT
```
