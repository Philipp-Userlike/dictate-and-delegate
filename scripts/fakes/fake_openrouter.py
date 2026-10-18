#!/usr/bin/env python3
"""A fake OpenRouter for tests: no provider calls, no money, answers tests queue up front.

Serves the OpenRouter endpoints Dictate & Delegate uses, under /api/v1 and /api/alpha like the real one:

  GET  /api/v1/key                    the key check in setup ("bad" keys get 401)
  GET  /api/v1/models                 a small catalog (CATALOG) for the Refine model picker
  POST /api/v1/audio/transcriptions   a queued transcript, or "Fake transcript."
  POST /api/v1/chat/completions       Refine: a queued reply, or the dictation echoed
  POST /api/v1/audio/speech           hands-free replies: a short MP3 (scripts/fakes/reply.mp3); a voice is required
  GET  /api/v1/generation?id=…        its cost: $0.0001
  POST /api/alpha/decisions           Jev: queued answers, or refine 0, chief 0, template none;
                                      only the questions asked are answered

And Anthropic's two endpoints, for Refine with an Anthropic key (-PanthropicUrl=<this server>):
  GET  /v1/models                     the key check ("sk-ant-bad" gets 401)
  POST /v1/messages                   a queued "anthropic" item, or the dictation echoed;
                                      {"error": "key"} 401, "credits" 400 credit balance,
                                      "busy" 429, "overloaded" 529, "provider" 500
Test control (not part of OpenRouter):

  POST /test/script   {"transcribe": [...], "text": [...], "decide": [...]}; each item is
                      {"text": "...", "cost": 0.001} ("cost": null: no usage.cost), or an error:
                      {"error": "provider"} 502, "credits" 402, "busy" 429, "invalid" 400.
                      "decide" items: {"refine": 0.9, "chief": 0.1, "template": "none",
                      "cost": 0.00002, "delay": 5} (delay in seconds, to test Jev's timeout)
  POST /test/reset    empties the queues and the request log
  GET  /test/requests the requests received since the last reset, newest last

Usage: python3 scripts/fakes/fake_openrouter.py [--port 8797]
"""
import argparse
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

def catalog_entry(id, name, prompt, completion, output=("text",)):
    return {"id": id, "name": name, "pricing": {"prompt": prompt, "completion": completion},
            "architecture": {"input_modalities": ["text", "image"], "output_modalities": list(output)}}


CATALOG = {"data": [
    catalog_entry("openai/gpt-fake", "Fake GPT", "0.000001", "0.000004"),
    catalog_entry("anthropic/claude-opus-5.5", "Anthropic: Claude Opus 5.5", "0.000004", "0.00002"),
    catalog_entry("anthropic/claude-sonnet-5.5", "Anthropic: Claude Sonnet 5.5", "0.000002", "0.00001"),
    catalog_entry("fake/image-maker", "Fake Image Maker", "0.000001", "0.000001", ("image",)),
]}

COSTS = {"transcribe": 0.001, "text": 0.0003, "decide": 0.00002}
ERRORS = {"provider": (502, "Fake provider error"),
          "credits": (402, "Insufficient credits"),
          "busy": (429, "Rate limit exceeded"),
          "invalid": (400, "Fake invalid request")}

lock = threading.Lock()
queues = {"transcribe": [], "text": [], "decide": [], "anthropic": []}
ANTHROPIC_ERRORS = {"key": (401, "authentication_error", "invalid x-api-key"),
                    "credits": (400, "invalid_request_error",
                                "Your credit balance is too low to access the Anthropic API."),
                    "busy": (429, "rate_limit_error", "Rate limited"),
                    "overloaded": (529, "overloaded_error", "Overloaded"),
                    "provider": (500, "api_error", "Internal server error")}
requests = []


