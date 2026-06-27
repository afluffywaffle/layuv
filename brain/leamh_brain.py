#!/usr/bin/env python3
"""
Léamh reference library — Phase 1 reference proxy.

A small OpenAI-compatible /chat/completions proxy that gives Layuv *continuity*:
it injects your project's whole reference library into every request, then
forwards to a real model (Gemini first; Claude later) and streams the reply
straight back.

Why it exists (the architecture):
  - Layuv (the Supernote / any device) = the *director*. It only knows this
    Mac's address. It never holds the API key or the reference library.
  - This reference-library server (on your Mac) = the *library + the key*. It is
    the only thing that talks to the upstream model.

Privacy/security notes — read these, they are the whole point:
  - The upstream API key lives ONLY on this Mac (brain.env / env var). It is
    never sent to the device and never logged.
  - NO RAG / no vector store: every reference file is injected WHOLE into each
    request. A personal-project "bible" fits comfortably in a modern context
    window, and whole-context injection can't silently omit a continuity fact
    the way retrieval can.
  - Honesty caveat: when the upstream is a CLOUD model (Gemini/Claude), your
    references + chapter ARE sent to that provider — that is inherent to using
    a cloud model and this server does not change it. What it protects is
    your *key* (stays here) and your *architecture*: pointing the upstream at a
    local model later (so nothing leaves your network) is a one-line edit here,
    with zero device rebuild.
  - Dependency-free on purpose: Python standard library only. The entire
    trusted surface is this one file — nothing in a package supply chain to
    audit. urllib for the upstream call, http.server for the listener.

Zero Android changes. Layuv just points its endpoint at this Mac:
    Endpoint (base URL):  http://<this-mac-LAN-ip>:<port>/v1
    Model:                anything (the Mac decides the real model)
    Key:                  blank on a trusted LAN, or your access token if set
"""

import json
import os
import socket
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent

# Files we treat as reference text. Anything else in the folder is ignored.
REFERENCE_EXTS = {".md", ".markdown", ".txt", ".text", ".rst", ".org"}

# Prepended ahead of the reference files in the injected system message.
SYSTEM_PREAMBLE = (
    "You are the writer on an ongoing writing project. The reference files below "
    "are the project's living 'bible' — characters, world, continuity, voice, and "
    "standing instructions. Treat them as authoritative for every revision: keep "
    "names, facts, and style consistent with them, and never contradict them. "
    "The user is the editor/director; their chapter text and annotations are the "
    "editorial instructions for this turn."
)


# --------------------------------------------------------------------------- #
# Config — a brain.env file next to this script (KEY=VALUE lines), with real
# environment variables taking precedence. brain.env is gitignored.
# --------------------------------------------------------------------------- #
CONFIG_KEYS = (
    "BRAIN_UPSTREAM_URL",      # full /chat/completions URL of the real model
    "BRAIN_UPSTREAM_KEY",      # the held API key (Gemini/Claude/...) — stays on this Mac
    "BRAIN_UPSTREAM_MODEL",    # the model THIS MAC decides to use (device's choice ignored)
    "BRAIN_REFERENCES_DIR",    # folder of reference files, read whole into every request
    "BRAIN_PORT",
    "BRAIN_BIND",
    "BRAIN_TOKEN",             # optional shared secret the device must send as its key
)

DEFAULTS = {
    "BRAIN_UPSTREAM_URL": "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
    "BRAIN_UPSTREAM_MODEL": "gemini-2.5-flash",
    "BRAIN_REFERENCES_DIR": str(Path.home() / "layuv-brain" / "references"),
    "BRAIN_PORT": "8077",
    "BRAIN_BIND": "0.0.0.0",
}


def load_config():
    cfg = dict(DEFAULTS)
    env_file = HERE / "brain.env"
    if env_file.exists():
        for raw in env_file.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            cfg[key.strip()] = value.strip().strip('"').strip("'")
    # Real environment variables override the file.
    for key in CONFIG_KEYS:
        if os.environ.get(key):
            cfg[key] = os.environ[key]
    return cfg


