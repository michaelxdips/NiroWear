package com.nirowear.provider

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Input is API base, never full generation URL. Root defaults to /v1; explicit paths stay explicit. */
object Endpoints {
    fun normalize(
        raw: String,
        allowLocalHttp: Boolean = false,
    ): String {
        val url =
            try {
                raw.trim().toHttpUrl()
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Enter valid HTTPS API base URL")
            }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "Base URL cannot contain credentials, query or fragment"
        }
        require(url.isHttps || (allowLocalHttp && url.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))) {
            "HTTPS required"
        }
        val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        // Reject a full ENDPOINT pasted as a base. Only `chat/completions` and a trailing
        // `models`/`responses` were guarded, so the URL from Anthropic's own cURL example
        // ("…/v1/messages") was accepted and every request went to "/v1/messages/messages"; a Gemini
        // ":generateContent" URL produced "…:generateContent/models/…"; and "…/v1/models/{id}" looked
        // like a base. All of them 404, and 404 maps to "Model unavailable. Choose another model." — so
        // a URL typo was reported as a model problem.
        //
        // A resource segment ANYWHERE in the path means the user pasted a request URL, not a base. A
        // ':' segment is a Gemini action ("models/x:streamGenerateContent"), never a base. "chat" is
        // only checked as a trailing pair, because a proxy path may legitimately contain it.
        val resourceSegments = setOf("messages", "models", "responses", "embeddings", "completions")
        require(
            segments.takeLast(2) != listOf("chat", "completions") &&
                segments.none { it.lowercase() in resourceSegments } &&
                url.pathSegments.none { ':' in it },
        ) {
            "Enter API base, not request endpoint"
        }
        while (segments.size >= 2 && segments.takeLast(2) == listOf("v1", "v1")) segments.removeAt(segments.lastIndex)
        if (segments.isEmpty()) segments += "v1"
        return url
            .newBuilder()
            .encodedPath("/")
            .apply {
                segments.forEach { addPathSegment(it) }
            }.build()
            .toString()
            .trimEnd('/')
    }

    fun compatible(
        base: String,
        resource: String,
        allowLocalHttp: Boolean = false,
    ): HttpUrl =
        normalize(base, allowLocalHttp)
            .toHttpUrl()
            .newBuilder()
            .apply {
                resource.split('/').forEach { addPathSegment(it) }
            }.build()
}

/**
 * Header names whose values are credentials.
 *
 * [redactHeaders] currently has no production caller — this app logs nothing — so treat it as the
 * redactor to use the moment any diagnostic path prints request headers, and as the reason a new
 * adapter has one obvious list to extend. Its behaviour is pinned by CompatibleProviderTest; the
 * previous comment claimed diagnostic paths already read it, which was not true.
 */
private val CREDENTIAL_HEADERS = setOf("authorization", "x-api-key", "x-goog-api-key", "api-key")

fun redactHeaders(headers: Map<String, String>): Map<String, String> =
    headers.mapValues { (name, value) -> if (name.lowercase() in CREDENTIAL_HEADERS) "[REDACTED]" else value }
