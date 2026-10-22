package com.nirowear.provider

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Input is API base, never full generation URL. Root defaults to /v1; explicit paths stay explicit. */
object Endpoints {
    fun normalize(raw: String, allowLocalHttp: Boolean = false): String {
        val url = try { raw.trim().toHttpUrl() } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Enter valid HTTPS API base URL")
        }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "Base URL cannot contain credentials, query or fragment"
        }
        require(url.isHttps || (allowLocalHttp && url.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))) {
            "HTTPS required"
        }
        val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        require(segments.takeLast(2) != listOf("chat", "completions") && segments.lastOrNull() !in setOf("models", "responses")) {
            "Enter API base, not request endpoint"
        }
        while (segments.size >= 2 && segments.takeLast(2) == listOf("v1", "v1")) segments.removeAt(segments.lastIndex)
        if (segments.isEmpty()) segments += "v1"
        return url.newBuilder().encodedPath("/").apply { segments.forEach { addPathSegment(it) } }.build().toString().trimEnd('/')
    }

    fun compatible(base: String, resource: String, allowLocalHttp: Boolean = false): HttpUrl =
        normalize(base, allowLocalHttp).toHttpUrl().newBuilder().apply {
            resource.split('/').forEach { addPathSegment(it) }
        }.build()
}

fun redactHeaders(headers: Map<String, String>): Map<String, String> = headers.mapValues { (name, value) ->
    if (name.lowercase() in setOf("authorization", "x-api-key", "x-goog-api-key", "api-key")) "[REDACTED]" else value
}
