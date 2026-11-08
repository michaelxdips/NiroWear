package com.nirowear.settings

import android.os.SystemClock
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text
import com.nirowear.BuildConfig
import com.nirowear.NiroApplication
import com.nirowear.R
import com.nirowear.data.ProviderSettingsState
import com.nirowear.provider.DISCOVERY_BUDGET_MS
import com.nirowear.provider.Endpoints
import com.nirowear.provider.ModelDiscoveryResult
import com.nirowear.provider.ProviderError
import com.nirowear.provider.ProviderProfile
import com.nirowear.provider.ProviderType
import com.nirowear.provider.SafeProviderException
import com.nirowear.provider.SecretValue
import com.nirowear.provider.label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** The four add-provider presets. Only the literals differ, so the button logic is written once. */
private data class Preset(
    @param:StringRes val label: Int,
    val type: ProviderType,
    @param:StringRes val name: Int,
    val base: String,
    @param:StringRes val message: Int,
)

private val PRESETS =
    listOf(
        Preset(
            R.string.provider_add_compatible,
            ProviderType.OPENAI_COMPATIBLE,
            R.string.provider_name_custom,
            "",
            R.string.provider_preset_compatible_message,
        ),
        Preset(
            R.string.provider_add_openai,
            ProviderType.OPENAI,
            R.string.provider_name_openai,
            "https://api.openai.com/v1",
            R.string.provider_preset_openai_message,
        ),
        Preset(
            R.string.provider_add_gemini,
            ProviderType.GEMINI,
            R.string.provider_name_gemini,
            "https://generativelanguage.googleapis.com/v1beta",
            R.string.provider_preset_gemini_message,
        ),
        Preset(
            R.string.provider_add_anthropic,
            ProviderType.ANTHROPIC,
            R.string.provider_name_anthropic,
            "https://api.anthropic.com/v1",
            R.string.provider_preset_anthropic_message,
        ),
    )

