# NiroWear 0.1.0 — stable release

Release date: 2026-11-03. Public beta-channel stable cut.

Four provider adapters (OpenAI Responses, Anthropic Messages, Gemini
GenerateContent, OpenAI-compatible Chat Completions), secure BYOK, dynamic
model selection, streaming with Stop, linear Retry/Edit, conversation history,
keyboard and system voice intent, structured reader with Markdown, tables,
code, Unicode and horizontal pan, LAN pairing transport, native math
typesetting in release builds, and the `Latest` control.

## Artifact identity

- Package `com.nirowear`, versionCode 10, versionName 0.1.0, nondebuggable.
- Signed with beta lineage identity, certificate SHA-256
  `7D:4E:6E:99:82:1D:90:4F:23:90:0B:D4:E0:CA:D5:B6:4B:1A:27:33:A2:82:43:DC:C8:AB:9D:6E:27:68:AB:E3`.
- Source tree: green commit `1b389a4` plus version bump only.

## Evidence

JVM suite green, lint 0 errors, connected suite 48/48 on emulator, release
assemble clean — full gates re-run on the stable tree before tagging. APK
bytes and SHA-256 measured at release time and recorded in the beta-repo
changelog entry.

## Known open items

Live provider smoke skipped per owner order (no owner-typed key on emulator);
owner BETA7 hardware sheet pending; TalkBack pass open; native-math licence
review open (JLaTeXMath exception ships in `assets/legal/`).