def next_item(kind, default_text):
    with lock:
        if queues[kind]:
            return queues[kind].pop(0)
    return default_text if isinstance(default_text, dict) else {"text": default_text}


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
        if self.path == "/v1/models":
            if self.headers.get("x-api-key", "") in ("", "sk-ant-bad") or not self.headers.get("anthropic-version"):
                return self.anthropic_error("key")
            return self.reply(200, {"data": [{"type": "model", "id": "claude-sonnet-5-5"}], "has_more": False})
        if self.path == "/api/v1/models":
            return self.reply(200, CATALOG)
        if self.path.startswith("/api/v1/generation?"):
            # What a voice reply cost, looked up after it: a fixed $0.0001.
            return self.reply(200, {"data": {"id": "gen-tts-fake", "total_cost": 0.0001}})
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
        if self.path == "/v1/messages":
            self.log("anthropic", body)
            item = next_item("anthropic", user_text(body))
            if "error" in item:
                return self.anthropic_error(item["error"])
            if self.headers.get("x-api-key", "") in ("", "sk-ant-bad"):
                return self.anthropic_error("key")
            return self.reply(200, {
                "id": "msg_fake", "type": "message", "role": "assistant", "model": body.get("model"),
                "content": [{"type": "thinking", "thinking": ""}, {"type": "text", "text": item.get("text", "")}],
                "stop_reason": "end_turn",
                "usage": {"input_tokens": 1000, "output_tokens": 100}})
        if not self.authorized():
            return
        if self.path == "/api/v1/audio/transcriptions":
            self.log("transcribe", body)
            return self.answer("transcribe", next_item("transcribe", "Fake transcript."), chat=False)
        if self.path == "/api/alpha/decisions":
            self.log("decide", body)
            return self.decide(body, next_item("decide", {}))
        if self.path == "/api/v1/audio/speech":
            # Hands-free replies read aloud: a short real MP3 (Kokoro saying a sentence).
            self.log("speech", body)
            if not body.get("voice"):
                return self.reply(400, {"error": {"code": 400, "message": "An explicit voice is required for this TTS provider."}})
            data = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "reply.mp3"), "rb").read()
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("X-Generation-Id", "gen-tts-fake")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if self.path == "/api/v1/chat/completions":
            self.log("text", body)
            return self.answer("text", next_item("text", user_text(body)), chat=True)
        self.reply(404, {"error": {"code": 404, "message": "Not found"}})

    def anthropic_error(self, name):
        status, kind, message = ANTHROPIC_ERRORS[name]
        self.reply(status, {"type": "error", "error": {"type": kind, "message": message}})

    def log(self, kind, body):
        summary = dict(body)
        audio = summary.get("input_audio")
        if isinstance(audio, dict):  # keep the log small: the audio itself isn't interesting
            summary["input_audio"] = {"format": audio.get("format"), "bytes": len(audio.get("data") or "")}
        with lock:
            requests.append({"kind": kind, "body": summary})

    def decide(self, body, item):
        """Jev's answer shape: a noul per yes/no question, a choice with probabilities."""
        if "error" in item:
            status, message = ERRORS.get(item["error"], ERRORS["provider"])
            return self.reply(status, {"error": {"code": status, "message": message}})
        if item.get("delay"):
            time.sleep(float(item["delay"]))
        answers = {}
        for name, q in (body.get("questions") or {}).items():
            if q.get("type") == "noul":
                answers[name] = {"type": "noul", "noul": float(item.get(name, 0.0))}
            elif q.get("type") == "choice":
                options = list((q.get("criteria") or {}).keys()) or ["none"]
                choice = item.get(name, options[0])
                if choice not in options:
                    choice = options[0]
                answers[name] = {"type": "choice", "choice": choice, "confidence": 0.95,
                                 "probabilities": {o: (0.95 if o == choice else 0.05 / max(1, len(options) - 1))
                                                   for o in options}}
        cost = item.get("cost", COSTS["decide"])
        usage = {"input_tokens": 400, "output_tokens": 40}
        if cost is not None:
            usage["cost"] = cost
        self.reply(200, {"model": "typesafe/jev-fake", "answers": answers, "usage": usage,
                         "id": "gen-dec-fake", "provider": "TypeSafe"})

    def answer(self, kind, item, chat):
        if "error" in item:
            status, message = ERRORS.get(item["error"], ERRORS["provider"])
            return self.reply(status, {"error": {"code": status, "message": message}})
        if item.get("delay"):
            time.sleep(float(item["delay"]))
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
