# Security policy

## Reporting a vulnerability

Report privately through GitHub's **Security → Report a vulnerability** on this repository. Do not
open a public issue for a suspected vulnerability.

Please include: the affected version (`versionName` from **Settings → About**, or the APK filename),
a description of the impact, and the smallest reproduction you can manage.

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
- **The debug signing key.** Published beta APKs are signed with `CN=Android Debug`. That is
  documented, not a vulnerability. It does mean you should not trust a beta build as if it were a
  production release; see the README's known limitations.
- **Cleartext on your own LAN during pairing.** The pairing page is served over plain HTTP on the
  local network by design, because a watch cannot present a certificate a phone will trust. The key
  payload is encrypted with ephemeral ECDH P-256 and AES-GCM, and the session is gated by a 128-bit
  capability token that is only visible on the watch screen. An attacker who can read the watch
  screen, or who controls your LAN, is outside the threat model — but say so if you find a way to
  get a key without either.
- **A rooted watch or a compromised OS.** The Keystore guarantee assumes the platform is intact.

## Supported versions

Only the newest published prerelease is supported. There is no stable channel yet: every release is
a prerelease because the signing identity is still the debug key.
