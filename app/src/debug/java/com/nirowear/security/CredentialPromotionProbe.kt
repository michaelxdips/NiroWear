package com.nirowear.security

/** Instrumentation-only observer. Never receives secrets; absent from release behavior. */
object CredentialPromotionProbe {
    var observer: ((String) -> Unit)? = null

    fun reached(state: String) {
        observer?.invoke(state)
    }
}
