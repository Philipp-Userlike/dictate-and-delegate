#!/usr/bin/env python3
"""A fake of bb's MCP server (plugin bb--mcp) for tests: no bb, no agents, canned answers.

Speaks MCP Streamable HTTP on POST /mcp and answers as an event stream, like bb does:

  initialize                  protocol 2025-06-18, server "bb"
  notifications/initialized   202
  tools/list                  search, read, run
  tools/call run|read         recognises the snippet by the bb command it calls:
                                "thread","spawn"  -> {"id": "thr_fakechief", "title": ..., "status": "starting"}
                                "thread","tell"   -> {"threadId": ..., "delivery": "sent"}
                                "thread","show"   -> {"id": ..., "title": "Chief of staff", "status": "idle", "archived": false}
                                "skill","list"    -> {"skills": {name: marker hash}} of the installed fake skills
                                spawn titled "Talk Router: install skills" -> installs the files in its
                                                     prompt at once (unless /test/skills says "stall"),
                                                     {"id": "thr_fakeinstall"}
                                "thread","archive" -> {"archived": true}
                              anything else returns {}

And bb's REST upload, as bb's own app uses it:

  POST /api/v1/projects/<id>/attachments   multipart "file" -> {"type", "path", "name", "mimeType", "sizeBytes"}
                                           (401 when /test/config {"uploadStatus": 401} says so)

The MCP snippet "project","attachment","upload" (the base64 fallback) stores its stdin text too.

Test control (not part of bb):

  POST /test/fail     {"kind": "spawn"|"tell"|"show"|"any", "error": "tool"|"http401"|"http503"|"rpc"}
                      queues one failure for the next matching call: "tool" is an isError result
                      ("bb thread tell failed: …"), "httpNNN" answers that HTTP status, "rpc" a
                      JSON-RPC error
  POST /test/config   {"token": "secret"} requires "Authorization: Bearer secret" (401 otherwise);
                      {"token": ""} turns that off
  POST /test/skills   {"installed": {"talk-router": "abc", ...}} sets what bb has ("" missing,
                      "old" unmarked); {"stall": true} makes the install thread never install
  GET  /test/uploads  every upload: {"name", "mimeType", "size", "sha256", "via": "rest"|"mcp", "path"}
  GET  /test/skills   what's installed: {"installed": {...}, "files": {name: content}}
  POST /test/reset    empties failures, config, installed skills and the request log
  GET  /test/requests every MCP request since the last reset, oldest first:
                      {"method", "tool", "code", "auth"}

Usage: python3 scripts/fakes/fake_bb_mcp.py [--port 8798]
"""
import argparse
import base64
import hashlib
import json
import re
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CHIEF_ID = "thr_fakechief"
TOOLS = [
    {"name": "search", "description": "Find bb commands for a task.",
     "inputSchema": {"type": "object", "properties": {"query": {"type": "string"}}}},
    {"name": "read", "description": "Run a snippet that may only inspect bb.",
     "inputSchema": {"type": "object", "properties": {"code": {"type": "string"}}, "required": ["code"]}},
    {"name": "run", "description": "Run a snippet that may call any bb command.",
     "inputSchema": {"type": "object", "properties": {"code": {"type": "string"}}, "required": ["code"]}},
]

lock = threading.Lock()
failures = []
config = {"token": "", "uploadStatus": 200}
uploads = []
skills = {"installed": {}, "files": {}, "stall": False}
requests = []


def kind_of(code):
    flat = code.replace(" ", "")
    if '"skill","list"' in flat:
        return "skills"
    if '"thread","archive"' in flat:
        return "archive"
    if '"project","attachment","upload"' in flat:
        return "upload"
    for kind in ("spawn", "tell", "show"):
        if '"thread","%s"' % kind in code.replace(" ", ""):
            return kind
    return "other"


def take_failure(kind):
    with lock:
        for i, f in enumerate(failures):
            if f.get("kind") in (kind, "any"):
                return failures.pop(i)
    return None


def thread_arg(code, command):
    m = re.search(r'"thread"\s*,\s*"%s"\s*,\s*"(thr_[A-Za-z0-9]+)"' % command, code)
    return m.group(1) if m else CHIEF_ID


