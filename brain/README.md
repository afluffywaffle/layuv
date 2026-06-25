# Léamh brain — Phase 1 reference proxy

A ~250-line, **dependency-free** Python server that gives Layuv *continuity*: it
injects your project's whole reference library into every request, forwards to a
real model (Gemini first), and streams the reply back. Layuv stays a pure
OpenAI-compatible client — you just point its endpoint at this Mac. **No app
rebuild.**

```
device (Layuv / Supernote)  ──►  this Mac (brain)  ──►  upstream model (Gemini)
   knows only the Mac's URL       holds the KEY +            does the writing
   never holds the key            the LIBRARY
```

## The three things the brain does

1. **Holds the API key** — it lives only on this Mac (`brain.env`), never on the
   device, never in a log.
2. **Injects your reference library WHOLE** into every request (no RAG / no
   vector store — a personal-project bible fits the context window, and
   whole-context injection can't silently drop a continuity fact).
3. **Streams the reply back unchanged** (SSE pass-through). The device only ever
   knows this Mac's address.

## Honest privacy note

With a **cloud** upstream (Gemini/Claude) your references + chapter *are* sent to
that provider — that's inherent to any cloud model; the brain doesn't change it.
What the brain protects is your **key** (stays here) and your **architecture**:
switching the upstream to a **local** model later — so *nothing* leaves your
network — is a one-line edit in `brain.env`, with zero device rebuild.

## Setup (one time)

```bash
cd brain
cp brain.env.example brain.env
# edit brain.env → paste your free Gemini key into BRAIN_UPSTREAM_KEY
#   (get one at https://aistudio.google.com — it's an AIza... key)
```

Put your reference files (`.md` / `.txt`) in `~/layuv-brain/references/`
(created for you, with a sample bible already in it). Edit/add files anytime —
they're re-read on every request, no restart needed. To use a different folder,
set `BRAIN_REFERENCES_DIR` in `brain.env`.

## Run

```bash
cd brain
python3 leamh_brain.py
```

It prints the exact settings to type into Layuv, e.g.:

```
Point Layuv's AI settings here:
  Endpoint (base URL):  http://192.168.1.50:8077/v1
  Model             :  brain        (anything — the Mac decides)
  Key               :  (leave blank)
```

## Point Layuv at it

In Layuv → **Help & About → Ask AI → AI settings**:

| Field | Value |
|---|---|
| Endpoint (base URL) | `http://<this-mac-LAN-ip>:8077/v1` (from the banner) |
| Model | anything (e.g. `brain`) — the Mac decides the real model |
| Key | blank on a trusted LAN, or your `BRAIN_TOKEN` if you set one |

Tap **Test connection** → should say **Connection OK**. The Supernote must be on
the **same Wi-Fi** as this Mac.

## Verify injection (the whole point)

The sample bible plants facts that exist **only** in the references — so if the
model knows them, injection works. Quick check without the device:

```bash
curl -N http://127.0.0.1:8077/v1/chat/completions \
  -H 'content-type: application/json' \
  -d '{"model":"brain","stream":true,"messages":[
       {"role":"user","content":"In one short line: what is the moon called in this project, and what is the currency?"}]}'
```

A correct answer (the moon is **Call**; the currency is the **siol**) proves the
library was injected — those facts are nowhere but the reference folder.

On-device end-to-end: annotate a chapter → **Send** → the rewrite reflects a
bible-only fact → the **Save & Open** draft card still works → and the key never
left this Mac (Layuv's prefs hold only the Mac URL).

## Files

| File | Purpose |
|---|---|
| `leamh_brain.py` | the whole server (stdlib only) |
| `brain.env.example` | copy to `brain.env`, fill in your key |
| `brain.env` | your secrets — **gitignored** |
| `~/layuv-brain/references/` | your reference library — never committed |

## Later phases (not built yet)

- **Remote reach** via Tailscale/Headscale (set `BRAIN_TOKEN`, reach the brain
  as a `100.x` / `*.ts.net` address; Layuv's cleartext guard already trusts it).
- **Supernote batch path** via Supernote Private Cloud → a folder-watcher that
  reuses the `docx/` engine.
- **Evolving library** — an "approve draft → add to references" action.
