# NiroWear 0.2.0

Release date: 2026-11-08. The renderer stops withholding rows, eight product bugs are fixed, and the
whole UI is localised.

## Artifact identity

- Package `com.nirowear`, `versionCode 13`, `versionName 0.2.0`, non-debuggable, minSdk 30, targetSdk 37.
- Signed with the NiroWear release key (`CN=NiroWear Release`), the same identity as 0.1.1 and 0.1.2, so
  both update in place. Only a 0.1.0 install still needs an uninstall first (it used the Android debug
  lineage).
- Built and signed by `.github/workflows/release.yml` from the `v0.2.0` tag.

## What was run for this cut

Read from the result XML, not from memory:

- JVM unit tests **269 / 269, 0 failures, 0 errors, 43 suites**.
- `ktlintCheck` **0 errors**; Android Lint **0 issues** (`<issues format="6"/>`, "No issues found.").
- `assembleDebug` and `assembleRelease` **pass** on this tree. The published artifact is the one the
  workflow signs from the tag; a local cut of the same tree is not bit-identical.

## Nothing is withheld from a long answer any more

A table used to be one lazy item capped at 40 rows with a "… N more rows" line underneath, and a code
fence was a single `Text` of the entire fence — the ANR shape the cap existed to prevent, and a
contradiction of the rule that an answer is never shortened for the screen. Every table row and every
24-line code chunk is its own item now: the cap is gone, not raised. Measured on the AVD: a 120-row table
scrolls to row 119 and a 200-line fence keeps every line.

## Eight product bugs

1. **Stop downgraded a finished answer.** The `Cancelled` catch path wrote `STOPPED` unconditionally, so
   tapping Stop after a completed answer replaced its status. It only writes `STOPPED` when the turn had
   not already reached a terminal state.
2. **Retry with no user row blamed the provider.** It now refuses with "Nothing to retry in this chat."
   instead of sending a request that could not succeed.
3. **A `finish_reason`-only final chunk failed a complete answer.** Compatible providers may send the
   terminal chunk without a `delta`; the adapter treated that as malformed and failed a whole response
   that had arrived intact. The `delta` is optional now.
4. **A bare Gemini host produced 404s.** `https://generativelanguage.googleapis.com` without a version
   segment now resolves to `/v1beta`; explicit paths are left exactly as typed.
5. **Pairing delivered a payload after Cancel.** The server could still accept and return a payload that
   the closed screen no longer read; it checks the close flag now.
6. **Accepted-socket leak.** If `Thread.start()` threw, the accepted socket was never closed. It is
   closed in the loop's `finally`.
7. **One transient DataStore error stranded the provider screen.** The flow now completes instead of
   dying, with a bounded retry (3 attempts, 1/2/4 s).
8. **The dropped-cells notice was unreachable.** It lived on the non-lazy table path that no longer
   renders, so a table that lost cells said nothing; it now rides the last rendered row.

Found by this release's own gate: **the pairing listener refused the real browser behind a hostile
peer.** `serve()` joins each request with a hard time bound, which blocks the accept loop by design, and
the listen backlog was `1` — so a peer that filled the single queue slot made the phone's real connection
fail with `ECONNREFUSED`. Measured as `ConnectException` in
`PairingServerTest.aDribblingClientCannotHoldTheSession` during a full-suite run. The backlog is 8.

## The keyboard is handled on both typing screens

`targetSdk 37` is edge-to-edge on Android 15+, where the IME no longer shrinks the window: on a 466 px
dial the keyboard covered the field and the Save button. `adjustResize` plus `imePadding()` on the
composer and the provider form. The provider form's name, base URL and model survive Activity recreation;
the API key deliberately does not, because a saved-instance bundle is plaintext on disk.

## Accessibility: four measured dead stops removed

- The "Links" group node announced a count and carried **no action**, so TalkBack stopped on something it
  could not activate. It opens the first link now, with a localised plural action label.
- The composer field relied on the visible "Prompt" heading as its label. A heading is a separate node,
  so TalkBack reached an unlabelled edit box; the field has its own label.
- A credential entry announced its label twice. The label node is decorative now.
- The provider header was a fixed `130 dp` box with `maxLines = 1`, which clipped to "Add prov…" at a
  2.0x font scale. It grows to two lines inside the dial's safe band.

## The whole UI is localised, not just the renderer

89 user-visible strings moved out of `MainActivity`, `ProviderScreen` and `ReaderList` into
`values/strings.xml`, with a complete Indonesian set in `values-in/strings.xml`. Technical terms users
expect in English — API key, model, provider, token — stay in English. Every English value is
byte-identical to the literal it replaced.

## Freezes removed from the dial

`startPairing()` ran `SecureRandom`, a P-256 `KeyPairGenerator`, a self-signed certificate and a
`ServerSocket` bind inside `onClick`, and `PairingServer.close()` joined its worker for up to five
seconds on the caller. Both run off the UI thread now. Model discovery gained a 30 s whole-operation
budget; its pages were bounded only per-socket, so a slow endpoint could hold "Discovering…" for minutes.

## Bounded work where input is unbounded

Math layout is bounded at 16384 × 1024 px and 3,000,000 px area (a wider bound was tried first and
broke horizontal panning, so it was reverted), list nesting at depth 4, and a single `TextMeasurer` is
shared per item. `AnnotatedString` results and the inline-node map are memoised, mid-line `$$` is a
single pass, regexes are hoisted out of render loops, and the message update uses the row's ownership as
its test instead of a read-then-write pair.

## Smaller footprint

1.20 MB of dead Markdown artifacts (69 files plus `AUDIT.md`) and 5 stale tracked files are gone; the
documentation tree went from 1.4 MB to 285 KB across 33 files. `README.md` was rewritten around what was
measured. The licence and attribution assets (MPL-2.0 Public Suffix List offer, GPL-2.0 JLaTeXMath text)
are untouched and still ship byte-identical — `scripts/verify-apk.py` asserts it.

## Build and CI

`jvmTarget` is pinned to 17, the Gradle cache key hashes every toolchain input, ten actions are pinned by
commit SHA, and the release workflow is split into a read-only build job and a write-token `publish` job
so a signing key is never decoded in a job that can push. Re-running a release edits the existing GitHub
release instead of failing on it.

## Not verified here

- The 6 `@HostDrivenGate` methods are excluded from the default run and have still never executed (#29).
- Two instrumented methods need hardware the emulator cannot provide: bezel/rotary input, and a real
  phone browser for the pairing certificate click-through (#27, #28).
- CI still cannot compile the instrumented sources, which are private by policy (#30).
- Error strings that originate from providers and the network remain English-only (#31).
