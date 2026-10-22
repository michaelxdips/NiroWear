# NiroWear privacy facts — validation candidate

Technical disclosure draft, not an approved legal privacy policy or Play Data Safety declaration. Publisher/controller identity, public policy URL, jurisdiction/contact and channel approval remain external prerequisites.

## On device

Room stores conversation titles, USER/ASSISTANT raw content, statuses, timestamps and assistant provider/model provenance. OpenAI phase metadata stores visible message boundaries and original adapter scope. DataStore stores provider settings and selected profile/model, including credential references. These are local application data; conversation content is not claimed encrypted by this implementation.

API keys are encrypted using Android Keystore-backed AES-GCM with versioned ciphertext/nonce and credential-reference authentication. Failed encryption/decryption requires re-entry, without plaintext fallback. Saved key values are not loaded into editing fields. Provider settings use `FLAG_SECURE`. Security properties reduce risk, not absolute guarantees.

Backup is disabled. Uninstall or clear data removes application history/settings and can lose access to keys. No cloud conversation recovery is configured. Device-bound ciphertext cannot be treated as portable credential backup.

## Network

At user request, credentials and conversation context are sent directly to selected provider endpoint for discovery, validation or generation. Release requires HTTPS and platform certificate trust. No separate NiroWear relay/backend or analytics SDK is configured. Provider operator may process, retain or bill requests according to its own terms; third-party endpoint labels do not establish official upstream identity.

OpenAI generation sets `store=false`; that does not establish universal zero-retention guarantees. Stop cancels local underlying HTTP call and preserves partial content. It does not guarantee remote computation/billing stops. No automatic HTTP request replay is configured.

## Voice and links

Voice input delegates to installed system recognition activity. Its microphone use, remote processing and privacy policy depend on system recognizer; NiroWear has no direct microphone permission in shipping manifest. Actual speech recognition is not accepted on physical hardware yet. Review recognizer-produced text before Send.

External links delegate to Android/Wear handlers. Destination operator receives whatever its handler/navigation sends; NiroWear has no in-app browser. Final Wear/phone destination completion remains unverified in available environment.

## Release requirements

Before Play submission, supply owner-approved in-app/public privacy policy, accurate data disclosures, retention/deletion/support contact, provider/recognizer processing disclosure and AI-content reporting/safeguard policy. Current technical draft does not satisfy those publication gates by itself.
