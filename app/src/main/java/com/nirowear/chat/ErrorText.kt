package com.nirowear.chat

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
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

/** Renders a stored failure value: a known token becomes localization, anything else is shown as-is. */
internal fun errorText(
    resources: Resources,
    stored: String?,
): String? {
    if (stored == null) return null
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
