# Beta.7 Test Sheet — no adb, no computer needed

Install the beta.7 APK from the release page on your phone, then sideload to the
watch (or install direct if your watch supports it). Every step happens on the
watch. All you need: home Wi-Fi and one provider API key.

## 1. Add a provider manually

- Open NiroWear → Menu → Providers → Add.
- Type a name, pick a provider type, paste the Base URL if custom, paste the API key.
- Tap `Validate · uses API`.
- What to see: success check, or a precise error ("API key rejected" vs "Model unavailable").
- Report back: which provider, and the exact message if validation fails.

## 2. Stream an answer

- Type any question and send.
- What to see: the answer streams in progressively; long answers stay readable
  (scroll vertically, pan wide tables/code sideways).
- Report back: does anything render as a giant bubble, or is it document-like?

## 3. Stop mid-stream

- Send a question that gives a long answer, then tap Stop while it streams.
- What to see: streaming halts; the partial answer stays visible with
  "Stopped · partial answer kept".
- Report back: is the partial text still there after stopping?

## 4. Retry

- After an answer completes, tap Retry.
- What to see: a fresh continuation generates below the same question.
- Report back: old answer replaced or duplicated? Any error?

## 5. Edit a message

- Long-press (or open) one of YOUR earlier messages and edit it.
- What to see: everything after it is replaced by a new continuation.
- Report back: did later messages disappear as expected, or linger?

## 6. Switch model

- Change the model in provider settings, then send a new message.
- What to see: the new answer comes from the new model; history stays one linear thread.
- Report back: model name shown, and history intact?

## 7. Pair over LAN (home Wi-Fi only)

- Providers screen → Set up from phone. The watch shows a URL.
- On a phone/laptop **on the same Wi-Fi**, open that URL and paste your key.
- What to see: the watch confirms the key arrived; nothing asks for anything
  except the key; the page warns the handshake is unauthenticated.
- Report back: did the key arrive? Did the URL on the page match the watch exactly?
- Prefer typing the key in manually on untrusted/public Wi-Fi.

## 8. Voice input

- Tap the voice button and speak a question.
- What to see: recognized text lands in the input field, ready to send.
- Report back: recognition worked? Language correct?

## 9. What NOT to expect yet (known limits)

- No stable channel: every release is a prerelease; reinstall per beta.
- A fresh install cannot upgrade an installed beta signed with a different key —
  uninstall first if install fails.
- Live provider smoke, TalkBack pass, and native-math licence review are still open;
  release builds typeset math natively, debug builds too.