CFG = load_config()


# --------------------------------------------------------------------------- #
# Reference library — read fresh on EVERY request so editing the library never
# needs a restart (the "evolving library" goal). Cheap at personal scale.
# --------------------------------------------------------------------------- #
def read_references():
    base = Path(CFG["BRAIN_REFERENCES_DIR"]).expanduser()
    if not base.is_dir():
        return [], 0
    files = []
    for path in sorted(base.rglob("*")):
        if not path.is_file() or path.name.startswith("."):
            continue
        if path.suffix.lower() not in REFERENCE_EXTS:
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        if text.strip():
            files.append((str(path.relative_to(base)), text))
    total_chars = sum(len(text) for _, text in files)
    return files, total_chars


def build_system_message(files):
    if not files:
        return None
    parts = [SYSTEM_PREAMBLE]
    for rel, text in files:
        parts.append(f"\n===== REFERENCE FILE: {rel} =====\n{text.strip()}\n")
    return {"role": "system", "content": "\n".join(parts)}


# --------------------------------------------------------------------------- #
# HTTP handler
# --------------------------------------------------------------------------- #
class BrainHandler(BaseHTTPRequestHandler):
    # HTTP/1.0 → the streamed body is delimited by connection close, which is
    # exactly how Layuv reads the SSE (read lines until EOF). No Content-Length,
    # no chunked-encoding bookkeeping.
    protocol_version = "HTTP/1.0"

    # Silence the default per-request access log; we print our own privacy-safe line.
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path.rstrip("/") in ("/health", "/v1/health", ""):
            self._send_json(200, {"status": "ok", "service": "leamh-brain"})
        else:
            self._send_json(404, {"error": {"message": "not found"}})

    def do_POST(self):
        if self.path.rstrip("/") not in ("/v1/chat/completions", "/chat/completions"):
            self._send_json(404, {"error": {"message": "unknown endpoint"}})
            return

        # Optional device auth: if an access token is configured, the device must
        # send it as its API key (Authorization: Bearer <token>).
        token = CFG.get("BRAIN_TOKEN", "").strip()
        if token and self.headers.get("Authorization", "") != f"Bearer {token}":
            self._send_json(401, {"error": {"message":
                "Unauthorized — set the access token as the API key in Layuv's AI settings."}})
            return

        try:
            length = int(self.headers.get("Content-Length") or 0)
            req = json.loads(self.rfile.read(length))
        except Exception:
            self._send_json(400, {"error": {"message": "Invalid JSON request body."}})
            return

        files, total_chars = read_references()
        system_message = build_system_message(files)
        device_messages = req.get("messages") or []

        upstream_model = CFG.get("BRAIN_UPSTREAM_MODEL") or req.get("model") or "gemini-2.5-flash"
        body = dict(req)  # preserve max_tokens, temperature, etc.
        body["model"] = upstream_model
        body["messages"] = ([system_message] if system_message else []) + device_messages
        body["stream"] = True

        # Privacy-safe log: counts only, never the manuscript or the key.
        print(f"[brain] request: {len(device_messages)} device msg(s); "
              f"injected {len(files)} reference file(s) ({total_chars:,} chars) "
              f"-> {upstream_model}", flush=True)

        if not files:
            print(f"[brain] WARNING: no reference files found in "
                  f"{CFG['BRAIN_REFERENCES_DIR']} — forwarding without injected context.",
                  flush=True)

        self._forward_stream(body)

    def _forward_stream(self, body):
        url = CFG["BRAIN_UPSTREAM_URL"]
        upstream_key = CFG.get("BRAIN_UPSTREAM_KEY", "").strip()
        ureq = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), method="POST")
        ureq.add_header("content-type", "application/json")
        ureq.add_header("accept", "text/event-stream")
        if upstream_key:
            ureq.add_header("Authorization", f"Bearer {upstream_key}")

        try:
            resp = urllib.request.urlopen(ureq, timeout=300)
        except urllib.error.HTTPError as e:
            # Forward the upstream status + error body verbatim so Layuv can read
            # error.message. (The key is never in the response body.)
            err_body = b""
            try:
                err_body = e.read()
            except Exception:
                pass
            print(f"[brain] upstream HTTP {e.code}", flush=True)
            self._send_raw(e.code, "application/json",
                           err_body or json.dumps(
                               {"error": {"message": f"Upstream returned HTTP {e.code}."}}).encode("utf-8"))
            return
        except Exception as e:
            print(f"[brain] upstream unreachable: {type(e).__name__}", flush=True)
            self._send_json(502, {"error": {"message":
                f"The reference-library server couldn't reach the upstream model ({type(e).__name__}). "
                "Check BRAIN_UPSTREAM_URL / the network."}})
            return

        # Stream the upstream SSE straight back to the device, byte for byte.
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        try:
            while True:
                chunk = resp.read(2048)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
            print("[brain] response streamed ok", flush=True)
        except Exception as e:
            # Device hung up mid-stream, or a write failed — not fatal.
            print(f"[brain] stream ended early: {type(e).__name__}", flush=True)
        finally:
            try:
                resp.close()
            except Exception:
                pass

    def _send_json(self, code, obj):
        self._send_raw(code, "application/json", json.dumps(obj).encode("utf-8"))

    def _send_raw(self, code, content_type, body):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except Exception:
            pass


