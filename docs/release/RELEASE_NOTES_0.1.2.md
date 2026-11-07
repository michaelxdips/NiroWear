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
- after this release: **66 tests, 14 failing, 0 errors**

The remaining failures are itemised per class in the repository README with the measured reason for
each — a geometry assertion in the reader, fixtures that need a virgin app, 10 s coroutine budgets on a
software GPU, and three cases an emulator cannot provide (bezel input, a LAN phone, watch restart
semantics). Nothing here is claimed green.

## Not verified here

- The 7 `@HostDrivenGate` methods are excluded from the default run and still have never executed.
- Keystore, Room and Compose paths have no JVM test; device proof for the Save change comes from the
  instrumented setup tests above.
