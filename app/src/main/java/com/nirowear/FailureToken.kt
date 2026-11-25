package com.nirowear

/**
 * Failure tokens this app stores for causes that are not a provider error: history/settings storage,
 * refusals, and the truncation note.
 *
 * Same rule as `ProviderError` tokens — the sentence is chosen when the value is DISPLAYED
 * (`chat/ErrorText.kt`), not when it is written, so a stored failure reads in the language the reader
 * switched to. The values are already in databases: keep them stable.
 */
object FailureToken {
    const val HISTORY_UNREADABLE = "HISTORY_UNREADABLE"
    const val HISTORY_RETRYING = "HISTORY_RETRYING"
    const val HISTORY_UNAVAILABLE = "HISTORY_UNAVAILABLE"
    const val KEY_CLEANUP_PENDING = "KEY_CLEANUP_PENDING"

    /**
     * Shown under an answer that ended before it finished. Deliberately CAUSE-NEUTRAL: Gemini's
     * CONTINUATION ends incomplete without hitting any output cap, so naming a length limit would be
     * untrue. The reader only needs to know the answer is not the whole answer.
     */
    const val TRUNCATED = "TRUNCATED"
    const val MESSAGE_GONE = "MESSAGE_GONE"
    const val NOTHING_TO_RETRY = "NOTHING_TO_RETRY"
    const val SEND_FAILED = "SEND_FAILED"
    const val ANSWER_NOT_SAVED = "ANSWER_NOT_SAVED"
    const val SAVE_STILL_FAILING = "SAVE_STILL_FAILING"
    const val SETTINGS_UNREADABLE = "PROVIDER_SETTINGS_UNREADABLE"
    const val SETTINGS_UNAVAILABLE = "PROVIDER_SETTINGS_UNAVAILABLE"
}
