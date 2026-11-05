package com.nirowear.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nirowear.provider.ProviderError
import com.nirowear.provider.ProviderProfile
import com.nirowear.provider.ProviderType
import com.nirowear.provider.SafeProviderException
import com.nirowear.provider.SecretValue
import com.nirowear.security.CredentialPromotionProbe
import com.nirowear.security.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

private val Context.providerData by preferencesDataStore("providers")

data class ProviderSettingsState(
    val profiles: List<ProviderProfile> = emptyList(),
    val selectedId: String? = null,
    val error: String? = null,
) {
    val selected: ProviderProfile? get() = profiles.find { it.id == selectedId }
}

class ProviderSettings(
    context: Context,
    private val secrets: SecretStore,
) {
    private val store = context.providerData
    private val profilesKey = stringPreferencesKey("profiles_v1")
    private val selectedKey = stringPreferencesKey("selected_profile")
    private val mutex = Mutex()
    val state =
        store.data
            .map { prefs ->
                try {
                    ProviderSettingsState(decode(prefs[profilesKey] ?: "[]"), prefs[selectedKey])
                } catch (
                    _: Exception,
                ) {
                    ProviderSettingsState(
                        error = "Provider settings unreadable. History and saved keys kept; setup changes blocked.",
                    )
                }
            }.catch { error ->
                if (error is CancellationException) throw error
                emit(
                    ProviderSettingsState(
                        error = "Provider settings unavailable. History and saved keys kept; setup changes blocked.",
                    ),
                )
            }

    suspend fun recover() =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val current = state.first()
                // Same rule as save(): refs of entries this build cannot parse are still referenced,
                // so recover() must not delete their ciphertext either.
                if (current.error == null) {
                    secrets.cleanup(referencedCredentialRefs(store.data.first()[profilesKey] ?: "[]"))
                }
            }
        }

    suspend fun save(
        profile: ProviderProfile,
        newSecret: SecretValue?,
        validateReplacement: (
            suspend (ProviderProfile, SecretValue) -> ProviderError?
        )? = null,
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(profile.displayName.isNotBlank() && profile.displayName.length <= 100)
            require(profile.selectedModelId.isNotBlank() && profile.selectedModelId.length <= 256)
            val current = state.first()
            check(current.error == null) { "Provider settings unavailable" }
            val previous = current.profiles.find { it.id == profile.id }
            require(newSecret != null || previous != null) { "API key required" }
            require(
                previous == null || newSecret == null || validateReplacement != null,
            ) { "Replacement validation required" }
            // The elvis must stay INSIDE the null-secret branch. Hoisting it would throw whenever a
            // brand-new provider is added (newSecret != null, previous == null), which is a normal
            // flow, not an unreachable state.
            val ref =
                if (newSecret == null) {
                    val prev = previous ?: error("unreachable: newSecret == null requires previous != null")
                    prev.credentialRef
                } else {
                    UUID.randomUUID().toString()
                }
            if (newSecret != null) secrets.save(ref, newSecret)
            var promoted = false
            var cleaned = true
            try {
                if (newSecret != null) CredentialPromotionProbe.reached("CANDIDATE_WRITTEN")
                if (newSecret != null) {
                    val candidate = secrets.read(ref)
                    // Only a REPLACEMENT needs validation against the previous profile. Adding a
                    // brand-new provider has previous == null and legitimately passes no validator,
                    // so requiring one unconditionally crashes the normal add-provider path.
                    // The require() above only guarantees a validator when previous != null.
                    if (previous != null) {
                        val validate =
                            validateReplacement
                                ?: error("unreachable: require() guarantees a validator when previous != null")
                        validate(profile, candidate)?.let { throw SafeProviderException(it) }
                    }
                }
                if (newSecret != null) CredentialPromotionProbe.reached("CANDIDATE_VALIDATED")
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    store.edit { prefs ->
                        val profiles =
                            decode(prefs[profilesKey] ?: "[]").filterNot { it.id == profile.id } +
                                profile.copy(credentialRef = ref)
                        prefs[profilesKey] = encode(profiles)
                        // Selecting is a separate, explicit action (select()). Replacing a key on an
                        // existing profile must not retarget the next send: editing provider B while
                        // A is active used to silently make B the active provider and model, which is
                        // exactly the "never silently change a model" rule. Only a brand-new profile
                        // becomes selected.
                        if (previous == null) prefs[selectedKey] = profile.id
                    }
                    ; promoted = true
                }
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (promoted && newSecret != null) CredentialPromotionProbe.reached("REFERENCE_PROMOTED")
                        if (!promoted && newSecret != null) secrets.delete(ref)
                        // Referenced refs, INCLUDING entries this build cannot parse: cleanup deletes
                        // anything absent from this set, so a dropped entry must still count.
                        secrets
                            .cleanup(
                                referencedCredentialRefs(store.data.first()[profilesKey] ?: "[]"),
                            )
                        if (newSecret != null && promoted) CredentialPromotionProbe.reached("OLD_PAYLOAD_CLEANED")
                    } catch (error: Exception) {
                        if (!promoted) throw error
                        cleaned = false // Metadata committed; never report that previous credential survived.
                    }
                }
            }
            cleaned
        }
    }

    suspend fun select(id: String) =
        mutex.withLock {
            store.edit { prefs ->
                require(decode(prefs[profilesKey] ?: "[]").any { it.id == id })
                prefs[selectedKey] = id
            }
        }

    suspend fun delete(id: String) =
        mutex.withLock {
            withContext(Dispatchers.IO + NonCancellable) {
                val current = state.first()
                check(current.error == null) { "Provider settings unavailable" }
                val previous = current.profiles.find { it.id == id } ?: return@withContext
                store.edit { prefs ->
                    prefs[profilesKey] = encode(decode(prefs[profilesKey] ?: "[]").filterNot { it.id == id })
                    if (prefs[selectedKey] == id) prefs.remove(selectedKey)
                }
                secrets.cleanup(decode(store.data.first()[profilesKey] ?: "[]").map { it.credentialRef }.toSet())
            }
        }

    private fun encode(profiles: List<ProviderProfile>) =
        JsonArray(
            profiles.map { p ->
                buildJsonObject {
                    put("id", p.id)
                    put("type", p.type.name)
                    put("name", p.displayName)
                    put("base", p.baseUrl)
                    put("model", p.selectedModelId)
                    put("credentialRef", p.credentialRef)
                }
            },
        ).toString()

    /**
     * Per-element decoding.
     *
     * Previously any single bad element (an unknown `type` after an enum change, one missing field,
     * one malformed ref) threw for the whole array. The state then reported "settings unreadable"
     * with an EMPTY profile list, and every writer re-decodes that same poisoned value before it can
     * write, so nothing could repair it: the app was permanently unconfigurable while the ciphertext
     * for every profile still sat on disk with no reference to it. Dropping only the broken entries
     * keeps the rest usable and keeps their credential references alive.
     *
     * A parse failure of the whole document is still fatal — that is real corruption of the envelope,
     * not of one entry.
     */
    private fun decode(source: String): List<ProviderProfile> =
        Json.parseToJsonElement(source).jsonArray.mapNotNull { element ->
            runCatching { decodeOne(element) }.getOrNull()
        }

    private fun decodeOne(element: kotlinx.serialization.json.JsonElement): ProviderProfile {
        val p = element.jsonObject

        fun field(name: String): String {
            val value = p.getValue(name).jsonPrimitive
            require(value.isString) { "Invalid profile field" }
            return value.content
        }
        val ref = field("credentialRef")
        require(Regex("[a-f0-9-]{36}").matches(ref)) { "Invalid credential reference" }
        return ProviderProfile(
            field("id"),
            ProviderType.valueOf(field("type")),
            field("name"),
            field("base"),
            field("model"),
            ref,
        )
    }
}

/**
 * Every credential reference the stored document still mentions, including entries decode() dropped.
 *
 * This is what `SecretStore.cleanup` must be given. Cleanup DELETES ciphertext whose ref is absent, so
 * handing it only the successfully decoded refs turned one malformed entry into permanent key loss on
 * the next save of any provider — and there is no plaintext fallback. A ref that is mentioned in the
 * document is still referenced, whether or not this build can parse its profile.
 */
internal fun referencedCredentialRefs(source: String): Set<String> {
    val elements = Json.parseToJsonElement(source).jsonArray
    val refs = mutableSetOf<String>()
    for (element in elements) {
        // Best-effort on the ref alone: it is the only field cleanup cares about, and it stays valid
        // even when the rest of the entry cannot be decoded.
        runCatching {
            element.jsonObject
                .getValue("credentialRef")
                .jsonPrimitive.content
        }.getOrNull()?.takeIf { Regex("[a-f0-9-]{36}").matches(it) }?.let { refs += it }
    }
    return refs
}
