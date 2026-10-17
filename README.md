# Talk Router

**One intuitive shortcut to AI, from any app on your phone.**

Press a key and talk: dictation, text creation and independent agents, from one starting point.

<p><b>Dictate while you look.</b> Talk about what's on screen; the text lands in your chat.</p>

<video src="https://github.com/Philipp258/talk-router/raw/main/docs/videos/talk.mp4" poster="https://github.com/Philipp258/talk-router/raw/main/docs/videos/talk-poster.png" controls muted playsinline width="320">
  <a href="docs/videos/talk.mp4"><img src="docs/videos/talk-poster.png" width="360" alt="A friend sends a café link; you talk about it while looking at the page, then insert the text into the chat. Click to play."></a>
</video>

<p><b>Refine with a template.</b> <i>"Use my application template, mention that I have no pets."</i> The finished text goes into the form.</p>

<video src="https://github.com/Philipp258/talk-router/raw/main/docs/videos/refine.mp4" poster="https://github.com/Philipp258/talk-router/raw/main/docs/videos/refine-poster.png" controls muted playsinline width="320">
  <a href="docs/videos/refine.mp4"><img src="docs/videos/refine-poster.png" width="360" alt="A template is saved in Talk Router; on an apartment listing you ask for an application from it, and the finished text goes into the contact form. Click to play."></a>
</video>

<p><b>Hand it to your agent.</b> <i>"Ada, build me a little snake game."</i> Your agent builds it on your computer, and you improve it by talking while you play.</p>

<video src="https://github.com/Philipp258/talk-router/raw/main/docs/videos/agent.mp4" poster="https://github.com/Philipp258/talk-router/raw/main/docs/videos/agent-poster.png" controls muted playsinline width="640">
  <a href="docs/videos/agent.mp4"><img src="docs/videos/agent-poster.png" width="360" alt="You ask your agent for a snake game; it builds it on your computer; while playing you ask for changes, and it updates the game. Click to play."></a>
</video>

## What it does

### Dictate anywhere

Press a shortcut in any app and talk. A small floating panel stays next to whatever you're doing, so you can keep browsing, reading or testing while you speak. When you're done, you can use the transcript anywhere.

<img src="docs/images/panel-still.png" width="240" alt="The floating panel recording over a web page, with a speech bubble of what you say">

### Refine: one model call, any instruction

**✨ Refine** sends what you said through a single call to a text model. It quickly processes or generates any text you need, based on:

- a default prompt (roughly "find the intention of the text and improve it towards it"),
- predefined text templates (like a sample job application),
- any instruction you dictate ("Write an application to startup X based on our template. Emphasize that…").

### Hand off larger tasks to agents

Talk Router sends your dictation to an agent running on its own machine, your laptop or a server, through [bb](https://getbb.app), with Claude Code or Codex on your existing subscription. Usually you'd have a kind of "coordinator" or "chief of staff" agent there that routes and manages all larger independent work. This can include coding, research, recurring tasks and a lot of other things.

### The best models, pay per use

The latest speech models: MAI-Transcribe 2 by default, 60 languages, and you can switch languages mid-sentence. You pay per use at the providers' prices, about $0.10 per hour of audio, so half an hour of talking a day comes to about $1.50 a month. There's no subscription, and every note shows what it cost.

### Open source, private by design

No Talk Router server, no account, no analytics. You use your own keys, and notes stay encrypted on the phone. It's MIT licensed, so you can read exactly what it sends. [Privacy details](docs/PRIVACY.md)

## Why bb

bb runs Claude Code and Codex and adds nice extra features on top. It works with common subscriptions like Claude and ChatGPT, has full remote control, and gives Talk Router a nice interface to communicate with.

## Install

**You need:** an [OpenRouter](https://openrouter.ai) key and an Android phone with Android 14 or newer. For agents: [bb](https://getbb.app) with Claude Code or Codex, and the free [ntfy](https://ntfy.sh) app for notifications.

1. Download the APK from [Releases](https://github.com/Philipp258/talk-router/releases/latest) and open it on your phone.
2. Open Talk Router and paste your OpenRouter key. Setup walks you through the microphone, the start key and Insert.
3. Optional: in bb, run `bb plugin enable bb--mcp`, then sign in from Talk Router. It sets up your agent.

[How it works](docs/HOW-IT-WORKS.md) · [Using the app](docs/GUIDE.md) · [Build & test](docs/DEVELOPMENT.md) · [Privacy](docs/PRIVACY.md)
