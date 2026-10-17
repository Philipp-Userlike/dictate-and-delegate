#!/usr/bin/env python3
"""Drives Drift on the emulator for a QA agent. Every command saves a numbered screenshot.

  drive.py <command> [arguments] [--note "what you are doing and why"]

Commands:
  launch                 open Drift (as a user would from the launcher)
  screen                 just look: screenshot plus the list of what is on screen
  tap TEXT | tap #N | tap X,Y
                         tap the element with this text or description (exact, else contains),
                         element N from the last list, or a point in screenshot pixels
  longpress TEXT | #N | X,Y
  type TEXT              type into the focused field
  key BACK|HOME|ENTER|PASTE|RECENTS
  swipe up|down          scroll the page (up shows what is further down)
  swipe left|right       next / previous page, e.g. of Quick Settings
  quicksettings          pull down Quick Settings (where the Drift tile is once added)
  collapse               close Quick Settings / the notification shade
  bubble                 tap Drift's floating button (start, or stop while recording)
  pad                    open "Notes test field", another app with a text box to paste into
  say SAID [PROCESSED]   decide what the next recording "contains": the speech model will return
                         SAID; with PROCESSED, Drift treats it as a request and returns PROCESSED,
                         otherwise as a plain transcript
  state                  Drift's dictation state, e.g. recording, uploading, copied
  wait SECONDS
  note TEXT              write down an observation (with a screenshot), no action

Run folder: $QA_RUN (screenshots NNN.png and steps.jsonl). The emulator, adb and the fake
OpenRouter come from scripts/qa/reset.sh, which also prints the environment to use.
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

PACKAGE = "app.driftdictation"
RUN = os.environ.get("QA_RUN") or sys.exit("Set QA_RUN to this agent's run folder.")
SERVER = os.environ.get("QA_SERVER", "http://127.0.0.1:8798")
ADB = os.environ.get("ADB", "adb").split()
os.makedirs(RUN, exist_ok=True)


def adb(*args, binary=False, check=False):
    out = subprocess.run(ADB + list(args), capture_output=True, timeout=60)
    if check and out.returncode != 0:
        sys.exit("adb failed: " + out.stderr.decode(errors="replace"))
    return out.stdout if binary else out.stdout.decode(errors="replace")


def shell(*args):
    return adb("shell", *args)


def density():
    m = re.search(r"(\d+)\s*$", shell("wm", "density").strip())
    return int(m.group(1)) / 160 if m else 2.625


def prefs():
    """Drift's settings, readable because the debug build is debuggable."""
    xml = adb("exec-out", "run-as", PACKAGE, "cat", "shared_prefs/settings.xml")
    values = {}
    try:
        for e in ET.fromstring(xml):
            values[e.get("name")] = e.get("value", e.text)
    except ET.ParseError:
        pass
    return values


def elements():
    """Visible nodes of the current window as (label, kind, bounds, flags)."""
    shell("uiautomator", "dump", "/sdcard/qa.xml")
    xml = adb("exec-out", "cat", "/sdcard/qa.xml")
    found = []
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return found
    for n in root.iter("node"):
        text, desc = n.get("text", ""), n.get("content-desc", "")
        b = list(map(int, re.findall(r"\d+", n.get("bounds", "[0,0][0,0]"))))
        if b[2] - b[0] <= 0 or b[3] - b[1] <= 0:
            continue
        flags = [f for f in ("clickable", "checkable", "checked", "focused", "scrollable", "enabled")
                 if n.get(f) == "true"]
        if "enabled" in flags:
            flags.remove("enabled")
        else:
            flags.append("disabled")
        label = text or desc
        if not label and not ({"clickable", "checkable", "scrollable"} & set(flags)):
            continue
        kind = n.get("class", "").split(".")[-1]
        found.append({"label": label, "desc": desc if text else "", "kind": kind, "bounds": b,
                      "flags": flags, "package": n.get("package", "")})
    return found


def last_elements():
    path = os.path.join(RUN, "last-screen.json")
    return json.load(open(path)) if os.path.exists(path) else []


def find(target):
    if re.fullmatch(r"\d+,\d+", target):
        x, y = map(int, target.split(","))
        return x, y, target
    if target.startswith("#"):
        items = last_elements()
        i = int(target[1:])
        if i >= len(items):
            sys.exit("No element %s on the last screen." % target)
        e = items[i]
    else:
        items = elements()
        exact = [e for e in items if target in (e["label"], e["desc"])]
        loose = [e for e in items if target.lower() in (e["label"] + " " + e["desc"]).lower()]
        if not (exact or loose):
            sys.exit('Nothing on screen matches "%s". Run screen, or tap by #N or X,Y '
                     '(the floating button is not in the list: use bubble).' % target)
        e = (exact or loose)[0]
    b = e["bounds"]
    return (b[0] + b[2]) // 2, (b[1] + b[3]) // 2, e["label"] or e["desc"] or e["kind"]


def step_number():
    path = os.path.join(RUN, "steps.jsonl")
    return sum(1 for _ in open(path)) + 1 if os.path.exists(path) else 1


