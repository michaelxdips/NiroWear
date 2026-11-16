package com.nirowear.renderer

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class LinkDispatchTest {
    @Test fun onlyValidHttpLinksDispatchAndMissingOrBlockedHandlersFailSafely() {
        var sent: Intent? = null
        var failure: RuntimeException? = null
        val context =
            object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
                override fun startActivity(intent: Intent) {
                    failure?.let { throw it }
                    sent = intent
                }
            }
        for (url in listOf("https://example.com/path?q=1", "http://example.com", "HTTPS://example.com")) {
            assertNull(openHttpLink(context, url))
            assertEquals(Intent.ACTION_VIEW, sent!!.action)
            assertEquals(url, sent.data.toString())
        }
        for (url in listOf(
            "javascript:alert(1)",
            "file:///private",
            "https:missing-host",
            "https://secret@example.com",
        )) {
            sent = null
            assertNotNull(openHttpLink(context, url))
            assertNull(sent)
        }
        failure = ActivityNotFoundException()
        assertEquals(context.getString(com.nirowear.R.string.renderer_link_no_app), openHttpLink(context, "https://example.com"))
        failure = SecurityException()
        assertEquals(context.getString(com.nirowear.R.string.renderer_link_cannot_open), openHttpLink(context, "https://example.com"))
    }
}
