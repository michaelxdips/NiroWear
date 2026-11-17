package com.nirowear.data

import com.nirowear.provider.ProviderProfile
import com.nirowear.provider.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Deleting a provider must not leave the app silently without an active one, and a selection this
 * build cannot read must not leave it with no active provider at all.
 *
 * The delete path used to drop `selected_profile` when it pointed at the deleted profile, with no
 * fallback and no message: the list simply stopped showing a "Selected" row and the next send reported
 * "Add or select provider from menu.".
 */
class ProviderSelectionTest {
    private fun profile(id: String) =
        ProviderProfile(
            id = id,
            type = ProviderType.OPENAI,
            displayName = id,
            baseUrl = "https://example.com/v1",
            selectedModelId = "m",
            credentialRef = "11111111-2222-3333-4444-555555555555",
        )

    @Test
    fun deletingTheActiveProviderSelectsTheFirstSurvivor() {
        val remaining = listOf(profile("b"), profile("c"))
        assertEquals("b", nextSelection(remaining, current = "a", deletedId = "a"))
    }

    @Test
    fun deletingAnotherProviderKeepsTheActiveOne() {
        val remaining = listOf(profile("a"), profile("c"))
        assertEquals("a", nextSelection(remaining, current = "a", deletedId = "b"))
    }

    @Test
    fun deletingTheLastProviderClearsTheSelection() {
        assertNull(nextSelection(emptyList(), current = "a", deletedId = "a"))
    }

    @Test
    fun staleSelectionFallsBackRatherThanPointingAtNothing() {
        // A selection pointing at a profile that is no longer in the list is equally broken.
        val remaining = listOf(profile("b"))
        assertEquals("b", nextSelection(remaining, current = "gone", deletedId = "other"))
    }

    @Test
    fun aSelectionThisBuildCannotReadFallsBackToAReadableProfile() {
        // The defect this pins: decode() drops an unparsable entry but the stored selection was passed
        // through untouched, so `selected` was null with `error` null — "Add provider" while three were
        // listed, and every send rejected.
        val profiles = listOf(profile("b"), profile("c"))
        assertEquals("b", readableSelection(profiles, selectedId = "unreadable"))
        assertEquals("b", readableSelection(profiles, selectedId = null))
    }

    @Test
    fun aReadableSelectionIsKept() {
        val profiles = listOf(profile("b"), profile("c"))
        assertEquals("c", readableSelection(profiles, selectedId = "c"))
    }

    @Test
    fun noProfilesMeansNoSelection() {
        assertNull(readableSelection(emptyList(), selectedId = "x"))
    }

    @Test
    fun deletingAnUnrelatedProviderLeavesASelectionThisBuildCannotDecode() {
        // The stored document can name a profile this build cannot decode (a newer provider type, a
        // missing field). Comparing the stored selection only against the DECODED list treated it as
        // gone and overwrote it with the first survivor, so deleting an unrelated provider destroyed
        // the user's real choice — the opposite of what readableSelection promises.
        val document =
            """[{"id":"future","type":"NEW_TYPE","name":"F","base":"https://x/v1",""" +
                """"model":"m","credentialRef":"11111111-2222-3333-4444-555555555555"}]"""
        assertEquals(setOf("future"), storedIds(document))
        assertEquals("future", nextSelection(listOf(profile("a")), "future", "a", storedIds(document)))
        // Deleting the profile that IS selected still falls back, readable or not.
        assertEquals("a", nextSelection(listOf(profile("a")), "future", "future", storedIds(document)))
    }
}