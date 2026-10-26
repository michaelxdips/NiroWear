package com.nirowear.settings

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
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
import com.nirowear.data.ProviderSettingsState
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
import java.util.UUID

@Composable
fun ProviderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as NiroApplication
    val state by app.settings.state.collectAsStateWithLifecycle(initialValue = ProviderSettingsState())
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf<ProviderProfile?>(null) }
    var adding by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(ProviderType.OPENAI_COMPATIBLE) }
    var name by remember { mutableStateOf("Custom provider") }
    var base by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") } // Never rememberSaveable, persist or repopulate decrypted secret.
    var reveal by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var validated by remember { mutableStateOf(false) }
    // Pairing listener: exists only while this screen is composed. Never a service, never at boot.
    var pairing by remember { mutableStateOf<PairingServer?>(null) }
    var pairingNote by remember { mutableStateOf("") }
    var paired by remember { mutableStateOf<PairingPayload?>(null) }
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
    // persistence path and no way for an arriving key to skip the promotion probe.
    // One save tail for both entry paths. Manual entry passes its field value; a paired key is
    // handed over directly so it never has to exist in the revealable editing state.
    suspend fun saveProfile(explicit: SecretValue? = null) {
        busy = true
        try {
            val supplied = explicit ?: key.takeIf { it.isNotBlank() }?.let { SecretValue(it.trim()) }
            val cleaned =
                app.settings.save(profile(), supplied) { p, candidate ->
                    // A missing adapter is a configuration error, not a null to assert away:
                    // report it as a validation failure the user can see.
                    val adapter = app.provider(p.type)
                        ?: return@save ProviderError.PROTOCOL
                    adapter.validate(p, candidate)
                }
            key = ""
            edit = null
            adding = false
            models = emptyList()
            message =
                if (cleaned) {
                    "Saved securely."
                } else {
                    "Saved securely. Old encrypted-key cleanup pending; reopen app to retry cleanup."
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A rejected replacement key arrives as SafeProviderException from the promotion probe.
            // Naming the cause beats "Secure save failed" when the real problem is a 401.
            message = (failure as? SafeProviderException)
                ?.error
                ?.userMessage
                ?.let { "$it Previous credential kept." }
                ?: "Secure save failed. Previous credential kept. Re-enter key."
        } finally {
            busy = false
        }
    }

    fun stopPairing(note: String = "") {
        pairing?.close()
        pairing = null
        pairingNote = note
    }

    fun startPairing() {
        val host = lanAddress()
        if (host == null) {
            pairingNote = "No Wi-Fi address. Turn on Wi-Fi, or enter the key below."
            return
        }
        val server =
            PairingServer(host) { payload ->
                scope.launch {
                    stopPairing("Key received.")
                    edit = null
                    adding = true
                    reveal = false
                    validated = false
                    models = emptyList()
                    type = payload.type
                    name = payload.name.ifBlank { payload.type.label }
                    base = payload.baseUrl
                    model = payload.modelId
                    // The key arrived encrypted and is handed straight to storage. Putting it in the
                    // editing field would make it revealable and screenshot-adjacent for no benefit;
                    // the field stays blank, exactly as it does after a manual save.
                    key = ""
                    saveProfile(SecretValue(sanitizeKey(payload.apiKey)))
                }
            }
        runCatching { server.start() }
            .onSuccess {
                pairing = server
                pairingNote = if (isPrivateAddress(host)) "" else "Not a home network. Cancel if unsure."
            }.onFailure { pairingNote = "Pairing unavailable. Enter the key below." }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Color.Black),
        state = listState,
        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                if (adding || edit != null) "${if (edit != null) "Edit" else "Add"} ${type.label}" else "Providers",
                modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally),
                fontSize = 18.sp,
            )
        }
        item {
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally),
            ) {
                Text("Back")
            }
        }
        val stateError = state.error
        if (stateError != null) {
            item { Text(stateError, fontSize = 14.sp) }
        } else if (!adding && edit == null) {
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
                                message = "Selection failed. Try again."
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${if (state.selectedId == p.id) "Selected · " else ""}${p.displayName}\n${p.selectedModelId}",
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
                            "API key configured. Enter replacement only."
                    }, modifier = Modifier.fillMaxWidth()) { Text("Edit / replace key") }
                }
            }
            item {
                Button(onClick = {
                    adding = true
                    type = ProviderType.OPENAI_COMPATIBLE
                    name = "Custom provider"
                    base =
                        ""
                    model = ""
                    key = ""
                    validated = false
                    reveal = false
                    message =
                        "HTTPS API base. Root defaults to /v1."
                }) { Text("Add compatible") }
            }
            item {
                Button(onClick = {
                    adding = true
                    type = ProviderType.OPENAI
                    name = "OpenAI"
                    base =
                        "https://api.openai.com/v1"
                    model = ""
                    key = ""
                    validated = false
                    reveal = false
                    models =
                        emptyList()
                    message = "Native Responses. Discover models; selected model needs text validation."
                }) { Text("Add OpenAI") }
            }
            item {
                Button(onClick = {
                    adding = true
                    type = ProviderType.GEMINI
                    name = "Gemini"
                    base =
                        "https://generativelanguage.googleapis.com/v1beta"
                    model = ""
                    key = ""
                    validated = false
                    reveal =
                        false
                    models = emptyList()
                    message =
                        "Native generateContent. Discovery filters generation capability."
                }) { Text("Add Gemini") }
            }
            item {
                Button(onClick = {
                    adding = true
                    type = ProviderType.ANTHROPIC
                    name = "Anthropic"
                    base =
                        "https://api.anthropic.com/v1"
                    model = ""
                    key = ""
                    validated = false
                    reveal = false
                    models =
                        emptyList()
                    message = "Native Messages. Use single-workspace API key."
                }) { Text("Add Anthropic") }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (pairing == null) startPairing() else stopPairing() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (pairing == null) "Set up from phone" else "Cancel pairing", fontSize = 14.sp)
                    }
                    pairing?.let { server ->
                        Text(server.url, fontSize = 14.sp, color = Color(0xFFA8C7FA))
                        Text(
                            "Open that on your phone. Expires in 2 minutes.",
                            fontSize = 13.sp,
                            color = Color(0xFFB8BDC6),
                        )
                    }
                    if (pairingNote.isNotBlank()) Text(pairingNote, fontSize = 13.sp, color = Color(0xFFB8BDC6))
                }
            }
            item {
                Entry("Name", name) {
                    name = it
                    validated = false
                }
            }
            item {
                Entry("Base URL", base, keyboardType = KeyboardType.Uri) {
                    base = it
                    validated = false
                }
            }
            item {
                Entry("Manual model ID", model) {
                    model = it
                    validated = false
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // ponytail: recreate password semantics; remove when Compose fixes dynamic isPassword updates.
                    key(reveal) {
                        Entry(
                            "API key",
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
                        Text("Configured · enter replacement only", fontSize = 14.sp, color = Color(0xFFB8BDC6))
                    }
                    keyMismatchHint(key, type)?.let { Text(it, fontSize = 14.sp, color = Color(0xFFFFD28A)) }
                    if (key.isBlank() &&
                        remoteTyping != null
                    ) {
                        Text(remoteTyping, fontSize = 14.sp, color = Color(0xFFB8BDC6))
                    }
                    OutlinedButton(onClick = {
                        reveal = !reveal
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (reveal) "Hide key" else "Reveal key") }
                }
            }
            item {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        val input = Triple(type, base, key)
                        val inputProfileId = edit?.id
                        try {
                            val p = profile()
                            val adapter = app.provider(p.type)
                            if (adapter == null) {
                                message = "This provider type is not available in this build."
                                return@launch
                            }
                            val result = adapter.listModels(p, secret(p))
                            if (input != Triple(type, base, key) || inputProfileId != edit?.id) return@launch
                            when (result) {
                                is ModelDiscoveryResult.Models -> {
                                    models = result.ids
                                    message =
                                        "Models listed. Choose or enter manually."
                                }

                                is ModelDiscoveryResult.Unavailable -> {
                                    models = emptyList()
                                    message =
                                        "Models unavailable. Enter model manually; chat still allowed."
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            if (input == Triple(type, base, key) && inputProfileId == edit?.id) {
                                message = "Check URL/key. Manual model still allowed."
                            }
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Discover models") }
            }
            items(models) { id ->
                Button(onClick = {
                    model = id
                    validated = false
                }, modifier = Modifier.fillMaxWidth()) { Text(id, fontSize = 14.sp) }
            }
            item {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        validated = false
                        try {
                            val p = profile()
                            val inputKey = key
                            val inputProfileId = edit?.id
                            val adapter = app.provider(p.type)
                            if (adapter == null) {
                                message = "This provider type is not available in this build."
                                return@launch
                            }
                            val error = adapter.validate(p, secret(p))
                            if (p.type != type || p.displayName != name.trim() ||
                                p.baseUrl != Endpoints.normalize(base, BuildConfig.DEBUG) ||
                                p.selectedModelId != model.trim() ||
                                key != inputKey ||
                                inputProfileId != edit?.id
                            ) {
                                message = "Inputs changed. Validate current settings again."
                                return@launch
                            }
                            validated = error == null
                            message = error?.userMessage ?: "Connected. Selected model generated successfully."
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
                }) { Text("Validate · uses API") }
            }
            item {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled =
                        !busy && (edit == null || key.isBlank() || validated),
                    onClick = {
                        scope.launch {
                            stopPairing()
                            saveProfile()
                        }
                    },
                ) { Text("Save securely") }
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
                                    "Provider deleted. History kept."
                            } catch (
                                cancelled: CancellationException,
                            ) {
                                throw cancelled
                            } catch (_: Exception) {
                                message = "Delete failed. Try again."
                            }
                        }
                    }) { Text("Delete provider") }
                }
            }
        }
        if (message.isNotBlank()) item { Text(message, fontSize = 14.sp) }
        if (busy) item { Text("Working…", fontSize = 14.sp) }
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
        Text(label, fontSize = 14.sp, color = Color(0xFFB8BDC6))
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
