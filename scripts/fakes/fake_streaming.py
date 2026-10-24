#!/usr/bin/env python3
"""A fake streaming speech-to-text server for tests: AssemblyAI's v3 and ElevenLabs' realtime
WebSockets, answering with texts tests queue up front. No provider calls, no money.

  WS   /v3/ws                          AssemblyAI: binary PCM in; "Turn" messages out (live words,
                                       end_of_turn when it's done: after 1 s of silence, or at once on
                                       {"type": "ForceEndpoint"})
  WS   /v1/speech-to-text/realtime     ElevenLabs: input_audio_chunk JSON in; partial_transcript out,
                                       committed_transcript on "commit": true

Each stretch of speech it hears (16 kHz PCM louder than 500 RMS) takes the next queued text and
reveals it a word per 100 ms of speech, as the real ones show what they have so far. A wrong key
("bad") is refused, as the providers do (HTTP 401).

Test control (plain HTTP, same port):

  POST /test/script   {"texts": ["Book the table. Over.", ...]} queued, in order;
                      {"drop": true} instead of a text: the connection is closed (1011) when that
                      stretch starts, as a provider failing mid-call
  POST /test/reset    empties the queue and the log
  GET  /test/log      what happened: connections (path, key), audio seconds, forces, closes

Usage: python3 scripts/fakes/fake_streaming.py [--port 8800]
"""
import argparse
import base64
import hashlib
import json
import math
import socketserver
import struct
import threading
import time

lock = threading.Lock()
queue = []
events = []


def log(**event):
    with lock:
        events.append(dict(event, at=round(time.time(), 3)))


def rms(pcm):
    n = len(pcm) // 2
    if n == 0:
        return 0
    samples = struct.unpack("<%dh" % n, pcm[:n * 2])
    return math.sqrt(sum(s * s for s in samples) / n)


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        line = self.rfile.readline().decode("latin-1").strip()
        if not line:
            return
        method, path, _ = line.split(" ", 2)
        headers = {}
        while True:
            h = self.rfile.readline().decode("latin-1").strip()
            if not h:
                break
            k, _, v = h.partition(":")
            headers[k.strip().lower()] = v.strip()
        if headers.get("upgrade", "").lower() == "websocket":
            return self.websocket(path, headers)
        length = int(headers.get("content-length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}") if length else {}
        if path == "/test/script":
            with lock:
                queue.extend(body.get("texts", []))
            return self.http(200, {})
        if path == "/test/reset":
            with lock:
                queue.clear()
                events.clear()
            return self.http(200, {})
        if path == "/test/log":
            with lock:
                return self.http(200, {"log": list(events)})
        self.http(404, {"error": "not found"})

    def http(self, status, body):
        data = json.dumps(body).encode()
        self.wfile.write(("HTTP/1.1 %d X\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n" % (status, len(data))).encode() + data)

    # ------------------------------------------------------------------ WebSocket

    def websocket(self, path, headers):
        base = path.split("?")[0]
        key = headers.get("authorization") if base == "/v3/ws" else headers.get("xi-api-key")
        if not key or key == "bad":
            body = b'{"error": "Invalid API key"}'
            self.wfile.write(b"HTTP/1.1 401 Unauthorized\r\nContent-Length: %d\r\n\r\n" % len(body) + body)
            log(kind="refused", path=base)
            return
        accept = base64.b64encode(hashlib.sha1((headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        self.wfile.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n\r\n" % accept).encode())
        self.wfile.flush()
        self.send_lock = threading.Lock()
        self.aai = base == "/v3/ws"
        log(kind="open", path=base, query=path.partition("?")[2])
        self.text, self.words, self.shown, self.turn, self.quiet, self.speaking, self.audio = None, [], 0, 0, 0, False, 0
        if not self.aai:
            self.send({"message_type": "session_started", "session_id": "fake"})
        try:
            while True:
                op, payload = self.frame()
                if op == 8:
                    self.raw(8, payload[:2])
                    break
                if op == 9:
                    self.raw(10, payload)
                elif op == 2 and self.aai:
                    self.audio_in(payload)
                elif op == 1:
                    m = json.loads(payload)
                    if self.aai and m.get("type") == "ForceEndpoint":
                        log(kind="force")
                        self.final()
                    elif self.aai and m.get("type") == "Terminate":
                        break
                    elif not self.aai and m.get("message_type") == "input_audio_chunk":
                        pcm = base64.b64decode(m.get("audio_base_64") or "")
                        if pcm:
                            self.audio_in(pcm)
                        if m.get("commit"):
                            log(kind="force")
                            self.final()
        except (ConnectionError, OSError, ValueError):
            pass
        log(kind="closed", path=base, audio_seconds=round(self.audio / 32000, 1))

    def frame(self):
        head = self.rfile.read(2)
        if len(head) < 2:
            raise ConnectionError("closed")
        op, n = head[0] & 0x0F, head[1] & 0x7F
        if n == 126:
            n = struct.unpack(">H", self.rfile.read(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", self.rfile.read(8))[0]
        mask = self.rfile.read(4) if head[1] & 0x80 else b"\0\0\0\0"
        data = bytearray(self.rfile.read(n))
        for i in range(n):
            data[i] ^= mask[i & 3]
        return op, bytes(data)

    def raw(self, op, payload):
        n = len(payload)
        head = bytes([0x80 | op]) + (bytes([n]) if n < 126 else bytes([126]) + struct.pack(">H", n) if n < 65536 else bytes([127]) + struct.pack(">Q", n))
        with self.send_lock:
            self.wfile.write(head + payload)
            self.wfile.flush()

    def send(self, message):
        self.raw(1, json.dumps(message).encode())

    def audio_in(self, pcm):
        """100 ms or so of audio: speech reveals the next word; a second of quiet ends the turn."""
        self.audio += len(pcm)
        loud = rms(pcm) > 500
        if loud:
            self.quiet = 0
            if self.text is None:
                with lock:
                    item = queue.pop(0) if queue else None
                if isinstance(item, dict) and item.get("drop"):
                    log(kind="dropped")
                    self.raw(8, struct.pack(">H", 1011) + b"Internal error")
                    raise ConnectionError("dropped")
                if item is None:
                    return
                self.text, self.words, self.shown = item, item.split(), 0
            if self.shown < len(self.words):
                self.shown += 1
                self.live()
        elif self.text is not None:
            self.quiet += len(pcm)
            if self.aai and self.quiet >= 32000:  # A second of quiet: AssemblyAI ends the turn itself.
                self.final()

    def live(self):
        words = self.words[:self.shown]
        if self.aai:
            self.send({"type": "Turn", "turn_order": self.turn, "end_of_turn": False, "transcript": "",
                       "words": [{"text": w.strip(".,?!"), "word_is_final": False} for w in words]})
        else:
            self.send({"message_type": "partial_transcript", "text": " ".join(words)})

    def final(self):
        if self.text is None:
            return  # Nothing live: nothing to end (as AssemblyAI after a turn ended by itself).
        text = self.text
        self.text, self.words, self.shown = None, [], 0
        if self.aai:
            self.send({"type": "Turn", "turn_order": self.turn, "end_of_turn": True, "turn_is_formatted": True, "transcript": text, "words": []})
            self.turn += 1
        else:
            self.send({"message_type": "committed_transcript", "text": text})
        log(kind="final", text=text)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8800)
    args = parser.parse_args()
    Server((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
