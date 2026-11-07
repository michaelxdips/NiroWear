# NiroWear 0.1.2

Release date: 2026-11-07. One behaviour change, and the instrumented suite executed for the first time.

## Artifact identity

- Package `com.nirowear`, `versionCode 12`, `versionName 0.1.2`, non-debuggable, minSdk 30, targetSdk 37.
- Signed with the NiroWear release key (`CN=NiroWear Release`), same identity as 0.1.1 — so 0.1.1
  installs update in place. A 0.1.0 install still needs an uninstall first (0.1.0 used the Android debug
  lineage).
- Built and signed by `.github/workflows/release.yml` from the `v0.1.2` tag.

## Validate is advisory; Save is not gated on a live API call

Save was `enabled = !busy && (!needsValidation || validated)`: no profile could be saved until a real
call to the provider succeeded. That is hostile on a watch — offline, behind a captive portal, or with a
provider briefly down — and it contradicted the screen's own "chat still allowed" message. On a Wear OS
37 emulator the four provider-setup tests could not reach Save at all: `clickable=true enabled=false`
with every field filled.

Now Save requires what a profile needs (name, base URL, model, key-or-existing-profile) and nothing
more. Saving unvalidated says so on screen; a wrong key then shows up at the first send as an error row
on the conversation, which is visible and recoverable.

Unchanged: **Validate · uses API** still makes one real (billable) call, **Discover models** still lists
what the provider advertises, discovery failure still never blocks manual entry, and the app still never
silently substitutes a model.

## Instrumentation, measured rather than claimed

The published figure was "48 connected tests, 48/48, last recorded 2026-11-02". It was not reproducible:
the suite is **66 methods** and had never executed on this branch. It now has, on a Wear OS 37 x86_64
AVD at 466×466:

- goal start: **66 tests, 31 failing**
- after this release: **66 tests, 18 failing, 0 errors**
- after the test-side fixes that followed: **66 tests, 2 failing, 0 errors**

An earlier revision of this file said "14 failing". That figure was **inferred**, not measured: it was
the 18 from the full-suite run minus four setup tests that had been re-run and passed in isolation.
The tests share one app process, so a class that passes alone can still fail in suite order; the
measured number for the whole suite is 18.

The two that remain are itemised per class in the repository README with the measured reason for
each — both are cases the emulator cannot provide: bezel/rotary input and a real phone browser for the
pairing certificate click-through. The soft-IME method now passes, with the limit it hit documented:
injected text and key events are swallowed while the soft keyboard window is up, so that test proves
the IME is requested and shown, the field takes focus, and the composer accepts a multi-word prompt
once the keyboard is dismissed. Nothing here is claimed green.

## Not verified here

- The 6 `@HostDrivenGate` methods are excluded from the default run and still have never executed.
- Keystore, Room and Compose paths have no JVM test; device proof for the Save change comes from the
  instrumented setup tests above.
