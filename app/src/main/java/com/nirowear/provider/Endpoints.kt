package com.nirowear.provider

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Input is API base, never full generation URL. Root defaults to /v1; explicit paths stay explicit. */
object Endpoints {
    /**
     * Hosts whose bare form has a documented version that is NOT /v1. Only consulted when the URL
     * carries no path segment, so a user who types a version keeps it.
     */
    private val DEFAULT_VERSION_BY_HOST = mapOf("generativelanguage.googleapis.com" to "v1beta")
    private val LOCAL_HTTP_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")
    private val RESOURCE_SEGMENTS = setOf("messages", "models", "responses", "embeddings", "completions")

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
        require(url.isHttps || (allowLocalHttp && url.host in LOCAL_HTTP_HOSTS)) {
            "HTTPS required"
        }
        val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        // Reject a full ENDPOINT pasted as a base. A resource segment ANYWHERE in the path means the
        // user pasted a request URL, not a base; a ':' segment is a Gemini action.
        require(
            segments.none { it.lowercase() in RESOURCE_SEGMENTS } &&
                url.pathSegments.none { ':' in it },
        ) {
            "Enter API base, not request endpoint"
        }
        while (segments.size >= 2 && segments[segments.size - 2] == "v1" && segments[segments.size - 1] == "v1") {
            segments.removeAt(segments.lastIndex)
        }
        // A bare host must default to the version its API actually serves, not to a blanket /v1.
        // Google's REST reference documents the Gemini base as /v1beta ("GET /v1beta/models",
        // ai.google.dev/api/models), so normalising generativelanguage.googleapis.com to /v1 sent
        // every request one path segment away from a real endpoint; the 404 that followed maps to
        // MODEL_UNAVAILABLE, so a base-URL mistake was reported as "choose another model". Explicit
        // paths stay explicit: only a host with NO path segment is defaulted.
        if (segments.isEmpty()) segments += DEFAULT_VERSION_BY_HOST[url.host.lowercase()] ?: "v1"
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
 * Header names whose values hold credentials; redacted whenever diagnostic code inspects request headers.
 */
private val CREDENTIAL_HEADERS = setOf("authorization", "x-api-key", "x-goog-api-key", "api-key")

fun redactHeaders(headers: Map<String, String>): Map<String, String> =
    headers.mapValues { (name, value) -> if (name.lowercase() in CREDENTIAL_HEADERS) "[REDACTED]" else value }
