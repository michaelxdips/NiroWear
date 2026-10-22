# PRODUCT SPEC — NiroWear v0.1

NiroWear is a BYOK, multi-model AI chat client built specifically for Wear OS.

## Core jobs

- ask through keyboard or voice
- receive complete streamed AI answers
- read technical material clearly on the wrist
- switch models/providers
- maintain chat history
- edit previous questions

## Product boundary

NiroWear intentionally excludes desktop-workspace functionality that is impractical on a watch.

## Primary differentiation

MVP has six release gates: secure BYOK, dynamic provider/model selection, reliable
direct-to-provider streaming, excellent native rendering, keyboard/voice, persistent
linear editable history. BYOK and renderer converge early; neither can replace the
other. OpenAI-native, Anthropic, Gemini and generic compatible endpoints required.

Rendering quality on a tiny round display, especially for:

- technical prose
- mathematical notation
- Unicode symbols
- LaTeX
- matrices
- tables
- code