def record(command, note, settle=1.2):
    time.sleep(settle)
    n = step_number()
    shot = "%03d.png" % n
    with open(os.path.join(RUN, shot), "wb") as f:
        f.write(adb("exec-out", "screencap", "-p", binary=True))
    items = elements()
    json.dump(items, open(os.path.join(RUN, "last-screen.json"), "w"))
    p = prefs()
    step = {"n": n, "command": command, "note": note, "screenshot": shot,
            "time": time.strftime("%H:%M:%S"), "drift_state": p.get("state", ""),
            "screen": [e["label"] for e in items if e["label"]][:60]}
    with open(os.path.join(RUN, "steps.jsonl"), "a") as f:
        f.write(json.dumps(step, ensure_ascii=False) + "\n")
    print("Step %d · screenshot %s" % (n, os.path.join(RUN, shot)))
    print("Drift state: %s %s" % (p.get("state", "-"), ("· " + p["detail"]) if p.get("detail") else ""))
    print("On screen (tap by text or #N):")
    for i, e in enumerate(items):
        extra = (" [" + ", ".join(e["flags"]) + "]") if e["flags"] else ""
        desc = (' desc="%s"' % e["desc"]) if e["desc"] else ""
        print('  #%d %s "%s"%s%s' % (i, e["kind"], e["label"], desc, extra))


def queue(body):
    urllib.request.urlopen(urllib.request.Request(SERVER + "/test/reset", b"{}", {"Content-Type": "application/json"}), timeout=10).read()
    req = urllib.request.Request(SERVER + "/test/script", json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    urllib.request.urlopen(req, timeout=10).read()


def script_fake(said, processed):
    # With a processed text the request check finds a request; without one, nothing was asked.
    route = "text" if processed else "transcript"
    queue({"transcribe": [{"text": said}], "text": [{"text": processed or said}],
           "route": [{"text": '{"route": "%s", "template": ""}' % route}]})


def main():
    args = sys.argv[1:]
    note = ""
    if "--note" in args:
        i = args.index("--note")
        note = " ".join(args[i + 1:])
        args = args[:i]
    if not args:
        sys.exit(__doc__)
    cmd, rest = args[0], args[1:]
    command = " ".join(args)
    if cmd == "launch":
        shell("am", "start", "-n", PACKAGE + "/.MainActivity", "--ez", "debugScreenshots", "true")
        return record(command, note, 2.0)
    if cmd in ("screen", "note"):
        if cmd == "note" and rest:
            note = " ".join(rest) + ((" · " + note) if note else "")
        return record(command, note, 0.3)
    if cmd in ("tap", "longpress"):
        x, y, label = find(" ".join(rest))
        if cmd == "tap":
            shell("input", "tap", str(x), str(y))
        else:
            shell("input", "swipe", str(x), str(y), str(x), str(y), "900")
        return record("%s %s" % (cmd, label), note)
    if cmd == "type":
        text = " ".join(rest)
        escaped = re.sub(r"([\\\"'`$&|;<>()!?*#~])", r"\\\1", text).replace(" ", "%s")
        shell("input", "text", escaped)
        return record(command, note, 0.8)
    if cmd == "key":
        codes = {"BACK": "KEYCODE_BACK", "HOME": "KEYCODE_HOME", "ENTER": "KEYCODE_ENTER",
                 "PASTE": "KEYCODE_PASTE", "RECENTS": "KEYCODE_APP_SWITCH"}
        shell("input", "keyevent", codes.get(rest[0].upper(), rest[0]))
        return record(command, note)
    if cmd == "swipe":
        size = re.findall(r"\d+", shell("wm", "size"))
        w, h = (int(size[-2]), int(size[-1])) if len(size) >= 2 else (1080, 2400)
        if rest and rest[0] in ("left", "right"):
            a, b = (w * 8 // 10, w * 2 // 10) if rest[0] == "left" else (w * 2 // 10, w * 8 // 10)
            shell("input", "swipe", str(a), str(h * 3 // 10), str(b), str(h * 3 // 10), "300")
        elif rest and rest[0] == "down":
            shell("input", "swipe", str(w // 2), str(h * 3 // 10), str(w // 2), str(h * 7 // 10), "400")
        else:
            shell("input", "swipe", str(w // 2), str(h * 7 // 10), str(w // 2), str(h * 3 // 10), "400")
        return record(command, note)
    if cmd == "quicksettings":
        shell("cmd", "statusbar", "expand-settings")
        return record(command, note)
    if cmd == "collapse":
        shell("cmd", "statusbar", "collapse")
        return record(command, note)
    if cmd == "bubble":
        p, d = prefs(), density()
        x, y = int(p.get("bubbleX") or 16), int(p.get("bubbleY") or 220)
        recording = p.get("state") == "recording"
        # While recording the button is a 132 dp pill whose stop control is on the right.
        tx = x + int((132 - 24) * d) if recording else x + int(24 * d)
        shell("input", "tap", str(tx), str(y + int(24 * d)))
        return record(command, note)
    if cmd == "pad":
        shell("am", "start", "-n", PACKAGE + ".test/" + PACKAGE + ".TestPadActivity")
        return record(command, note, 1.5)
    if cmd == "say":
        if not rest:
            sys.exit('say needs what the recording contains, e.g. say "hi Jana see you at ten"')
        script_fake(rest[0], rest[1] if len(rest) > 1 else None)
        print("Queued: the next recording transcribes to %r%s." % (
            rest[0], "" if len(rest) < 2 else " and processes to %r" % rest[1]))
        return
    if cmd == "state":
        p = prefs()
        print("Drift state: %s · %s" % (p.get("state", "-"), p.get("detail", "")))
        return
    if cmd == "wait":
        time.sleep(float(rest[0]) if rest else 2)
        return record(command, note, 0.2)
    sys.exit("Unknown command %s.\n%s" % (cmd, __doc__))


if __name__ == "__main__":
    main()
