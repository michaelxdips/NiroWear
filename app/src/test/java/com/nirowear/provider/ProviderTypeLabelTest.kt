package com.nirowear.provider

import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards the "Add Openai" typo: the provider heading must use brand casing. */
class ProviderTypeLabelTest {
    @Test fun labelsUseBrandCasingNotTitleCasing() {
        assertEquals("OpenAI", ProviderType.OPENAI.label)
        assertEquals("Gemini", ProviderType.GEMINI.label)
        assertEquals("Anthropic", ProviderType.ANTHROPIC.label)
        assertEquals("compatible", ProviderType.OPENAI_COMPATIBLE.label)
    }
}
