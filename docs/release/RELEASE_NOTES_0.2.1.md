# NiroWear 0.2.1

Release date: 2026-11-15. A maintenance cut: the pairing listener stops letting one unauthenticated
peer deny the session, the dependency set is current again, and every published number is re-derived
from a result file rather than carried forward.

## Artifact identity

- Package `com.nirowear`, `versionCode 14`, `versionName 0.2.1`, non-debuggable, minSdk 30, targetSdk 37.
- Signed with the NiroWear release key (`CN=NiroWear Release`), the same identity as 0.1.1, 0.1.2 and
  0.2.0, so it updates in place. Only a 0.1.0 install still needs an uninstall first (it used the
  Android debug lineage).
- Built and signed by `.github/workflows/release.yml` from the `v0.2.1` tag.

## What was run for this cut

Read from the result XML, not from memory:

- JVM unit tests **283 / 283, 0 failures, 0 errors, 44 suites**.
- `ktlintCheck` **0 errors**; Android Lint **0 issues** (`No issues found.`).
- `assembleDebug` and `assembleRelease` **pass** on this tree (`app-debug.apk` 34,693,547 B,
  `app-release-unsigned.apk` 3,689,873 B locally). The published artifact is the one the workflow signs
  from the tag; a local cut of the same tree is not bit-identical.
- **NOT TESTED for this cut: the connected suite.** No emulator was started, and instrumentation is
  destructive. The last measurement stays 2026-11-09: 71 tests, 2 failures, 0 errors — the two are
  hardware-bound (#27, #28).

## Pairing no longer lets one peer deny the session

The LAN listener accepted one connection, served it, and only then accepted the next. Over TLS the
first read is where the handshake happens, so a peer that connected and sent nothing held the whole
session for one `soTimeout` (2 s) — serially, once per connection.

Measured with a raw-socket probe, before and after:

| scenario | 0.2.0 | 0.2.1 |
|---|---|---|
| 4 idle preconnects, then the real browser | 8,015 ms | 7 ms |
| 8-socket preconnect burst, then the real browser | 16,018 ms | 2,033 ms |
| 8-socket burst + real browser, connect outcome | `ConnectException` | 200 |

That was linear in the attacker's connection count against a 120 s capability: a burst of about sixty
pushed the phone's first request past the deadline, and the user saw a page that never loaded with
nothing on the watch explaining it.

Requests are dispatched to their own handler threads now, capped at eight in flight, so the overflow
waits a slot in the kernel queue instead of queueing the real browser behind every hostile connection
in turn. Single use is a compare-and-set — two concurrent requests could otherwise both save — and
`close()` closes every live client. The accept queue is 32 instead of 8, because with 8 a burst made
the browser's own connect fail: Windows answers a full accept queue with RST.

What did **not** change: the handshake is still unauthenticated by design, the capability token in the
URL is still what authorises a payload, the certificate is still self-signed and minted per session,
and the page still says so. Nothing here adds a trust assumption.

## Dependencies

`androidx.datastore:datastore-preferences` **1.2.1 → 1.3.0**. No API this app uses changed.

## Corrections carried in this cut

- The published string count was **89**; the resource files hold **105** keys in both `values/` and
  `values-in/`, none missing. The 16-key difference is exactly what the 0.2.0 localisation change added.
- The CI workflow comment said `compileSdk 36` while the build uses `37`.
- Lint's `GradleDependency` detector makes a "0 warnings" figure expire on its own when a library
  publishes, with no commit to this repository. It had already expired for 0.2.0; the bump above is
  what makes it true again.

## Upgrade

Install over 0.2.0. Chats and saved keys survive; keys are device-bound and are never copied off the
watch. Nothing is migrated in this release — the Room schema is unchanged at version 2.
