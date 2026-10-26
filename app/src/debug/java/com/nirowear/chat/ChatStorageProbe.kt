package com.nirowear.chat

/** Fault injection only; no content/credentials supplied. */
object ChatStorageProbe {
    var beforeFinish: (() -> Unit)? = null

    fun finishing() {
        beforeFinish?.invoke()
    }
}
