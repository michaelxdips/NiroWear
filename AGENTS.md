# AGENTS.md — NiroWear

NiroWear is a BYOK AI chat client for Wear OS: Kotlin, Jetpack Compose for Wear OS, Room, OkHttp, one `:app` module, MIT licensed.

This file is the working contract for anyone — human or AI agent — changing this repository. It describes what the product is, what it must never become, and which engineering properties are load-bearing.

## Product contract

NiroWear must provide:

- AI chat
- keyboard input
- voice input
- BYOK
- provider/model switching
- streaming responses
- full conversation history
- edit a previous user message
- retry an assistant response
- long readable answers
- Markdown
- Unicode and special symbols
- code blocks
- LaTeX/math
- matrices, cases, aligned math
- tables adapted to a watch display
- links

NiroWear must **not** add, unless explicitly requested later:

- image generation
- image input / camera
- file or PDF upload
- agents
- MCP as a product feature
- browser / web-search UI
- artifacts / canvas
- workspaces / projects
- branching conversations
- alternate response variants
- memory-management UI
- token / cost UI
- elaborate model parameter controls
- local LLM

## UX contract

- Reference viewport: **round 466 × 466 px**, Xiaomi Watch 2 class.
- Never shorten an answer merely because the screen is small. Solve small screens with layout, scrolling, typography and content transformation.
- No oversized chat bubbles or avatars. Assistant output reads like a document, not a messenger bubble.
- Keyboard and voice are both first-class entry points.
- Long answers must stay readable.

## Editing contract

Editing an earlier user message is linear:

1. replace the edited message;
2. delete every message after it;
3. resend context through that message;
4. generate a new continuation.

Never create branches.

## Engineering contract

**Provider truth.** Precedence: current official API docs, then current SDK/reference docs, then inspected reference implementations, then this source, then model memory. Never guess a protocol from a model name. OpenAI-native Responses and generic compatible Chat Completions are distinct adapters.

**Canonical history** is provider-neutral USER/ASSISTANT raw content. Assistant rows record provider profile ID and model ID. Switching providers preserves one linear history.

**Adapters** own instruction mapping, SSE parsing and semantic completion. EOF alone is not success — detect the protocol's terminal event.

**Stop** must cancel the underlying HTTP request, persist partial STOPPED content, and settle safely before the next send. Never automatically replay visible output.

**Credentials.** Never log `Authorization`, `x-api-key`, `x-goog-api-key`, or any key. Never place a credential in a URL, a filename, a notification, or an error message. Persist only versioned AES-GCM ciphertext and nonce outside the Keystore; profiles store credential references, not keys. Encryption or decryption failure requires re-entry — **no plaintext fallback**. Mask entry; never repopulate a saved key into an editing field. Backup stays disabled because keys are device-bound.

**Model listing and authentication are separate.** Compatible providers must accept a manually typed model ID even when discovery fails. Never silently change a model.

**Dependencies.** No new third-party dependency for something the standard library or the platform already does. Justify any addition in one line in the pull request.

**Architecture.** Prefer pragmatic Android architecture; avoid over-engineering. Keep one application module unless evidence proves otherwise.

Preferred stack: Kotlin, Jetpack Compose, Compose for Wear OS Material 3, Coroutines + Flow/StateFlow, Room, DataStore where useful, OkHttp, kotlinx.serialization, Android Keystore, Gradle Kotlin DSL.

## Renderer contract

The response renderer is a core subsystem, not a formatting detail.

Never render a whole assistant response as one giant text node. Represent semantic blocks: paragraph, heading, bullet list, numbered list, quote, inline code, code block, inline math, display math, table, divider, link.

Raw assistant Markdown must be stored so the renderer can evolve later.

## Math contract

Math must support readable Unicode and LaTeX, including Greek letters, operators, calculus symbols, set theory, logic, vectors, fractions, roots, powers and subscripts, integrals/summations/limits, matrices, cases and aligned equations.

Never shrink a wide formula until it becomes unreadable. Prefer, in order: natural layout, math-aware line breaking, horizontal pan, and only then modest scaling.

## Streaming contract

Streaming is mandatory.

If the user is near the bottom, follow incoming content. If the user scrolls upward, do not pull them back down — show a small `Latest` action instead.

Partial content must remain visible after interruption.

## Verification discipline

Never claim a build, test, emulator or device run succeeded unless it was actually executed and its output read. Quote literal numbers from result files, not from memory.

Instrumentation tests are destructive. Guard the device identity before running them, and never run them against a watch holding real credentials or conversations.

## Scope changes

Adding a feature from the out-of-scope list is a product decision, not an implementation detail. Raise it in an issue first.

## License

Application source is MIT (see `LICENSE`). By contributing you agree to license your contribution under the same terms.