@Composable
fun ProviderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Resolved ONCE here and read inside the coroutine callbacks. Lint's
    // LocalContextGetResourceValueCall exists because calling resources.getString(...) from a
    // composable re-reads configuration state on every recomposition; LocalResources is the
    // configuration-aware handle Compose provides for exactly this, and the renderer already
    // uses it the same way.
    val resources = androidx.compose.ui.platform.LocalResources.current
    val app = context.applicationContext as NiroApplication
    val state by app.settings.state.collectAsStateWithLifecycle(initialValue = ProviderSettingsState())
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf<ProviderProfile?>(null) }
    var adding by remember { mutableStateOf(false) }
    // Survive Activity recreation (rotation, theme change, a Watch-face handoff). These are the
    // non-secret fields of a half-filled form; losing them meant re-typing a base URL on a watch
    // keyboard. The API KEY below stays `remember` on purpose: a saved-instance bundle is plaintext
    // on disk, and the credential contract forbids repopulating it.
    var type by rememberSaveable { mutableStateOf(ProviderType.OPENAI_COMPATIBLE) }
    val customProviderName = stringResource(R.string.provider_name_custom)
    var name by rememberSaveable { mutableStateOf(customProviderName) }
    var base by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var key by remember { mutableStateOf("") } // Never rememberSaveable, persist or repopulate decrypted secret.
    var reveal by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    // Guards a double tap while the listener is being created off the UI thread: a second tap would
    // otherwise build a second server and bind a second port.
    var pairingStarting by remember { mutableStateOf(false) }
    var validated by remember { mutableStateOf(false) }
    // Pairing listener: exists only while this screen is composed. Never a service, never at boot.
    var pairing by remember { mutableStateOf<PairingServer?>(null) }
    var pairingNote by remember { mutableStateOf("") }
    // Remote input is what makes typing on a watch tolerable, so advertise it only when the enabled
    // IME list actually contains a provider that supports it.
    val remoteTyping = remember { remoteTypingHint(enabledImePackages(context)) }
    val listState = rememberLazyListState()
    LaunchedEffect(adding, edit?.id) { listState.scrollToItem(0) }
    LaunchedEffect(type, base, key, edit?.id) { models = emptyList() }
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            key = ""
            // Leaving the screen kills the listener even if the coroutine never resumes.
            pairing?.close()
            pairing = null
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    fun profile(): ProviderProfile =
        ProviderProfile(
            edit?.id ?: UUID.randomUUID().toString(),
            type,
            name.trim(),
            Endpoints.normalize(base, BuildConfig.DEBUG),
            model.trim(),
            edit?.credentialRef ?: UUID.randomUUID().toString(),
        )

    suspend fun secret(profile: ProviderProfile): SecretValue =
        if (key.isNotBlank()) {
            SecretValue(key.trim())
        } else {
            withContext(Dispatchers.IO) { app.secrets.read(profile.credentialRef) }
        }

    // The one save tail. Manual entry and LAN pairing both end here, so there is no second
    // persistence path and no way for an arriving key to skip the promotion probe. Manual entry
    // passes its field value; a paired key is handed over directly so it never has to exist in
    // the revealable editing state.
    suspend fun saveProfile(explicit: SecretValue? = null) {
        busy = true
        try {
            val supplied = explicit ?: key.takeIf { it.isNotBlank() }?.let { SecretValue(it.trim()) }
            val cleaned =
                app.settings.save(profile(), supplied) { p, candidate ->
                    // A missing adapter is a configuration error, not a null to assert away:
                    // report it as a validation failure the user can see.
                    val adapter = app.provider(p.type) ?: return@save ProviderError.PROTOCOL
                    adapter.validate(p, candidate)
                }
            key = ""
            edit = null
            adding = false
            models = emptyList()
            message =
                if (cleaned) {
                    resources.getString(R.string.provider_saved_ok)
                } else {
                    resources.getString(R.string.provider_saved_cleanup_pending)
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A rejected replacement key arrives as SafeProviderException from the promotion probe.
            // Naming the cause beats "Secure save failed" when the real problem is a 401.
            val specific = (failure as? SafeProviderException)?.error?.userMessage
            // "Previous credential kept." is only true when there WAS one. On a brand-new provider the
            // failure is a plain require() ("API key required", "Selected model ID required"), and
            // claiming a credential was kept both lied and hid the reason.
            val hadPrevious = edit != null
            message =
                when {
                    specific != null && hadPrevious -> {
                        resources.getString(R.string.provider_error_previous_kept, specific)
                    }

                    specific != null -> {
                        specific
                    }

                    failure is IllegalArgumentException || failure is IllegalStateException -> {
                        failure.message
                            ?.takeIf { it.isNotBlank() }
                            ?: resources.getString(R.string.provider_check_config)
                    }

                    hadPrevious -> {
                        resources.getString(R.string.provider_save_failed_prev_kept)
                    }

                    else -> {
                        resources.getString(R.string.provider_save_failed)
                    }
                }
        } finally {
            busy = false
        }
    }

    fun stopPairing(note: String = "") {
        // close() shuts the sockets synchronously, which is fast; the worker join it also starts is
        // on its own daemon thread, so this no longer freezes the dial while a peer sits in read().
        pairing?.close()
        pairing = null
        pairingNote = note
    }

    /**
     * Starts the pairing listener WITHOUT blocking the UI thread.
     *
     * The constructor does real work — SecureRandom, a P-256 KeyPairGenerator, a self-signed
     * certificate, and a ServerSocket bind — and `start()` spawns the accept loop. Running all of
     * that inside `onClick` stalled the provider screen on the exact tap that is supposed to respond
     * instantly. It runs on IO now and reports back on the main thread.
     */
    fun startPairing() {
        if (pairingStarting) return
        val host = lanAddress()
        if (host == null) {
            pairingNote = resources.getString(R.string.provider_no_wifi)
            return
        }
        pairingStarting = true
        pairingNote = resources.getString(R.string.provider_pairing_starting)
        scope.launch {
            val started =
                withContext(Dispatchers.IO) {
                    // The constructor itself does real work — SecureRandom, a P-256 KeyPairGenerator,
                    // and a ServerSocket bind. Only start() used to be guarded, so a Wi-Fi drop
                    // between lanAddress() and the bind threw BindException out of the Compose
                    // onClick and killed the process. Both are guarded now, and a failed start closes
                    // the already-bound socket instead of leaking the fd until process death.
                    runCatching {
                        PairingServer(host, clock = SystemClock::elapsedRealtime) { payload ->
                            scope.launch {
                                stopPairing(resources.getString(R.string.provider_key_received))
                                edit = null
                                adding = true
                                reveal = false
                                validated = false
                                models = emptyList()
                                type = payload.type
                                name = payload.name.ifBlank { payload.type.label }
                                base = payload.baseUrl
                                model = payload.modelId
                                // The key arrived encrypted and is handed straight to storage. Putting
                                // it in the editing field would make it revealable and
                                // screenshot-adjacent for no benefit; the field stays blank, exactly
                                // as it does after a manual save.
                                key = ""
                                saveProfile(SecretValue(sanitizeKey(payload.apiKey)))
                            }
                        }
                    }.mapCatching { created ->
                        created.start()
                        created
                    }.onFailure { created ->
                        // A half-started listener must not keep its fd until process death.
                        runCatching { (created as? PairingServer)?.close() }
                    }
                }
            pairingStarting = false
            started
                .onSuccess { created ->
                    pairing = created
                    pairingNote =
                        if (isPrivateAddress(host)) {
                            resources.getString(R.string.provider_pairing_warning)
                        } else {
                            resources.getString(R.string.provider_not_home_network)
                        }
                }.onFailure { pairingNote = resources.getString(R.string.provider_pairing_unavailable) }
        }
    }

    LazyColumn(
        // imePadding: targetSdk 37 means edge-to-edge on Android 15+, where adjustResize no longer
        // shrinks the window. Without this the keyboard sat over the API-key field and Save on the
        // 466 px dial — the one screen where typing is the whole task.
        Modifier.fillMaxSize().imePadding().background(Color.Black),
        state = listState,
        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            val title =
                if (adding || edit != null) {
                    stringResource(
                        if (edit != null) R.string.provider_title_edit else R.string.provider_title_add,
                        type.label,
                    )
                } else {
                    stringResource(R.string.provider_title)
                }
            Text(
                title,
                modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally),
                fontSize = 18.sp,
            )
        }
        item {
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(R.string.provider_back))
            }
        }
        val stateError = state.error
        if (stateError != null) {
            item { Text(stateError, fontSize = 14.sp) }
        } else if (!adding && edit == null) {
            // Save lands here (adding=false). The confirmation must survive the mode flip:
            // keep the same visible result row in list mode so "Saved securely." stays on screen.
            if (message.isNotBlank()) {
                item { Text(message, fontSize = 14.sp, color = Color(0xFFFFD28A)) }
            }
            // One preset block, four rows. They were four near-identical copies of six state
            // assignments each; the only fields that differ are the four literals below.
            items(PRESETS) { preset ->
                val presetLabel = stringResource(preset.label)
                val presetName = stringResource(preset.name)
                val presetMessage = stringResource(preset.message)
                Button(onClick = {
                    adding = true
                    type = preset.type
                    name = presetName
                    base = preset.base
                    model = ""
                    key = ""
                    validated = false
                    reveal = false
                    models = emptyList()
                    message = presetMessage
                }) { Text(presetLabel) }
            }
            items(state.profiles) { p ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            try {
                                app.settings.select(p.id)
                            } catch (
                                cancelled: CancellationException,
                            ) {
                                throw cancelled
                            } catch (_: Exception) {
                                message = resources.getString(R.string.provider_select_failed)
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(
                                if (state.selectedId == p.id) {
                                    R.string.provider_row_selected
                                } else {
                                    R.string.provider_row
                                },
                                p.displayName,
                                p.selectedModelId,
                            ),
                            fontSize = 14.sp,
                        )
                    }
                    OutlinedButton(onClick = {
                        edit = p
                        type = p.type
                        name = p.displayName
                        base = p.baseUrl
                        model =
                            p.selectedModelId
                        key = ""
                        validated = false
                        reveal = false
                        models = emptyList()
                        message =
                            resources.getString(R.string.provider_key_configured)
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.provider_edit_replace_key)) }
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (pairing == null) startPairing() else stopPairing() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (pairing == null) {
                                    R.string.provider_setup_from_phone
                                } else {
                                    R.string.provider_cancel_pairing
                                },
                            ),
                            fontSize = 14.sp,
                        )
                    }
                    pairing?.let { server -> Text(server.url, fontSize = 14.sp, color = Color(0xFFA8C7FA)) }
                    if (pairingNote.isNotBlank()) Text(pairingNote, fontSize = 13.sp, color = Color(0xFFB8BDC6))
                }
            }
            item {
                Entry(stringResource(R.string.provider_label_name), name) {
                    name = it
                    validated = false
                }
            }
            item {
                Entry(stringResource(R.string.provider_label_base_url), base, keyboardType = KeyboardType.Uri) {
                    base = it
                    validated = false
                }
            }
            item {
                Entry(stringResource(R.string.provider_label_manual_model), model) {
                    model = it
                    validated = false
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // ponytail: recreate password semantics; remove when Compose fixes dynamic isPassword updates.
                    key(reveal) {
                        Entry(
                            stringResource(R.string.provider_label_api_key),
                            key,
                            keyboardType = KeyboardType.Password,
                            visualTransformation =
                                if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                        ) {
                            // Trim at the boundary: a trailing newline from a clipboard bridge otherwise
                            // surfaces as an invisible character that only fails at save time.
                            key = sanitizeKey(it)
                            validated = false
                        }
                    }
                    if (edit !=
                        null
                    ) {
                        Text(
                            stringResource(R.string.provider_configured_replace),
                            fontSize = 14.sp,
                            color = Color(0xFFB8BDC6),
                        )
                    }
                    keyMismatchHint(key, type)?.let { Text(it, fontSize = 14.sp, color = Color(0xFFFFD28A)) }
                    if (key.isBlank() &&
                        remoteTyping != null
                    ) {
                        Text(remoteTyping, fontSize = 14.sp, color = Color(0xFFB8BDC6))
                    }
                    OutlinedButton(onClick = {
                        reveal = !reveal
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(
                                if (reveal) R.string.provider_hide_key else R.string.provider_reveal_key,
                            ),
                        )
                    }
                }
            }
            item {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        message = ""
                        // Profiles are frozen at request time, so only a mode change can retarget
                        // this request; that is exactly what the staleness check compares.
                        val input = Triple(type, base, key)
                        val inputProfileId = edit?.id
                        try {
                            val p = profile()
                            val adapter = app.provider(p.type)
                            if (adapter == null) {
                                message = resources.getString(R.string.provider_type_unavailable)
                                return@launch
                            }
                            // A whole-operation budget, not just the client's per-socket limits:
                            // discovery pages, so a slow endpoint could keep this screen in
                            // "Discovering…" for minutes. On expiry the user is told to type the
                            // model, which is the same advice a failed discovery already gives.
                            val result =
                                withTimeoutOrNull(DISCOVERY_BUDGET_MS) {
                                    adapter.listModels(p, secret(p))
                                } ?: ModelDiscoveryResult.Unavailable(ProviderError.TIMEOUT)
                            if (input != Triple(type, base, key) || inputProfileId != edit?.id) return@launch
                            when (result) {
                                is ModelDiscoveryResult.Models -> {
                                    models = result.ids
                                    message =
                                        resources.getString(R.string.provider_models_listed)
                                }

                                is ModelDiscoveryResult.Unavailable -> {
                                    models = emptyList()
                                    message =
                                        resources.getString(R.string.provider_models_unavailable)
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            if (input == Triple(type, base, key) && inputProfileId == edit?.id) {
                                message = resources.getString(R.string.provider_check_url_key)
                            }
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.provider_discover_models)) }
            }
            items(models) { id ->
                Button(onClick = {
                    model = id
                    validated = false
                    models = emptyList()
                }, modifier = Modifier.fillMaxWidth()) { Text(id, fontSize = 14.sp) }
            }
            item {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        validated = false
                        message = ""
                        try {
                            val p = profile()
                            // Snapshot what is actually being validated. The key field is NOT disabled
                            // while busy, so it can change during the request — and this is a real
                            // (billable) generation, so the result belongs to the snapshot, not to
                            // whatever the fields hold when it returns.
                            val inputKey = key
                            val inputProfileId = edit?.id
                            val adapter = app.provider(p.type)
                            if (adapter == null) {
                                message = resources.getString(R.string.provider_type_unavailable)
                                return@launch
                            }
                            val error = adapter.validate(p, secret(p))
                            // Any field changed under the request invalidates its result. Without this
                            // an edited key still got "Connected. Selected model generated
                            // successfully." for the OLD key, and a brand-new profile is saved
                            // without a replacement probe, so the never-validated key was persisted
                            // under a success message and then failed with 401.
                            if (p.type != type || p.displayName != name.trim() ||
                                p.baseUrl != Endpoints.normalize(base, BuildConfig.DEBUG) ||
                                p.selectedModelId != model.trim() ||
                                key != inputKey ||
                                inputProfileId != edit?.id
                            ) {
                                message = resources.getString(R.string.provider_inputs_changed)
                                return@launch
                            }
                            validated = error == null
                            message = error?.userMessage ?: resources.getString(R.string.provider_connected)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            // Name the failing field: ProviderError already separates a rejected key from a
                            // missing model, and collapsing both into one string made the fix a guess.
                            message = validationMessage(failure)
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.provider_validate)) }
            }
            // One shared result row, right after the action buttons. It used to sit at the list
            // tail below Save/Delete, so Validate/Discover output was invisible without a long
            // scroll and taps looked dead on the watch during live testing.
            item {
                if (message.isNotBlank()) Text(message, fontSize = 14.sp, color = Color(0xFFFFD28A))
            }
            item {
                // Validate is ADVISORY, not a gate on Save.
                //
                // The gate read `!needsValidation || validated`, i.e. Save stayed disabled until a live
                // call succeeded — while this very screen tells the user "Models unavailable. Enter model
                // manually; chat still allowed". A BYOK client that cannot save a profile because the
                // watch is offline, behind a captive portal, or the provider is briefly down is broken;
                // it also made the four setup tests unable to reach Save at all (measured on the AVD:
                // enabled=false with every field filled). What is required now is the data a profile
                // needs; an unvalidated save is announced, and a wrong key surfaces at the first send as
                // an error row on the conversation, which is visible and recoverable.
                val original = edit
                val connectivityChanged =
                    original == null ||
                        type != original.type ||
                        base.trim() != original.baseUrl ||
                        model.trim() != original.selectedModelId
                val needsValidation = key.isNotBlank() || connectivityChanged
                val ready =
                    name.isNotBlank() && base.isNotBlank() && model.isNotBlank() &&
                        (key.isNotBlank() || original != null)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && ready,
                    onClick = {
                        scope.launch {
                            stopPairing()
                            saveProfile()
                        }
                    },
                ) { Text(stringResource(R.string.provider_save)) }
                if (needsValidation && !validated && ready) {
                    Text(
                        stringResource(R.string.provider_not_validated),
                        fontSize = 13.sp,
                        color = Color(0xFFB8BDC6),
                    )
                }
            }
            val deleting = edit
            if (deleting != null) {
                item {
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                        scope.launch {
                            try {
                                app.settings.delete(deleting.id)
                                edit = null
                                key = ""
                                message =
                                    resources.getString(R.string.provider_deleted)
                            } catch (
                                cancelled: CancellationException,
                            ) {
                                throw cancelled
                            } catch (_: Exception) {
                                message = resources.getString(R.string.provider_delete_failed)
                            }
                        }
                    }) { Text(stringResource(R.string.provider_delete)) }
                }
            }
        }
        if (busy) item { Text(stringResource(R.string.provider_working), fontSize = 14.sp) }
    }
}

@Composable
private fun Entry(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onChange: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Decorative. The field below carries the same label as its contentDescription, so leaving
        // this Text in the accessibility tree made TalkBack read every entry's name twice: once for
        // the label node, once for the field it labels.
        Text(
            label,
            fontSize = 14.sp,
            color = Color(0xFFB8BDC6),
            modifier = Modifier.clearAndSetSemantics {},
        )
        BasicTextField(
            value,
            onChange,
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .onFocusChanged { focused = it.isFocused }
                .background(Color(0xFF202124), RoundedCornerShape(8.dp))
                .border(1.dp, if (focused) Color(0xFFA8C7FA) else Color(0xFF41454D), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 12.dp)
                .semantics { contentDescription = label },
            singleLine = true,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(Color.White),
        )
    }
}
