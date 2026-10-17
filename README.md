# Talk Router

**One intuitive shortcut to AI, from any app on your phone.**

Press a key and talk: dictation, text creation and independent agents, from one starting point.

<table>
<tr>
<td width="50%" valign="top"><img src="docs/images/anim-talk.gif" alt="A friend sends a café link; you talk about it while looking at the page, go back to the chat, insert the text and send it"></td>
<td width="50%" valign="top"><img src="docs/images/anim-refine.gif" alt="A template is saved in Talk Router; on an apartment listing you ask for an application from it, and the finished text goes into the contact form"></td>
</tr>
<tr>
<td valign="top"><b>Dictate while you look.</b> Talk about what's on screen; the text lands in your chat.</td>
<td valign="top"><b>Refine with a template.</b> <i>"Use my application template, mention that I have no pets."</i> The finished text goes into the form.</td>
</tr>
<tr>
<td colspan="2"><img src="docs/images/anim-agent.gif" alt="You ask your agent for a snake game; it builds it on your computer and sends a link; while playing you ask for changes, and it updates the game"></td>
</tr>
<tr>
<td colspan="2"><b>Hand it to your agent.</b> <i>"Ada, build me a little snake game."</i> Your agent builds it on your computer, and you improve it by talking while you play.</td>
</tr>
</table>

## What it does

### Dictate anywhere

Press a key in any app and talk. A small floating panel stays next to whatever you're doing, so you can keep browsing, reading or testing while you speak. When you stop, **📥 Insert** puts the text at your cursor. Talk Router never touches your clipboard on its own.

<img src="docs/images/panel-still.png" width="240" alt="The floating panel recording over a web page, with a speech bubble of what you say">

### Refine: one model call, any instruction

**✨ Refine** sends what you said through a single call to a text model: Claude Sonnet 5.5 by default, or any model you pick. In that one call, it follows:

- what you ask for in your dictation,
- a standing prompt you set yourself,
- your templates: predefined texts that the model fills in and adapts, or that you edit yourself.

That makes it fast and flexible: any text processing you can describe, in a few seconds. It starts by itself when you ask for a text or name a template, and it replaces the raw transcript in place.

### Hand off larger tasks to agents

Talk Router sends a task to an independent agent running on its own machine, your laptop or a server, through [bb](https://getbb.app), with Claude Code or Codex on your existing subscription. Say your agent's name, or tap 🧑‍💼. The agent works on its own with that machine's files and tools, splits big jobs into parallel work, and reports back with a notification or with text you can insert. It can schedule tasks too; those run while that machine is on.

### The best models, pay per use

The latest speech models: MAI-Transcribe 2 by default, 60 languages, and you can switch languages mid-sentence. You pay per use at the providers' prices, about $0.10 per hour of audio, so half an hour of talking a day comes to about $1.50 a month. There's no subscription, and every note shows what it cost.

### Open source, private by design

No Talk Router server, no account, no analytics. You use your own keys, and notes stay encrypted on the phone. It's MIT licensed, so you can read exactly what it sends. [Privacy details](docs/PRIVACY.md)

## Install

**You need:** an [OpenRouter](https://openrouter.ai) key and an Android phone with Android 14 or newer. For agents: [bb](https://getbb.app) with Claude Code or Codex, and the free [ntfy](https://ntfy.sh) app for notifications.

1. Download the APK from [Releases](https://github.com/Philipp258/talk-router/releases/latest) and open it on your phone.
2. Open Talk Router and paste your OpenRouter key. Setup walks you through the microphone, the start key and Insert.
3. Optional: in bb, run `bb plugin enable bb--mcp`, then sign in from Talk Router. It sets up your agent.

[How it works](docs/HOW-IT-WORKS.md) · [Using the app](docs/GUIDE.md) · [Build & test](docs/DEVELOPMENT.md) · [Privacy](docs/PRIVACY.md)
