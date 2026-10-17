#!/usr/bin/env python3
"""A fake OpenRouter for tests: no provider calls, no money, answers tests queue up front.

Serves the three OpenRouter endpoints Talk Router uses, under /api/v1 like the real one:

  GET  /api/v1/key                    the key check in setup ("bad" keys get 401)
  POST /api/v1/audio/transcriptions   a queued transcript, or "Fake transcript."
  POST /api/v1/chat/completions       the request check (ROUTER_MODEL) gets a queued route, or
                                      transcript; any other model a queued reply, or the dictation

Test control (not part of OpenRouter):

  POST /test/script   {"transcribe": [...], "route": [...], "text": [...]}; each item is
                      {"text": "...", "cost": 0.001} ("cost": null: no usage.cost), or an error:
                      {"error": "provider"} 502, "credits" 402, "busy" 429, "invalid" 400
  POST /test/reset    empties the queues and the request log
  GET  /test/requests the requests received since the last reset, newest last

Usage: python3 scripts/fakes/fake_openrouter.py [--port 8797]
"""
import argparse
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROUTER_MODEL = "deepseek/deepseek-v4.1-flash"
COSTS = {"transcribe": 0.001, "route": 0.00005, "text": 0.0003}
ERRORS = {"provider": (502, "Fake provider error"),
          "credits": (402, "Insufficient credits"),
          "busy": (429, "Rate limit exceeded"),
          "invalid": (400, "Fake invalid request")}

lock = threading.Lock()
queues = {"transcribe": [], "route": [], "text": []}
requests = []


def next_item(kind, default_text):
    with lock:
        if queues[kind]:
            return queues[kind].pop(0)
    return {"text": default_text}


def user_text(body):
    """The last user message as plain text (parts joined), for the echo default."""
    for message in reversed(body.get("messages") or []):
        if message.get("role") != "user":
            continue
        content = message.get("content")
        if isinstance(content, list):
            return "\n".join(p.get("text", "") for p in content if p.get("type") == "text")
        return content or ""
    return ""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            return json.loads(raw or b"{}")
        except json.JSONDecodeError:
            return {}

    def authorized(self):
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer ") or auth[7:].strip() in ("", "bad"):
            self.reply(401, {"error": {"code": 401, "message": "No auth credentials found"}})
            return False
        return True

    def do_GET(self):
        if self.path == "/test/requests":
            with lock:
                return self.reply(200, {"requests": list(requests)})
        if self.path == "/api/v1/key":
            if self.authorized():
                self.reply(200, {"data": {"label": "fake", "usage": 0, "limit": None}})
            return
        self.reply(404, {"error": {"code": 404, "message": "Not found"}})

    def do_POST(self):
        body = self.body()
        if self.path == "/test/reset":
            with lock:
                for q in queues.values():
                    q.clear()
                requests.clear()
            return self.reply(200, {})
        if self.path == "/test/script":
            with lock:
                for kind, items in body.items():
                    if kind in queues:
                        queues[kind].extend(items)
            return self.reply(200, {})
        if not self.authorized():
            return
        if self.path == "/api/v1/audio/transcriptions":
            self.log("transcribe", body)
            return self.answer("transcribe", next_item("transcribe", "Fake transcript."), chat=False)
        if self.path == "/api/v1/chat/completions":
            if body.get("model") == ROUTER_MODEL:
                self.log("route", body)
                default = '{"route": "transcript", "template": ""}'
                return self.answer("route", next_item("route", default), chat=True)
            self.log("text", body)
            return self.answer("text", next_item("text", user_text(body)), chat=True)
        self.reply(404, {"error": {"code": 404, "message": "Not found"}})

    def log(self, kind, body):
        summary = dict(body)
        audio = summary.get("input_audio")
        if isinstance(audio, dict):  # keep the log small: the audio itself isn't interesting
            summary["input_audio"] = {"format": audio.get("format"), "bytes": len(audio.get("data") or "")}
        with lock:
            requests.append({"kind": kind, "body": summary})

    def answer(self, kind, item, chat):
        if "error" in item:
            status, message = ERRORS.get(item["error"], ERRORS["provider"])
            return self.reply(status, {"error": {"code": status, "message": message}})
        cost = item.get("cost", COSTS[kind])
        usage = {} if cost is None else {"cost": cost}
        if chat:
            self.reply(200, {
                "id": "fake",
                "model": "fake",
                "choices": [{"message": {"role": "assistant", "content": item.get("text", "")}}],
                "usage": usage,
            })
        else:
            self.reply(200, {"text": item.get("text", ""), "usage": usage})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8797)
    args = parser.parse_args()
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
