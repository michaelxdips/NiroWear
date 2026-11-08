# Security policy

## Reporting a vulnerability

Report privately through GitHub's **Security → Report a vulnerability** on this repository. Do not
open a public issue for a suspected vulnerability.

Please include: the affected version (`versionName` from the APK's `package:` line, e.g.
`aapt2 dump badging <apk>`, or just the APK filename), a description of the impact, and the smallest
reproduction you can manage. The app has no About screen to read it from.

This is a single-maintainer project with no paid on-call. Expect an initial reply within about a
week. If a fix ships, it will be credited in the release notes unless you ask otherwise.

## What is in scope

The app stores a third-party API key on a watch and sends it only to the endpoint you configure.
Anything that breaks one of these is in scope:

- Reading a stored key without the Android Keystore, or recovering it from a backup, a log, a crash
  report, a filename, or a URL.
- Causing the pairing page to hand a key to a party other than the phone that opened the capability
  URL.
- Making the app send a key, or a message body, to a host other than the configured endpoint —
  including via a redirect, a retry, or a downgraded connection.
- Rendering untrusted Markdown in a way that executes script, follows a `javascript:` or `intent:`
  URL, or escapes the watch UI.

## What is not in scope

- **The model's output.** The app displays what the provider returns. A provider producing wrong or
  unsafe text is a provider issue.
- **The signing identity.** Every published release is signed with the dedicated NiroWear release key
  (`CN=NiroWear Release`, the identity 0.1.1 and later share), built by
  `.github/workflows/release.yml` from a `v*` tag. A build you make from source is unsigned unless you
  set the `NIROWEAR_KEYSTORE` variables yourself, so a self-built APK is not the published artifact. A
  signing mismatch is a release-process question, not a vulnerability.
- **The pairing TLS certificate.** The pairing page is served over HTTPS on the local network with a
  certificate the watch mints for that one session (`settings/SelfSignedCert.kt`), so the phone shows
  the usual self-signed warning, and nothing pins or trusts that certificate
  (`docs/adr/0001-no-tls-pinning.md`). The certificate is not what authorises a payload: the key is
  encrypted with ephemeral ECDH P-256 and AES-GCM, and the session is gated by a 128-bit capability
  token that is only visible on the watch screen. The TLS handshake itself is unauthenticated, so an
  attacker who can read the watch screen, or who controls your LAN, is outside the threat model — but
  say so if you find a way to get a key without either.
- **A rooted watch or a compromised OS.** The Keystore guarantee assumes the platform is intact.

## Supported versions

Only the newest published release is supported. Published APKs are signed with the NiroWear release
key by the tag workflow (`.github/workflows/release.yml`) and disclose their identity in the release
notes; a local `assembleRelease` without the `NIROWEAR_*` signing environment produces an unsigned APK.