class Handler(BaseHTTPRequestHandler):
    # Keep-alive like bb itself, so clients reuse connections the way they do with the real one.
    protocol_version = "HTTP/1.1"
    def log_message(self, fmt, *args):
        sys.stderr.write("%s\n" % (fmt % args))
        sys.stderr.flush()

    def send_json(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def send_event(self, message):
        data = ("event: message\ndata: %s\n\n" % json.dumps(message)).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
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

    def do_GET(self):
        if self.path == "/test/skills":
            with lock:
                return self.send_json(200, {"installed": skills["installed"], "files": skills["files"]})
        if self.path == "/test/uploads":
            with lock:
                return self.send_json(200, {"uploads": list(uploads)})
        if self.path == "/test/requests":
            with lock:
                return self.send_json(200, {"requests": list(requests)})
        self.send_json(404, {"error": "not found"})

    def do_POST(self):
        m = re.match(r"^/api/v1/projects/([^/]+)/attachments$", self.path)
        if m:
            return self.rest_upload()
        body = self.body()
        if self.path == "/test/reset":
            with lock:
                failures.clear()
                requests.clear()
                config["token"] = ""
                config["uploadStatus"] = 200
                uploads.clear()
                skills.update({"installed": {}, "files": {}, "stall": False})
            return self.send_json(200, {})
        if self.path == "/test/fail":
            with lock:
                failures.append(body)
            return self.send_json(200, {})
        if self.path == "/test/skills":
            with lock:
                if "installed" in body:
                    skills["installed"] = dict(body["installed"])
                if "stall" in body:
                    skills["stall"] = bool(body["stall"])
            return self.send_json(200, {})
        if self.path == "/test/config":
            with lock:
                config.update(body)
            return self.send_json(200, {})
        if self.path != "/mcp":
            return self.send_json(404, {"error": "not found"})
        auth = self.headers.get("Authorization", "")
        method = body.get("method", "")
        params = body.get("params") or {}
        tool = params.get("name", "") if method == "tools/call" else ""
        code = (params.get("arguments") or {}).get("code", "") if tool else ""
        with lock:
            requests.append({"method": method, "tool": tool, "code": code, "auth": auth})
            token = config["token"]
        if token and auth != "Bearer " + token:
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Bearer scope="bb"')
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if method == "notifications/initialized":
            self.send_response(202)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        rid = body.get("id")
        if method == "initialize":
            return self.send_event({"jsonrpc": "2.0", "id": rid, "result": {
                "protocolVersion": "2025-06-18",
                "capabilities": {"tools": {"listChanged": True}},
                "serverInfo": {"name": "bb", "version": "fake"}}})
        if method == "tools/list":
            return self.send_event({"jsonrpc": "2.0", "id": rid, "result": {"tools": TOOLS}})
        if method == "tools/call" and tool in ("run", "read"):
            if len(code) > 100000:  # bb's inputSchema: maxLength 100000
                return self.send_event({"jsonrpc": "2.0", "id": rid, "error": {
                    "code": -32602, "message": "Input validation error: code is too big (maximum 100000)"}})
            return self.call(rid, code)
        self.send_event({"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "Method not found"}})

    def rest_upload(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length)
        with lock:
            status = config["uploadStatus"]
            token = config["token"]
        if token and self.headers.get("Authorization", "") != "Bearer " + token:
            status = 401
        if status != 200:
            self.send_response(status)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        boundary = re.search(r"boundary=(\S+)", self.headers.get("Content-Type", "")).group(1).encode()
        part = raw.split(b"--" + boundary)[1]
        head, data = part.split(b"\r\n\r\n", 1)
        data = data[:-2]  # the CRLF before the closing boundary
        name = re.search(rb'filename="([^"]*)"', head).group(1).decode()
        mime = re.search(rb"Content-Type: (\S+)", head).group(1).decode()
        path = "%s-fake-%d" % (name, len(uploads))
        with lock:
            uploads.append({"name": name, "mimeType": mime, "size": len(data),
                            "sha256": hashlib.sha256(data).hexdigest(), "via": "rest", "path": path})
        self.send_json(200, {"type": "localImage" if mime.startswith("image/") else "localFile",
                             "sourceProjectId": "proj_personal", "path": path, "name": name,
                             "mimeType": mime, "sizeBytes": len(data)})

    def call(self, rid, code):
        kind = kind_of(code)
        failure = take_failure(kind)
        if failure:
            error = failure.get("error", "tool")
            if error.startswith("http"):
                self.send_response(int(error[4:]))
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if error == "rpc":
                return self.send_event({"jsonrpc": "2.0", "id": rid,
                                        "error": {"code": -32000, "message": "Fake bb error"}})
            return self.send_event({"jsonrpc": "2.0", "id": rid, "result": {
                "content": [{"type": "text", "text": "Error: bb thread %s failed: thread not found" % kind}],
                "isError": True}})
        if kind == "upload":
            text = json.loads(re.search(r'\{stdin: ("(?:[^"\\]|\\.)*")\}', code).group(1))
            name = re.search(r'"--filename","([^"]*)"', code.replace(" ", "")).group(1)
            path = "%s-fake-%d" % (name, len(uploads))
            with lock:
                uploads.append({"name": name, "mimeType": "text/plain", "size": len(text),
                                "text": text, "via": "mcp", "path": path, "codeLength": len(code)})
            value = {"path": path}
        elif kind == "skills":
            with lock:
                value = {"skills": {n: skills["installed"].get(n, "") for n in ("chief-of-staff", "talk-router")}}
        elif kind == "archive":
            value = {"archived": True}
        elif kind == "spawn" and "Talk Router: install skills" in code:
            prompt = json.loads(re.search(r'\{stdin: ("(?:[^"\\]|\\.)*")\}', code).group(1))
            with lock:
                if not skills["stall"]:
                    for m in re.finditer(r"===== ~/\.bb/skills/([a-z-]+)/SKILL\.md \(begins on the next line\) =====\n(.*?)===== end of \1 =====",
                                         prompt, re.S):
                        skills["files"][m.group(1)] = m.group(2)
                        marker = re.search(r"<!-- [a-z-]+-skill: ([0-9a-f]+) -->", m.group(2))
                        skills["installed"][m.group(1)] = marker.group(1) if marker else "old"
            value = {"id": "thr_fakeinstall", "status": "starting"}
        elif kind == "spawn":
            value = {"id": CHIEF_ID, "title": "Chief of staff", "status": "starting"}
        elif kind == "tell":
            value = {"threadId": thread_arg(code, "tell"), "delivery": "sent"}
        elif kind == "show":
            value = {"id": thread_arg(code, "show"), "title": "Chief of staff", "status": "idle", "archived": False}
        else:
            value = {}
        self.send_event({"jsonrpc": "2.0", "id": rid, "result": {
            "content": [{"type": "text", "text": json.dumps(value, indent=2)}], "isError": False}})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8798)
    args = parser.parse_args()
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
