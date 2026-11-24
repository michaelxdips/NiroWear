package com.nirowear.chat

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.nirowear.FailureToken
import com.nirowear.R
import com.nirowear.provider.ProviderError

/**
 * Failure text, resolved at DISPLAY time.
 *
 * A stored failure is a token (`NETWORK_UNAVAILABLE`, or `TIMEOUT|partial`), never a sentence: the
 * sentence depends on the reader's language, and the row is written long before it is read. Values this
 * build does not recognise — every row written before the tokens existed, and any row a newer build
 * wrote — are returned unchanged, so nothing renders empty and nothing is lost.
 */
internal fun ProviderError.labelRes(): Int =
    when (this) {
        ProviderError.PROVIDER_REQUIRED -> R.string.provider_error_provider_required
        ProviderError.PROVIDER_UNSUPPORTED -> R.string.provider_error_provider_unsupported
        ProviderError.INVALID_CREDENTIAL -> R.string.provider_error_invalid_credential
        ProviderError.PERMISSION_DENIED -> R.string.provider_error_permission_denied
        ProviderError.RATE_LIMITED -> R.string.provider_error_rate_limited
        ProviderError.MODEL_UNAVAILABLE -> R.string.provider_error_model_unavailable
        ProviderError.NETWORK_UNAVAILABLE -> R.string.provider_error_network_unavailable
        ProviderError.TIMEOUT -> R.string.provider_error_timeout
        ProviderError.RESPONSE_TOO_LARGE -> R.string.provider_error_response_too_large
        ProviderError.PROTOCOL -> R.string.provider_error_protocol
        ProviderError.PROMPT_BLOCKED -> R.string.provider_error_prompt_blocked
        ProviderError.SERVER -> R.string.provider_error_server
        ProviderError.HTTP -> R.string.provider_error_http
        ProviderError.CREDENTIAL_REENTRY -> R.string.provider_error_credential_reentry
    }

/** Non-provider tokens ([FailureToken]); resolved by the same display-time rule as a provider cause. */
private val localLabels =
    mapOf(
        FailureToken.HISTORY_UNREADABLE to R.string.failure_history_unreadable,
        FailureToken.HISTORY_RETRYING to R.string.failure_history_retrying,
        FailureToken.HISTORY_UNAVAILABLE to R.string.failure_history_unavailable,
        FailureToken.KEY_CLEANUP_PENDING to R.string.failure_key_cleanup_pending,
        FailureToken.TRUNCATED to R.string.failure_truncated,
        FailureToken.MESSAGE_GONE to R.string.failure_message_gone,
        FailureToken.NOTHING_TO_RETRY to R.string.failure_nothing_to_retry,
        FailureToken.SEND_FAILED to R.string.failure_send_failed,
        FailureToken.ANSWER_NOT_SAVED to R.string.failure_answer_not_saved,
        FailureToken.SAVE_STILL_FAILING to R.string.failure_save_still_failing,
        FailureToken.SETTINGS_UNREADABLE to R.string.failure_settings_unreadable,
        FailureToken.SETTINGS_UNAVAILABLE to R.string.failure_settings_unavailable,
    )

/** Renders a stored failure value: a known token becomes localization, anything else is shown as-is. */
internal fun errorText(
    resources: Resources,
    stored: String?,
): String? {
    if (stored == null) return null
    localLabels[stored]?.let { return resources.getString(it) }
    val cause = ProviderError.parse(stored) ?: return stored
    val text = resources.getString(cause.labelRes())
    return if (ProviderError.isPartial(stored)) {
        resources.getString(R.string.provider_error_partial, text)
    } else {
        text
    }
}

/** Composable form; recomposes on a configuration change, so a language switch re-renders stored rows. */
@Composable
internal fun errorText(stored: String?): String? = errorText(LocalResources.current, stored)
