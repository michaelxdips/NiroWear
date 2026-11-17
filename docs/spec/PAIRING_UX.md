# Pairing UX — plan

Status: **shipped** — NFC HCE emulation and the on-watch QR are in the app; see
[What shipped and what was measured](#what-shipped-and-what-was-measured) for the literal evidence
and for the two steps that still need a phone.

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

QR is shipped too, and the verification gap was closed rather than dodged: `com.google.zxing:core:3.5.3`
is a dependency now (Apache-2.0, pure JVM, ~340 KB) and `PairingQrTest` **decodes the pixels the canvas
would paint** at every width the watch actually uses, so the decode is evidence about the render, not
about the encoder's own matrix.

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

## What shipped and what was measured

Measured on the reference watch (Xiaomi Watch 2, API 34, 466x466 @320 dpi), 2026-11-17:

- **NFC files** `NdefTag.kt` (NDEF message, Type 2 Tag capability container, APDU responder) and
  `NfcPairingService.kt` (`HostApduService`), gated by `NfcPairingBridge`, which is armed by the pairing
  screen for exactly the session TTL. `NfcPairingTest`: 7 tests, 0 failures — exact URI record bytes,
  the full SELECT/READ exchange a reader performs, `Le=0x00` meaning 256 bytes, refusals (`6A82`,
  `6D00`, `6B00`), and expiry at the deadline.
- **`dumpsys nfc`** while pairing is armed: `*DEFAULT* ApduService: ComponentInfo{com.nirowear/
  com.nirowear.settings.NfcPairingService}, UID: 10110, description: NiroWear pairing, Static AID
  Groups: Category: other, AIDs:D2760000850101, F001020304050607`. The service is registered and the
  default for the `other` category, so the AIDs are routed to it.
- **QR** `QrCode.kt` + the canvas in `ProviderScreen.kt`. The QR node's accessibility bounds measured
  346x346 px at 466 and 264x264 px at 384, i.e. a 330 px and a 248 px canvas after the 4 dp inner
  padding. `PairingQrTest` decodes the planner's own pixels at 362, 330, 280 and 248 px: 3 tests,
  0 failures, with the smallest dial keeping >= 4 px per module.
- **End-to-end payload** with a non-browser client (Python `cryptography`: ECDH P-256 + AES-GCM, the
  token as AAD) against the watch's own TLS listener: `PAIR status=200 body={"ok":true}`, and the
  screen then showed `Saved securely.` The paired profile appeared in the header as `Zyr · gpt-6-luna`.
- **Adaptive**: at `wm size 384x384` the same pairing screen renders both options with no clipped text
  node; the QR needs one scroll, which the LazyColumn provides. Reset to 466 afterwards.

Still unverified, and deliberately not claimed:

- **A physical tap.** No handset with an NFC reader took part; the NDEF bytes and the APDU exchange are
  proven in JVM, the tap is not.
- **A camera scan.** The provider screen carries `FLAG_SECURE`, so its screenshots are black by design;
  the raster decode in `PairingQrTest` is the substitute, and a human scan is still owed (#28).
