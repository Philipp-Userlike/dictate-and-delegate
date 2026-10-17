#!/usr/bin/env python3
"""Real models, real money (a few cents, on your own OpenRouter key): recordings through the
default speech model, then each dictation through the request check and, when it asks for
something, the text model, the way the app calls OpenRouter. The check runs as for a user with a
chief of staff in bb; dictations meant for the chief are only routed, not sent. Writes a Markdown
report.

  set -a; . .tools/openrouter.env; set +a   # OPENROUTER_API_KEY
  scripts/qa/real-check.py out.md [rec1.wav rec2.wav …]
"""
import base64
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OPENROUTER = os.environ.get("OPENROUTER_URL", "https://openrouter.ai/api/v1")
SPEECH = "microsoft/mai-transcribe-2"
WORDS = ["Drift", "Jana"]
DICTATIONS = [  # What people say, as a speech model writes it down.
    "uh hi Marco can you bring two more boxes of tulips tomorrow the red ones no wait the yellow"
    " ones thanks",
    "dear Mr Weber the tap in the kitchen no actually the one in the bathroom has been dripping"
    " since Monday could someone come by I'm home after five oh and make that a bit more formal"
    " please",
    "so in the Kubernetes config the replica count should be three not two and uh add a comment"
    " about the Ljubljana data center",
    "hey Jana ich bin heute etwas später so gegen halb sieben, kannst du schon mal die Pizza"
    " bestellen, ähm, die mit Pilzen, nicht Salami",
    "just a transcript please, the server is down again",
    "reply to the landlord that the viewing on Saturday works for me, polite and short",
]


# Dictations with the route the check should pick when a chief of staff is set up.
ROUTED = [
    ("tell my chief of staff to look into why the nightly build failed and fix it", "chief"),
    ("chief of staff, book a table for four at Lisboa on Friday at eight", "chief"),
    ("let the agent handle this, update the dependencies in the drift repo and open a PR", "chief"),
    ("ask my agent to compare the three flight offers I pasted and pick the cheapest", "chief"),
    ("research what the best open-source speech models are right now and summarise them for me",
     "chief"),
    ("research shows that people sleep better in cold rooms, so turn the heating down tonight",
     "transcript"),
    ("hi Tom, my chief of staff will send you the contract tomorrow", "transcript"),
    ("write an email to my landlord that the heating is broken, polite", "text"),
    ("can you ask your agent to call me back about the offer", "transcript"),
]

TEMPLATE = ("Apartment application", "Dear Sir or Madam,\n\nI am very interested in the apartment"
            " at [address]. I am 34, work as a software developer with a permanent contract and a net"
            " income of 4,200 euros a month. I don't smoke and have no pets.\n\nI would be glad to"
            " come to a viewing.\n\nKind regards\nJonas Becker")
TEMPLATE_DICTATIONS = [
    "use my apartment application for the two room flat on Hauptstraße 5 the viewing is on"
    " Saturday at ten and say that I work from home two days a week",
    "hi Jana can you send me the photos from Saturday",
]


def java_constant(name, file="TextModel.java"):
    code = open(os.path.join(ROOT, "app/src/main/java/app/driftdictation/" + file)).read()
    block = re.search(name + r"\s*=\s*(.*?);\n", code, re.S).group(1)
    return "".join(json.loads('"' + s + '"') for s in re.findall(r'"((?:[^"\\]|\\.)*)"', block))


def java_string(name, file):
    code = open(os.path.join(ROOT, "app/src/main/java/app/driftdictation/" + file)).read()
    return re.search(name + r'\s*=\s*"([^"]+)"', code).group(1)


def post(path, body):
    req = urllib.request.Request(OPENROUTER + path, json.dumps(body).encode(), {
        "Authorization": "Bearer " + os.environ["OPENROUTER_API_KEY"],
        "Content-Type": "application/json", "X-Title": "Talk Router"})
    started = time.time()
    try:
        r = json.loads(urllib.request.urlopen(req, timeout=120).read())
    except urllib.error.HTTPError as e:
        r = {"error": e.code, "body": e.read().decode(errors="replace")[:300]}
    return r, time.time() - started, (r.get("usage") or {}).get("cost")


def chat(model, system, user, max_tokens, fast=False):
    body = {"model": model, "usage": {"include": True}, "max_tokens": max_tokens,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]}
    if fast:
        body["reasoning"] = {"enabled": False}
    r, seconds, cost = post("/chat/completions", body)
    try:
        text = r["choices"][0]["message"]["content"].strip()
    except (KeyError, IndexError, TypeError, AttributeError):
        text = json.dumps(r)[:200]
    return text, seconds, cost


def money(cost):
    return "–" if cost is None else "$%.6f" % cost


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    out, recordings = sys.argv[1], sys.argv[2:]
    router, text_model = java_string("MODEL", "Router.java"), java_string("MODEL", "TextModel.java")
    check_prompt = (java_constant("PROMPT", "Router.java").replace("%s", java_constant("CHIEF_ROUTE", "Router.java"))
                    + "\n\nThe user's templates: " + TEMPLATE[0])
    prompt = java_constant("DEFAULT_PROMPT")
    template = ("\n\n" + java_constant("INSTRUCTIONS", "Templates.java")
                + "\n\n<template name=\"%s\">\n%s\n</template>" % TEMPLATE)
    lines = ["# Real-model check", "", "Speech `%s`, request check `%s`, text `%s`." % (SPEECH, router, text_model), ""]
    total = 0.0
    inputs = []
    for path in recordings:
        r, seconds, cost = post("/audio/transcriptions", {
            "model": SPEECH, "response_format": "json",
            "input_audio": {"format": "wav", "data": base64.b64encode(open(path, "rb").read()).decode()},
            "provider": {"options": {"azure": {"phraseList": {"phrases": WORDS}}}}})
        total += cost or 0
        lines += ["## Recording %s" % os.path.basename(path), "",
                  "Transcript (%.1f s, %s): %s" % (seconds, money(cost), r.get("text", r)), ""]
        if "text" in r:
            inputs.append(r["text"])
    expected = dict(ROUTED)
    inputs += DICTATIONS + TEMPLATE_DICTATIONS + [d for d, _ in ROUTED]
    lines += ["| Dictation | Check | Result |", "| --- | --- | --- |"]
    right = 0
    for said in inputs:
        answer, seconds, cost = chat(router, check_prompt, said, 200, fast=True)
        total += cost or 0
        check = "%s (%.1f s, %s)" % (answer, seconds, money(cost))
        if said in expected:
            ok = '"%s"' % expected[said] in answer
            right += ok
            check += " %s expected %s" % ("✓" if ok else "✗", expected[said])
        result = said
        if '"chief"' in answer:
            result = "→ chief of staff (not sent by this check)"
        elif '"text"' in answer:
            system = prompt + (template if TEMPLATE[0].lower() in answer.lower() else "")
            result, seconds, cost = chat(text_model, system, said, 4096)
            total += cost or 0
            result = "%s (%.1f s, %s)" % (result, seconds, money(cost))
        cells = [c.replace("\n", "<br>").replace("|", "\\|") for c in (said, check, result)]
        lines.append("| %s | %s | %s |" % tuple(cells))
    lines += ["", "Chief cues: %d of %d routed as expected." % (right, len(ROUTED)),
              "", "Reported cost in total: $%.6f." % total]
    open(out, "w").write("\n".join(lines) + "\n")
    print(out)


if __name__ == "__main__":
    main()
