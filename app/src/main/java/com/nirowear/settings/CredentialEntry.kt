package com.nirowear.settings

import com.nirowear.provider.ProviderType

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
 * The provider a key's shape points at when it CONTRADICTS the selected preset, or null.
 *
 * Returns the CAUSE, not a sentence, for the same reason [validationCause] does: the sentence has to
 * resolve in the reader's language at display time, and a sentence baked in here was measured
 * rendering English inside the Indonesian provider screen (#66). Never blocks a save: compatible
 * endpoints legitimately use any format.
 */
internal fun impliedProviderMismatch(
    key: String,
    type: ProviderType,
): ProviderType? {
    val implied = providerForKey(key) ?: return null
    if (implied == type || type == ProviderType.OPENAI_COMPATIBLE) return null
    return implied
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
internal fun remoteTypingAvailable(enabledImePackages: Set<String>): Boolean =
    REMOTE_INPUT_IMES.any { it in enabledImePackages }

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
 * The cause behind a failed Validate/Save, or null when nothing classified it.
 *
 * Returns the CAUSE, not a sentence: the sentence depends on the reader's language and is resolved at
 * display time (`chat/ErrorText.kt`). Keeping this pure also means the classification is testable on the
 * JVM, without Android resources. `SafeProviderException` carries a precise [ProviderError]; anything
 * else is unclassified (bad URL shape, a `require` in an adapter, an unexpected throw), where the
 * caller shows the generic three-field hint instead of inventing a cause.
 */
internal fun validationCause(failure: Throwable): com.nirowear.provider.ProviderError? =
    (failure as? com.nirowear.provider.SafeProviderException)?.error
