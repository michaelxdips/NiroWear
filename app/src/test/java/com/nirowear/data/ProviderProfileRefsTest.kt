package com.nirowear.data

import com.nirowear.provider.ProviderProfile
import com.nirowear.provider.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A malformed profile entry must never cost the user a credential.
 *
 * `SecretStore.cleanup` DELETES any ciphertext whose ref is absent from the set it is given, and there
 * is no plaintext fallback. decode() drops unparsable entries on purpose (so one bad element cannot
 * lock the app out of its own settings), which means two things must hold:
 *
 * 1. the cleanup set is derived from the RAW document, and
 * 2. rewriting the document CARRIES the raw element forward. `encode(decode(x))` erased it, so the ref
 *    was gone from the document before cleanup looked, and the key was deleted anyway.
 *
 * Both are pure functions over JSON, so they are tested without a Context.
 */
class ProviderProfileRefsTest {
    private val refA = "11111111-2222-3333-4444-555555555555"
    private val refB = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    private fun valid(
        ref: String,
        id: String = "a",
    ) = """{"id":"$id","type":"OPENAI","name":"n","base":"https://example.com/v1",""" +
        """"model":"m","credentialRef":"$ref"}"""

    private fun profile(
        ref: String,
        id: String = "a",
    ) = ProviderProfile(
        id = id,
        type = ProviderType.OPENAI,
        displayName = "n",
        baseUrl = "https://example.com/v1",
        selectedModelId = "m",
        credentialRef = ref,
    )

    private fun settings(): ProviderSettings {
        val unsafe =
            sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
                as sun.misc.Unsafe
        return unsafe.allocateInstance(ProviderSettings::class.java) as ProviderSettings
    }

    @Test
    fun droppedEntryStillCountsAsReferenced() {
        // Second entry has a ref but no type/name/base/model, so decode() discards it.
        val source = "[${valid(refA)},{\"id\":\"b\",\"credentialRef\":\"$refB\"}]"
        val refs = referencedCredentialRefs(source)
        assertTrue("the valid entry's ref must be referenced: $refs", refA in refs)
        assertTrue("DROPPED entry's ref must still be referenced, or its key is deleted: $refs", refB in refs)
    }

    @Test
    fun unknownProviderTypeDoesNotLoseTheKey() {
        val source =
            """[{"id":"a","type":"RETIRED_PROVIDER","name":"n","base":"https://e.com",""" +
                """"model":"m","credentialRef":"$refA"}]"""
        assertTrue(refA in referencedCredentialRefs(source))
    }

    @Test
    fun malformedRefIsNotTreatedAsReferenced() {
        // A ref that cannot be a file name is not one; counting it would only mask orphans.
        val source = """[{"id":"a","credentialRef":"../../etc/passwd"}]"""
        assertEquals(emptySet<String>(), referencedCredentialRefs(source))
    }

    @Test
    fun emptyDocumentReferencesNothing() {
        assertEquals(emptySet<String>(), referencedCredentialRefs("[]"))
    }

    @Test
    fun rewritingKeepsTheUndecodableEntry() {
        // THE regression: `encode(decode(x))` deleted every entry decode rejected, so by the time
        // cleanup ran the ref was gone from the document and the key was still deleted.
        val previous = "[${valid(refA)},{\"id\":\"b\",\"credentialRef\":\"$refB\"}]"
        val written = settings().encodePreserving(listOf(profile(refA)), previous)
        assertTrue("the parseable profile is written: $written", refA in written)
        assertTrue("the UNPARSEABLE entry must survive the rewrite: $written", refB in written)
        assertTrue("and its ref must still count as referenced", refB in referencedCredentialRefs(written))
    }

    @Test
    fun rewritingDoesNotResurrectADeletedProfile() {
        // Preservation is only for entries that CANNOT be decoded. A removed decodable profile stays
        // removed, so deleting a provider still deletes its key.
        val previous = "[${valid(refA)},${valid(refB, id = "b")}]"
        val written = settings().encodePreserving(emptyList(), previous)
        assertTrue("a decodable entry is not carried forward: $written", refA !in written)
        assertTrue(refB !in written)
        assertEquals("so neither ref is referenced", emptySet<String>(), referencedCredentialRefs(written))
    }
}