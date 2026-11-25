# Screenshot provenance

Six genuine NiroWear application screenshots, 466 × 466 px, font scale 1.0. Byte-identical copies —
no crop, no mocked UI, no recoloring, no added watch frame. Total gallery is 145,035 B (~142 KiB).

Each image shows a scroll viewport, not a claim that a whole answer fits on screen at once.

| File | Actual state |
|---|---|
| `chat-home.png` | New chat screen with Keyboard and Voice entry points |
| `real-response.png` | Genuine streamed HTTPS answer (Rayleigh scattering) |
| `streaming-stop.png` | Active streaming with the Stop control |
| `table-record.png` | Markdown table converted to labelled vertical records |
| `code-reader.png` | Kotlin code block; horizontal continuation is intentionally pannable |
| `history.png` | History screen with a saved conversation |

Five gallery captures (all except `streaming-stop.png`) come from a 466 × 466 emulator running a non-debuggable build (`8b29475e`). `streaming-stop.png` was recaptured on `nirowear-b` (466 × 466 emulator, API 34) using debug build `ae91c033` from `3c5a60b` after centering the Stop label (`dx=0, dy=0`). Model and profile labels describe the
*configured* selection; they are not independent proof of an upstream provider's identity. Response
traffic used a configured third-party HTTPS endpoint through the native Responses adapter.

No API keys, provider editor screens, raw HTTP traffic or user storage appear in any capture.

Dimensions, byte sizes and hashes: [provenance.json](provenance.json).
