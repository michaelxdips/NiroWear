package com.nirowear.settings

import com.nirowear.provider.ProviderType
import com.nirowear.provider.label

/**
 * Normalizes a pasted or typed API key.
 *
 * `SecretValue` requires printable ASCII only, so one stray newline or zero-width character from a
 * clipboard bridge becomes "Invalid credential format" with no indication of which byte was wrong.
 * Stripping is the correct repair here: no provider key contains whitespace, and the alternative
 * (rejecting the paste) makes the user hunt an invisible character on a 466 px screen.
 */
internal fun sanitizeKey(raw: String): String = raw.filter { it.code in 33..126 }

/** Provider implied by a key prefix, or null when the prefix is not distinctive. */
internal fun providerForKey(key: String): ProviderType? =
    when {
        key.startsWith("sk-ant-") -> ProviderType.ANTHROPIC
        key.startsWith("sk-proj-") || key.startsWith("sk-") -> ProviderType.OPENAI
        key.startsWith("AIza") -> ProviderType.GEMINI
        else -> null
    }

/**
 * Advisory hint when a key's shape contradicts the selected provider. Pasting a key into the wrong
 * preset is the most common setup error, and the generic "Check URL, model and API key" message
 * cannot name it. Never blocks a save: compatible endpoints legitimately use any format.
 */
internal fun keyMismatchHint(
    key: String,
    type: ProviderType,
): String? {
    val implied = providerForKey(key) ?: return null
    if (implied == type || type == ProviderType.OPENAI_COMPATIBLE) return null
    return "Key looks like ${implied.label}. Check provider."
}

/**
 * Wear OS can hand a focused text field to the paired phone through the IME's remote input surface.
 * Gboard ships that surface (`WearRemoteInputActivity`), so its presence in the enabled IME list is
 * the closest available signal. Detection is deliberately by IME package and not by the speech
 * intent: `RECOGNIZE_SPEECH` resolving proves voice handoff, not text handoff.
 *
 * Reads `InputMethodManager.enabledInputMethodList`, not `Settings.Secure.ENABLED_INPUT_METHODS`:
 * the latter throws SecurityException for targetSdk above 33.
 *
 * ponytail: one-package heuristic; replace when a documented capability API exists.
 */
internal fun remoteTypingHint(enabledImePackages: Set<String>): String? =
    if (REMOTE_INPUT_IMES.any { it in enabledImePackages }) "Tip: type on your paired phone." else null

internal fun enabledImePackages(context: android.content.Context): Set<String> =
    runCatching {
        (
            context.getSystemService(
                android.content.Context.INPUT_METHOD_SERVICE,
            ) as? android.view.inputmethod.InputMethodManager
        )?.enabledInputMethodList
            ?.map { it.packageName }
            ?.toSet()
            .orEmpty()
    }.getOrDefault(emptySet())

internal val REMOTE_INPUT_IMES = setOf("com.google.android.inputmethod.latin")

/**
 * User-facing text for a failed Validate/Save.
 *
 * `SafeProviderException` already carries a precise `ProviderError.userMessage`; anything else is an
 * unclassified failure (bad URL shape, a `require` in an adapter, an unexpected throw), where the
 * generic three-field string is still the honest answer. 401 therefore reads "API key rejected" and
 * 404 reads "Model unavailable" instead of one message that blames three fields at once.
 */
internal fun validationMessage(failure: Throwable): String =
    (failure as? com.nirowear.provider.SafeProviderException)?.error?.userMessage
        ?: "Check URL, model and API key."
