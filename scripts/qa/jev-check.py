#!/usr/bin/env python3
"""Jev on labelled dictations, with real money (about $0.002 per run on your own OpenRouter key).

Asks Jev (POST /api/alpha/decisions) exactly what the app asks: the questions, instructions and
criteria are read from app/src/main/java/app/driftdictation/Jev.java. Each dictation is labelled
with what should happen: refine or not (None: either is fine), the template it names, and whether
it explicitly addresses the chief of staff. Prints accuracy per question and per threshold, and
writes a Markdown report.

  set -a; . .tools/openrouter.env; set +a   # OPENROUTER_API_KEY
  scripts/qa/jev-check.py qa-out/jev.md
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
URL = os.environ.get("OPENROUTER_DECISIONS", "https://openrouter.ai/api/alpha/decisions")
JEV = os.path.join(ROOT, "app/src/main/java/app/driftdictation/Jev.java")
TEMPLATES = ["Apartment application", "Weekly update", "Absage Termin"]

# (dictation, refine, template, chief). refine None: either answer is fine.
LABELLED = [
    # Plain messages and notes: nothing happens by itself.
    ("hi Marco can you bring two more boxes of tulips tomorrow, the yellow ones, thanks", False, "", False),
    ("Call Jana tomorrow at ten, then send the invoice for September.", False, "", False),
    ("note to self: the replica count should be three, not two", False, "", False),
    ("just a transcript please, the server is down again", False, "", False),
    ("Thanks for the update, see you on Monday.", False, "", False),
    ("can you write me a short summary of the meeting when you get a chance", False, "", False),
    ("remind me to buy milk", False, "", False),
    ("please call me back when you can", False, "", False),
    ("the build is red again, someone should look at it", False, "", False),
    ("tell Sarah I'm running ten minutes late", False, "", False),
    ("Hey Jana, ich bin heute etwas später, so gegen halb sieben, kannst du schon mal die Pizza bestellen, die mit Pilzen", False, "", False),
    ("Morgen um zehn mit Jana telefonieren, danach die Rechnung für September schicken", False, "", False),
    ("Kannst du mir bitte die Fotos von Samstag schicken", False, "", False),
    ("Notiz an mich: Ersatzteil für die Spülmaschine bestellen", False, "", False),
    ("Hallo Frau Schmidt, vielen Dank für Ihre Nachricht, ich melde mich morgen.", False, "", False),
    # Requests for a text, and messy dictations: Refine.
    ("uh hi Marco can you bring two more boxes of tulips tomorrow the red ones no wait the yellow ones um thanks", True, "", False),
    ("dear Mr Weber the tap in the bathroom has been dripping since Monday, could someone come by, I'm home after five, make that a bit more formal please", True, "", False),
    ("reply to the landlord that the viewing on Saturday works for me, polite and short", True, "", False),
    ("turn this into bullet points: milk, eggs, bread and the batteries for the smoke detector", True, "", False),
    ("translate to German: the meeting is moved to Thursday at three", True, "", False),
    ("write an email to my landlord that the heating is broken, polite", True, "", False),
    ("okay so um the thing is I wanted to say that uh the meeting, the meeting tomorrow is, no actually it's on Wednesday, at, um, two", True, "", False),
    ("make this shorter: we would like to inform you that due to unforeseen circumstances the event has been postponed", True, "", False),
    ("I need you to write a cover letter for the backend job at Spotify, based on my CV", True, "", False),
    ("summarise the pasted article in three bullet points", True, "", False),
    ("ähm also Herr Weber, der Küchenhahn tropft seit Montag, könnte jemand vorbeikommen, ich bin ab fünf zu Hause, mach daraus bitte eine höfliche Mail", True, "", False),
    ("Übersetz das ins Englische: Das Treffen ist auf Donnerstag verschoben", True, "", False),
    ("Mach daraus eine Liste: Milch, Eier, Brot, Batterien", True, "", False),
    ("Schreib eine Antwort an den Vermieter, dass mir der Besichtigungstermin am Samstag passt", True, "", False),
    ("äh also das Meeting ist, nein warte, das Meeting ist am Mittwoch, um, äh, zwei, glaube ich, ja zwei", True, "", False),
    ("Formuliere das formeller: Hi, kann ich am Montag freinehmen?", True, "", False),
    # Templates.
    ("use my apartment application for the flat on Hauptstraße 5, the viewing is Saturday at ten", True, "Apartment application", False),
    ("fill in my weekly update: shipped the panel, fixed two crashes, next week the volume key", True, "Weekly update", False),
    ("use the apartment application template but for the place on Lindenstraße", True, "Apartment application", False),
    ("Nutze meine Vorlage Absage Termin für den Zahnarzt am Dienstag", True, "Absage Termin", False),
    ("Fülle mein Weekly update aus: Panel fertig, zwei Abstürze behoben", True, "Weekly update", False),
    # The chief, explicitly addressed: sent by itself.
    ("chief of staff, book a table for four at Lisboa on Friday at eight", False, "", True),
    ("tell my chief of staff to look into why the nightly build failed and fix it", False, "", True),
    ("ask my agent to compare the three flight offers I pasted and pick the cheapest", None, "", True),
    ("let the agent handle this: update the dependencies in the drift repo and open a PR", False, "", True),
    ("my agent, draft a reply to Sarah saying I can't make it Thursday", None, "", True),
    ("chief of staff, write a polite email to the landlord about the broken heating and send me the text", None, "", True),
    ("hey agent, what's the weather tomorrow", False, "", True),
    ("hey chief, check whether the invoice from Hetzner arrived and pay it", False, "", True),
    ("chief of staff: what's on my calendar tomorrow?", False, "", True),
    ("Chief of Staff, buch mir einen Tisch für vier Personen am Freitag um acht im Lisboa", False, "", True),
    ("Sag meinem Chief of Staff, er soll herausfinden, warum der Nightly-Build fehlgeschlagen ist", False, "", True),
    ("Frag meinen Agenten, welches der drei Angebote am günstigsten ist", False, "", True),
    ("Lass den Agenten das erledigen: die Abhängigkeiten im Repo aktualisieren und einen PR aufmachen", False, "", True),
    ("Chief of Staff, schreib eine kurze Absage an das Team für das Offsite und schick mir den Text", None, "", True),
    ("Agent, bitte prüf, ob die Rechnung von Hetzner da ist", False, "", True),
    ("Schick meinem Agenten: bitte den Flug nach Lissabon umbuchen", False, "", True),
    # Tasks without an address, and mentions that aren't one: never sent.
    ("look into why the nightly build failed and fix it", False, "", False),
    ("research the best open-source speech models right now and summarise them", None, "", False),
    ("schedule a call with the design team next week", False, "", False),
    ("hey Tom, my chief of staff will send you the contract tomorrow", False, "", False),
    ("can you ask your agent to call me back about the offer", False, "", False),
    ("I think the chief of staff role at the company should be redefined", False, "", False),
    ("Finde heraus, warum der Nightly-Build fehlgeschlagen ist, und behebe es", False, "", False),
    ("Mein Chief of Staff schickt dir morgen den Vertrag", False, "", False),
    ("Recherchier, welche Spracherkennungsmodelle gerade am besten sind", None, "", False),
    ("Ich überlege, ob wir einen Chief of Staff einstellen sollten", False, "", False),
]


def java_constant(name):
    """A String constant from Jev.java: its literals joined, like javac does."""
    code = open(JEV, encoding="utf-8").read()
    lit = r'"(?:[^"\\]|\\.)*"'
    block = re.search(r"\b" + name + r"\s*=\s*(" + lit + r"(?:\s*\+\s*" + lit + r")*)", code).group(1)
    return "".join(json.loads('"' + s + '"') for s in re.findall(r'"((?:[^"\\]|\\.)*)"', block))


def java_number(name):
    code = open(JEV, encoding="utf-8").read()
    return float(re.search(r"\b" + name + r"\s*=\s*([0-9.]+)", code).group(1))


def body(dictation):
    """Jev.body(dictation, TEMPLATES, true, true), as the app builds it."""
    noul = lambda q, yes, no: {"type": "noul", "instructions": java_constant(q),
                               "criteria": {"true": java_constant(yes), "false": java_constant(no)}}
    options = {"none": java_constant("TEMPLATE_NONE")}
    for name in TEMPLATES:
        options[name] = java_constant("TEMPLATE_OPTION").replace("%s", name)
    return {"model": java_constant("MODEL"), "state": {"dictation": dictation},
            "questions": {"refine": noul("REFINE", "REFINE_YES", "REFINE_NO"),
                          "template": {"type": "choice", "instructions": java_constant("TEMPLATE"),
                                       "criteria": options},
                          "chief": noul("CHIEF", "CHIEF_YES", "CHIEF_NO")}}


def ask(dictation):
    req = urllib.request.Request(URL, json.dumps(body(dictation)).encode(), {
        "Authorization": "Bearer " + os.environ["OPENROUTER_API_KEY"],
        "Content-Type": "application/json", "X-Title": "Talk Router"})
    started = time.time()
    try:
        r = json.loads(urllib.request.urlopen(req, timeout=30).read())
    except urllib.error.HTTPError as e:
        r = {"error": e.code, "body": e.read().decode(errors="replace")[:300]}
    return r, time.time() - started


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    with ThreadPoolExecutor(8) as pool:
        results = list(pool.map(lambda d: ask(d[0]), LABELLED))
    refine_at, chief_at, hint_at = java_number("REFINE_AT"), java_number("CHIEF_AT"), java_number("HINT_AT")
    rows, cost, seconds, failed = [], 0.0, [], 0
    for (said, refine, template, chief), (r, s) in zip(LABELLED, results):
        a = r.get("answers")
        if not a:
            failed += 1
            rows.append((said, refine, template, chief, None, None, None, json.dumps(r)[:200]))
            continue
        cost += (r.get("usage") or {}).get("cost") or 0
        seconds.append(s)
        choice = a.get("template", {}).get("choice", "none")
        rows.append((said, refine, template, chief, a["refine"]["noul"], "" if choice == "none" else choice,
                     a["chief"]["noul"], ""))
    ok = [r for r in rows if r[4] is not None]
    lines = ["# Jev check", "", "Model `%s`, %d dictations (%d failed), $%.6f in total, median %.2f s."
             % (java_constant("MODEL"), len(rows), failed, cost, sorted(seconds)[len(seconds) // 2]), ""]

    def refine_acc(t):
        judged = [r for r in ok if r[1] is not None]
        right = sum(((r[4] >= t) or r[5] != "") == r[1] for r in judged)
        return right, len(judged)

    lines.append("## Refine (by itself at p ≥ threshold, or when a template is named)")
    lines.append("")
    for t in (0.3, 0.4, 0.5, 0.6, 0.7):
        right, n = refine_acc(t)
        lines.append("- %.1f: %d of %d right%s" % (t, right, n, "  ← used" if abs(t - refine_at) < 1e-9 else ""))
    tpl = sum(r[5] == r[2] for r in ok)
    lines += ["", "## Template", "", "%d of %d right." % (tpl, len(ok)), ""]
    lines.append("## Chief (sent by itself at p ≥ threshold; hint from %.1f)" % hint_at)
    lines.append("")
    for t in (0.7, 0.8, 0.9, 0.95):
        sent_wrong = sum(r[6] >= t and not r[3] for r in ok)
        sent_right = sum(r[6] >= t and r[3] for r in ok)
        addressed = sum(r[3] for r in ok)
        lines.append("- %.2f: %d of %d addressed sent, %d sent without an address%s"
                     % (t, sent_right, addressed, sent_wrong, "  ← used" if abs(t - chief_at) < 1e-9 else ""))
    hints = sum(hint_at <= r[6] < chief_at for r in ok)
    lines += ["", "Hints (🧑‍💼 highlighted, nothing sent): %d." % hints, "",
              "| Dictation | refine | template | chief | |", "| --- | --- | --- | --- | --- |"]
    for said, refine, template, chief, p_r, t, p_c, err in rows:
        if p_r is None:
            lines.append("| %s | | | | ✗ %s |" % (said, err))
            continue
        marks = []
        if refine is not None and ((p_r >= refine_at) or t != "") != refine:
            marks.append("refine")
        if t != template:
            marks.append("template")
        if (p_c >= chief_at) != chief:
            marks.append("chief")
        lines.append("| %s | %.2f%s | %s | %.2f%s | %s |" % (
            said.replace("|", "\\|"), p_r, "" if refine is None else (" (yes)" if refine else " (no)"),
            t or "–", p_c, " (yes)" if chief else " (no)", "✗ " + ", ".join(marks) if marks else "✓"))
    open(sys.argv[1], "w").write("\n".join(lines) + "\n")
    print("\n".join(lines[:30]))


if __name__ == "__main__":
    main()
