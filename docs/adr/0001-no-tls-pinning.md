# ADR-001 — No TLS pinning; rely on platform trust

## Status

Accepted

## Context

NiroWear is BYOK: the user configures arbitrary provider endpoints
(OpenAI, Anthropic, Gemini, OpenAI-compatible hosts). Pinning one CA or
leaf would break every custom endpoint and require an out-of-band update
channel the app deliberately does not have (no backend, no update server).

## Decision

No `CertificatePinner`, no custom `TrustManager`/`HostnameVerifier`.
Release builds use platform trust with `usesCleartextTraffic=false`,
`followRedirects=false` + `followSslRedirects=false`, so a key cannot
leak via a 302. Credential-bearing URL query strings are rejected at the
endpoint layer (`Endpoints.normalize`). Debug builds additionally allow
cleartext only to `localhost`/`127.0.0.1`/`10.0.2.2`
(`app/src/debug/res/xml/debug_network_security.xml`), no user-CA trust
added. A self-signed MITM fails closed: `IOException` surfaces as
`NETWORK_UNAVAILABLE` with no certificate detail leaked.

## Consequences

An enterprise proxy with a platform-installed CA can still decrypt by
platform design; the app adds no bypass and no exception for it. If a
provider ever needs pinning, it becomes a per-profile opt-in, not a
global default — revisiting this ADR.
