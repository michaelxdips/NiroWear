package com.nirowear.chat

import java.util.Locale

/**
 * The BCP-47 tag handed to the platform recognizer with the voice intent.
 *
 * The app ships exactly two languages, so it asks for exactly two. Without this the intent carried
 * no `EXTRA_LANGUAGE` at all (measured: only `EXTRA_LANGUAGE_MODEL` and `EXTRA_PROMPT` were set), and
 * the recognizer fell back to whatever the phone behind Gboard Wear happens to be set to — an
 * Indonesian screen could then dictate English words.
 *
 * Regional variants are deliberately not distinguished (`en-GB` asks for `en-US`, `id-SG` for
 * `in-ID`): the UI itself does not vary by region, and a recognizer given a tag it does not carry
 * fails harder than one given the base language it does.
 */
internal fun voiceLanguageTag(locale: Locale): String =
    when (locale.language.lowercase(Locale.ROOT)) {
        "in", "id" -> "in-ID"
        else -> "en-US"
    }