# --------------------------------------------------------------------------- #
# Startup
# --------------------------------------------------------------------------- #
def lan_ip():
    """Best-effort LAN IP of this Mac (no packet is actually sent)."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def main():
    port = int(CFG["BRAIN_PORT"])
    bind = CFG["BRAIN_BIND"]
    ip = lan_ip()
    files, total_chars = read_references()
    has_key = bool(CFG.get("BRAIN_UPSTREAM_KEY", "").strip())
    has_token = bool(CFG.get("BRAIN_TOKEN", "").strip())

    print("=" * 68)
    print("Léamh reference library — Phase 1 reference proxy")
    print("=" * 68)
    print(f"  Upstream model : {CFG['BRAIN_UPSTREAM_MODEL']}")
    print(f"  Upstream URL   : {CFG['BRAIN_UPSTREAM_URL']}")
    print(f"  Upstream key   : {'held on this Mac (never sent to device)' if has_key else 'NONE — set BRAIN_UPSTREAM_KEY for a cloud model'}")
    print(f"  References     : {CFG['BRAIN_REFERENCES_DIR']}")
    print(f"                   {len(files)} file(s), {total_chars:,} chars"
          + ("" if files else "  <-- empty! add files or set BRAIN_REFERENCES_DIR"))
    print(f"  Device auth    : {'access token required' if has_token else 'none (trusted LAN)'}")
    print("-" * 68)
    print("  Point Layuv's AI settings here:")
    print(f"    Endpoint (base URL):  http://{ip}:{port}/v1")
    print(f"    Model             :  anything     (the Mac decides the real model)")
    print(f"    Key               :  {'<your access token>' if has_token else '(leave blank)'}")
    print(f"  Health check:  curl http://{ip}:{port}/health")
    print("=" * 68, flush=True)

    if not has_key and "googleapis.com" in CFG["BRAIN_UPSTREAM_URL"]:
        print("[brain] WARNING: Gemini upstream needs a key. Put it in brain/brain.env "
              "as BRAIN_UPSTREAM_KEY=...", flush=True)

    server = ThreadingHTTPServer((bind, port), BrainHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[brain] shutting down.", flush=True)
        server.shutdown()


if __name__ == "__main__":
    sys.exit(main())
