# Pairing UX — plan

Status: proposal, not implemented. Owner-visible behavior today is unchanged.

## The problem, measured

Today the watch shows `https://<lan-ip>:<port>/<token>` and the phone must type it, then click through
a self-signed certificate warning (#28). Two failure points: typing a long URL on a phone, and the
interstitial. `PairingUiTest#keyArrivesFromPhoneWithoutTypingOnWatch` fails on an emulator only because
no browser exists to click through.

## What the hardware can actually do

Probed on the physical Xiaomi Watch 2 on 2026-11-16:

```
feature:android.hardware.nfc.any
feature:android.hardware.nfc.ese
feature:android.hardware.nfc.hce
dumpsys nfc: mState=on   mIsAlwaysOnSupported=false
```

HCE is present, so the watch can emulate an NDEF tag carrying the capability URL: a phone tap opens
the page, no typing. `mIsAlwaysOnSupported=false` means the screen must be on, which it is during
pairing.

## Options

| Option | Removes | Needs | Verifiable here |
|---|---|---|---|
| NFC HCE + NDEF URL | typing the URL | phone with NFC, screen on | NDEF bytes in a JVM test; the tap needs a phone |
| QR on the dial | typing the URL | camera + a QR decoder | **no decoder installed** (`qrcode`, `segno`, `cv2`, `pyzbar` all absent), so an encoder would prove only self-consistency |
| Manual URL (today) | nothing | nothing | yes |

QR is therefore designed but not shipped: shipping an encoder nobody can scan-test violates the
repository's verification discipline. If a decoder is added to the verifying machine, QR becomes the
second path — it is the only one that works when the phone has no NFC.

## Proposed flow

1. Watch enters pairing mode (as today) and shows the same URL and token.
2. If the device has HCE, it registers a `HostApduService` that answers SELECT AID and READ BINARY with
   a single NDEF message containing the capability URL. The service lives only for the 120 s session.
3. Phone taps the watch → browser opens the URL directly.
4. Everything after that is unchanged: the page is served over TLS with a per-session self-signed
   certificate, the capability token authorises exactly one payload, and the certificate warning still
   has to be accepted (NFC and QR remove typing, not the warning).

Security invariants that must not change: the token stays unguessable, the listener stays one-shot
(compare-and-set), `close()` closes every live client, no plaintext fallback, no credential in a URL,
log or filename.

## Implementation sketch (no new dependency)

- `NfcPairingService : HostApduService` — SELECT AID `F001020304050607` (project-chosen), then CAPABILITY
  CONTAINER / NDEF read commands; returns the same NDEF bytes a real tag would.
- `NdefRecord(TNF_WELL_KNOWN, RTD_URI, ...)` with the capability URL; the byte array is a pure function
  and gets a JVM test (`NfcPairingNdefTest`) that asserts the exact APDU responses for SELECT/READ.
- Manifest: `android.permission.NFC` (not required), `uses-feature nfc.hce` optional, service with
  `android:permission="android.permission.BIND_NFC_SERVICE"` and the HCE intent filter.
- Toggle: only while the pairing screen is open; deregister on close or timeout.
- Fallback: current URL screen stays, so a phone without NFC is unaffected.

## Verification plan

1. JVM: exact NDEF payload and APDU response bytes (runs in CI).
2. Watch: `dumpsys nfc` shows the service registered while the pairing screen is open, and gone after.
3. Phone: one tap opens the URL (manual, recorded in #28/#51). This half cannot be automated here.
4. Regression: the certificate-refused path must still leave the watch silent, not hanging.

## Adaptivity rules this must respect

Pairing is a watch UI like any other: touch targets stay 48 dp, the screen must fit a 384x384 dial
(192 dp) without clipping, and nothing may assume a 466 px width. The QR path, if ever shipped, must
render legibly at 384 px with the lowest error-correction level that still scans.
