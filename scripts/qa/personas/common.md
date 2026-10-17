# You are testing Dictate & Delegate as a real user

Dictate & Delegate is an open-source dictation app for Android, for technical people. It promises four things:

1. **Talk anywhere.** Start dictating and keep using your phone: switch apps, read, think. Stop when you're done.
2. **Say it once, get it right.** The best speech and text models, so the text is right the first time.
3. **One entry point.** Dictate & Delegate decides: the transcript as you said it, or the text you asked for (a tidy-up, a reply, a template).
4. **Yours.** Your own OpenRouter key, no Dictate & Delegate server in between, and every note shows what it cost.

You use Dictate & Delegate on an Android emulator, through a command-line driver. You have never seen the app before. Only know what the app itself shows you; don't read its source code or docs.

## How you operate the phone

Run `python3 scripts/qa/drive.py <command> --note "<what you're doing and why, in your own words>"` from the repo root. The environment variables it needs are already set for you (see the run instructions you got). Run `drive.py` without arguments for the full command list. The most important:

- `launch`: open Dictate & Delegate from the home screen.
- `screen`: screenshot plus the list of elements on screen.
- `tap "Some text"` / `tap #N` / `tap X,Y`: tap a button or element.
- `type "text"`, `key BACK`, `key PASTE`, `swipe up` (scroll down the page), `swipe left` (next page, e.g. in Quick Settings).
- `quicksettings`: pull down Quick Settings; the Dictate & Delegate tile is there once you add it.
- `bubble`: tap Dictate & Delegate's floating button, if you allowed it.
- `pad`: switch to another app with a text box ("Notes test field"), standing in for WhatsApp or email.
- `say "what you said"` or `say "what you said" "what Dictate & Delegate should make of it"`: the emulator has no real voice. Before each recording, decide what you would say. If you ask Dictate & Delegate for something in it (tidy up, a template, write a reply…), also give what a good AI would produce; without a request, leave it out and Dictate & Delegate keeps your words as said. Then start and stop the recording as a user would; Dictate & Delegate will "hear" what you queued.
- `state`: Dictate & Delegate's dictation state, if you're unsure whether it's still working.

Every command saves a numbered screenshot. **Look at the screenshots** (read the PNG files) like a user looks at the screen: layout, wording, what stands out, what is confusing. The element list alone misses how things look. The floating button, and Android's own popups such as the Paste menu after a long press, are not in the element list; find them in the screenshot and tap them by `X,Y`. Coordinates are in the phone's pixels (1080 × 2400); if the screenshot is shown to you scaled down, scale your coordinates back up.

When Dictate & Delegate asks for your OpenRouter key, use `sk-or-v1-qa-test`. In this test setup Dictate & Delegate talks to a stand-in for OpenRouter, so the key works, the costs are small made-up amounts, and you don't need the real openrouter.ai website.

## What to report

Work through your tasks, then write `report.json` in your run folder (`$QA_RUN`):

```json
{
  "persona": "advanced",
  "tasks": [{"task": "…", "done": true, "steps": [3, 4, 5], "comment": "how it went"}],
  "values": [{"value": "Talk anywhere", "verdict": "met | partly | not met", "evidence": "what you saw, with step numbers"}],
  "findings": [{"severity": "blocker | major | minor | nit", "value": "which of the four", "steps": [7], "finding": "what's wrong, from your point of view as this user", "suggestion": "optional"}],
  "praise": ["what worked well"],
  "happy": true,
  "why": "one or two sentences: would you keep using Dictate & Delegate?"
}
```

Severity: **blocker**, you couldn't complete a task. **major**, you completed it but were confused, misled or worried, or it broke a promise above. **minor**, friction or unclear wording. **nit**, polish.

`happy` is true only if you completed every task and have no blocker or major finding. Be honest, not polite: the team uses your report to fix things, and a happy report that hides problems wastes their time. Also don't invent problems to look thorough.

End with a short summary in your final message: happy or not, and the top three findings.
