#!/usr/bin/env python3
"""Builds index.html for a QA round: every step's screenshot with the agent's note, its findings
and verdict.   scripts/qa/gallery.py qa-out/round-1
"""
import html
import json
import os
import sys

ROUND = sys.argv[1] if len(sys.argv) > 1 else sys.exit(__doc__)
ORDER = {"blocker": 0, "major": 1, "minor": 2, "nit": 3}
esc = html.escape


def persona(name):
    folder = os.path.join(ROUND, name)
    steps_file = os.path.join(folder, "steps.jsonl")
    if not os.path.exists(steps_file):
        return ""
    steps = [json.loads(line) for line in open(steps_file) if line.strip()]
    report_file = os.path.join(folder, "report.json")
    report = json.load(open(report_file)) if os.path.exists(report_file) else {}
    out = ['<section><h2>%s</h2>' % esc(name.capitalize())]
    if report:
        happy = report.get("happy")
        out.append('<p class="verdict %s">%s · %s</p>' % (
            "happy" if happy else "unhappy", "Happy" if happy else "Not happy", esc(report.get("why", ""))))
        out.append("<h3>Findings</h3><table>")
        for f in sorted(report.get("findings", []), key=lambda f: ORDER.get(f.get("severity"), 9)):
            out.append('<tr><td class="%s">%s</td><td>%s</td><td>%s%s</td><td>%s</td></tr>' % (
                esc(f.get("severity", "")), esc(f.get("severity", "")), esc(f.get("value", "")),
                esc(f.get("finding", "")),
                ('<br><i>' + esc(f["suggestion"]) + '</i>') if f.get("suggestion") else "",
                " ".join('<a href="#%s-%s">%s</a>' % (name, s, s) for s in f.get("steps", []))))
        out.append("</table><h3>Values</h3><ul>")
        for v in report.get("values", []):
            out.append("<li><b>%s</b>: %s. %s</li>" % (
                esc(v.get("value", "")), esc(v.get("verdict", "")), esc(v.get("evidence", ""))))
        out.append("</ul><h3>Tasks</h3><ul>")
        for t in report.get("tasks", []):
            out.append("<li>%s <b>%s</b>: %s</li>" % (
                "✓" if t.get("done") else "✗", esc(t.get("task", "")), esc(t.get("comment", ""))))
        out.append("</ul>")
        if report.get("previous"):
            out.append("<h3>Previous round's findings</h3><ul>%s</ul>" % "".join(
                "<li><b>%s</b>: %s %s</li>" % (esc(p.get("status", "")), esc(p.get("finding", "")),
                                             esc(str(p.get("evidence", "")))) for p in report["previous"]))
        if report.get("praise"):
            out.append("<h3>Worked well</h3><ul>%s</ul>" % "".join(
                "<li>%s</li>" % esc(p) for p in report["praise"]))
    else:
        out.append('<p class="verdict unhappy">No report written.</p>')
    out.append('<h3>Steps</h3><div class="steps">')
    for s in steps:
        out.append('<figure id="%s-%s"><a href="%s/%s"><img loading="lazy" src="%s/%s"></a>'
                   '<figcaption><b>%s</b> <code>%s</code><br>%s</figcaption></figure>' % (
                       name, s["n"], name, s["screenshot"], name, s["screenshot"], s["n"],
                       esc(s["command"]), esc(s.get("note", ""))))
    out.append("</div></section>")
    return "\n".join(out)


body = "\n".join(persona(p) for p in ("advanced",))
page = """<!doctype html><meta charset="utf-8"><title>Dictate & Delegate QA · %s</title>
<style>
body{font:15px/1.45 system-ui,sans-serif;margin:24px;color:#14201c;max-width:1500px}
h2{margin-top:40px;border-top:2px solid #0b7a5e;padding-top:12px}
.verdict{font-size:18px;padding:10px 14px;border-radius:10px}
.happy{background:#dff3ea}.unhappy{background:#fde3d8}
table{border-collapse:collapse}td{border-bottom:1px solid #e3e7e3;padding:6px 10px;vertical-align:top}
td.blocker,td.major{color:#b3261e;font-weight:600}td.minor{color:#8a5a00}
.steps{display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:16px}
figure{margin:0}img{width:100%%;border:1px solid #dde3df;border-radius:12px}
figcaption{font-size:13px;color:#46524d}code{font-size:12px}
</style><h1>Dictate & Delegate QA · %s</h1>%s""" % (esc(os.path.basename(ROUND)), esc(os.path.basename(ROUND)), body)
open(os.path.join(ROUND, "index.html"), "w").write(page)
print(os.path.join(ROUND, "index.html"))
