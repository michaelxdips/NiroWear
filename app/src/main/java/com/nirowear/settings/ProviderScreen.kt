package com.nirowear.settings

import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import com.nirowear.BindSimpleRotaryScroll
import com.nirowear.BuildConfig
import com.nirowear.NiroApplication
import com.nirowear.R
import com.nirowear.chat.errorText
import com.nirowear.data.ProviderSettingsState
import com.nirowear.provider.DISCOVERY_BUDGET_MS
import com.nirowear.provider.Endpoints
import com.nirowear.provider.ModelDiscoveryResult
import com.nirowear.provider.ProviderError
import com.nirowear.provider.ProviderProfile
import com.nirowear.provider.ProviderType
import com.nirowear.provider.SafeProviderException
import com.nirowear.provider.SecretValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
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

/** Localized provider type name: the brand name stays, the compatible adapter's name is translated. */
private fun ProviderType.nameRes(): Int =
    when (this) {
        ProviderType.OPENAI_COMPATIBLE -> R.string.provider_type_compatible
        ProviderType.OPENAI -> R.string.provider_type_openai
        ProviderType.GEMINI -> R.string.provider_type_gemini
        ProviderType.ANTHROPIC -> R.string.provider_type_anthropic
    }

private fun validationErrorText(
    resources: android.content.res.Resources,
    failure: Exception,
): String? =
    when (failure.message) {
        "Enter valid HTTPS API base URL",
        "Base URL cannot contain credentials, query or fragment",
        -> resources.getString(R.string.provider_error_invalid_url)

        "HTTPS required" -> resources.getString(R.string.provider_error_https_required)

        "Enter API base, not request endpoint" -> resources.getString(R.string.provider_error_base_not_endpoint)

        "API key required" -> resources.getString(R.string.provider_error_key_required)

        "Invalid credential format" -> resources.getString(R.string.provider_error_key_format)

        "Display name required" -> resources.getString(R.string.provider_error_name_required)

        "Selected model ID required" -> resources.getString(R.string.provider_error_model_required)

        else -> null
    }

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
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val edit = editId?.let { id -> state.profiles.find { it.id == id } }
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
    var confirmDelete by remember(edit?.id) { mutableStateOf(false) }
    // Guards a double tap while the listener is being created off the UI thread: a second tap would
    // otherwise build a second server and bind a second port.
    var pairingStarting by remember { mutableStateOf(false) }
    var pairingJob by remember { mutableStateOf<Job?>(null) }
    var validated by remember { mutableStateOf(false) }
    // Pairing listener: exists only while this screen is composed. Never a service, never at boot.
    var pairing by remember { mutableStateOf<PairingServer?>(null) }
    var pairingFetchScope by remember { mutableStateOf<CoroutineScope?>(null) }
    var pairingNote by remember { mutableStateOf("") }
    var remainingSecs by remember(pairing) { mutableIntStateOf(pairing?.remainingSeconds() ?: 0) }
    // Remote input is what makes typing on a watch tolerable, so advertise it only when the enabled
    // IME list actually contains a provider that supports it.
    val remoteTyping = remember { remoteTypingAvailable(enabledImePackages(context)) }
    val listState = rememberLazyListState()
    BindSimpleRotaryScroll(listState)
    LaunchedEffect(adding, edit?.id, pairing != null) { listState.scrollToItem(0) }
    LaunchedEffect(type, base, key, edit?.id) { models = emptyList() }
    LaunchedEffect(pairing) {
        val server = pairing ?: return@LaunchedEffect
        while (isActive) {
            remainingSecs = server.remainingSeconds()
            if (server.isExpired()) {
                pairingFetchScope?.cancel()
                pairingFetchScope = null
                pairing?.close()
                pairing = null
                pairingNote = resources.getString(R.string.provider_pairing_expired)
                break
            }
            delay(500L)
        }
    }
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            key = ""
            // Leaving the screen kills the listener and any in-flight model fetch immediately.
            pairingJob?.cancel()
            pairingJob = null
            pairingFetchScope?.cancel()
            pairingFetchScope = null
            pairing?.close()
            pairing = null
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    val keepScreenOn = pairing != null
    DisposableEffect(keepScreenOn) {
        val window = (context as? android.app.Activity)?.window
        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (keepScreenOn) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
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
            editId = null
            adding = false
            models = emptyList()
            confirmDelete = false
            pairingNote = ""
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
            val specific =
                (failure as? SafeProviderException)
                    ?.error
                    ?.let { errorText(resources, it.code(hasText = false)) }
            val hadPrevious = edit != null
            val localizedValidation = validationErrorText(resources, failure)
            message =
                when {
                    specific != null && hadPrevious -> {
                        resources.getString(R.string.provider_error_previous_kept, specific)
                    }

                    specific != null -> {
                        specific
                    }

                    localizedValidation != null -> {
                        localizedValidation
                    }

                    failure is IllegalArgumentException || failure is IllegalStateException -> {
                        resources.getString(R.string.provider_check_config)
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
        pairingJob?.cancel()
        pairingJob = null
        pairingStarting = false
        pairingFetchScope?.cancel()
        pairingFetchScope = null
        // close() shuts the sockets synchronously, which is fast; the worker join it also starts is
        // on its own daemon thread, so this no longer freezes the dial while a peer sits in read().
        pairing?.close()
        pairing = null
        pairingNote = note
    }

    fun stepBack() {
        if (pairing != null || pairingStarting) {
            stopPairing()
        } else {
            stopPairing()
            key = ""
            editId = null
            adding = false
            models = emptyList()
            confirmDelete = false
            message = ""
        }
    }

    val inFormMode = adding || editId != null
    BackHandler(inFormMode) { stepBack() }

    /**
     * Starts the pairing listener WITHOUT blocking the UI thread.
     *
     * Runs `lanAddress()` + key/cert generation + Socket bind off Main thread and reports back.
     */
    fun startPairing() {
        if (pairingStarting) return
        val preset = PairingPreset(type, name.trim(), base.trim(), model.trim())
        val wasEditingId = edit?.id
        pairingStarting = true
        pairingNote = resources.getString(R.string.provider_pairing_starting)
        val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        pairingFetchScope?.cancel()
        pairingFetchScope = fetchScope
        pairingJob =
            scope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        val host = lanAddress() ?: return@withContext null
                        try {
                            val created =
                                PairingServer(
                                    host = host,
                                    clock = SystemClock::elapsedRealtime,
                                    initialPreset = preset,
                                    allowLocalHttp = BuildConfig.DEBUG,
                                    onFetchModels = { req ->
                                        runBlocking {
                                            fetchScope
                                                .async {
                                                    val adapter =
                                                        app.provider(req.type)
                                                            ?: return@async ModelDiscoveryResult.Unavailable(
                                                                ProviderError.PROVIDER_UNSUPPORTED,
                                                            )
                                                    val temp =
                                                        ProviderProfile(
                                                            "pairing-fetch",
                                                            req.type,
                                                            req.type.name,
                                                            Endpoints.normalize(req.baseUrl, BuildConfig.DEBUG),
                                                            "",
                                                            "00000000-0000-0000-0000-000000000000",
                                                        )
                                                    withTimeoutOrNull(DISCOVERY_BUDGET_MS) {
                                                        adapter.listModels(temp, SecretValue(sanitizeKey(req.apiKey)))
                                                    } ?: ModelDiscoveryResult.Unavailable(ProviderError.TIMEOUT)
                                                }.await()
                                        }
                                    },
                                ) { payload ->
                                    scope.launch {
                                        stopPairing(resources.getString(R.string.provider_key_received))
                                        editId = wasEditingId
                                        adding = wasEditingId == null
                                        reveal = false
                                        validated = false
                                        models = emptyList()
                                        type = payload.type
                                        name = payload.name.ifBlank { resources.getString(payload.type.nameRes()) }
                                        base = payload.baseUrl
                                        model = payload.modelId
                                        // Never repopulate a saved key into an editable field.
                                        key = ""
                                        saveProfile(SecretValue(sanitizeKey(payload.apiKey)))
                                    }
                                }
                            try {
                                if (!currentCoroutineContext().isActive) throw CancellationException()
                                created.start()
                                if (!currentCoroutineContext().isActive) throw CancellationException()
                                Result.success(host to created)
                            } catch (t: Throwable) {
                                runCatching { created.close() }
                                throw t
                            }
                        } catch (cancelled: CancellationException) {
                            fetchScope.cancel()
                            throw cancelled
                        } catch (failure: Throwable) {
                            fetchScope.cancel()
                            Result.failure(failure)
                        }
                    }
                pairingStarting = false
                if (result == null) {
                    fetchScope.cancel()
                    if (pairingFetchScope === fetchScope) pairingFetchScope = null
                    pairingNote =
                        resources.getString(
                            if (remoteTyping) R.string.provider_no_wifi_remote else R.string.provider_no_wifi,
                        )
                    return@launch
                }
                result
                    .onSuccess { (host, created) ->
                        if (!adding && editId == null) {
                            fetchScope.cancel()
                            if (pairingFetchScope === fetchScope) pairingFetchScope = null
                            runCatching { created.close() }
                            return@onSuccess
                        }
                        pairing = created
                        pairingNote =
                            if (isPrivateAddress(host)) {
                                ""
                            } else {
                                resources.getString(R.string.provider_not_home_network)
                            }
                    }.onFailure {
                        if (pairingFetchScope === fetchScope) pairingFetchScope = null
                        pairingNote = resources.getString(R.string.provider_pairing_unavailable)
                    }
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
                if (pairing != null) {
                    stringResource(R.string.provider_setup_from_phone)
                } else if (adding || edit != null) {
                    stringResource(
                        if (edit != null) R.string.provider_title_edit else R.string.provider_title_add,
                        stringResource(type.nameRes()),
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
                onClick = { if (inFormMode) stepBack() else onBack() },
                modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(R.string.provider_back))
            }
        }
        val stateError = state.error
        if (stateError != null) {
            item { Text(errorText(stateError) ?: stateError, fontSize = 14.sp) }
        } else if (!adding && editId == null) {
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
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        adding = true
                        editId = null
                        type = preset.type
                        name = presetName
                        base = preset.base
                        model = ""
                        key = ""
                        validated = false
                        reveal = false
                        models = emptyList()
                        pairingNote = ""
                        message = presetMessage
                    },
                ) { Text(presetLabel) }
            }
            items(state.profiles, key = { it.id }) { p ->
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
                        editId = p.id
                        adding = false
                        type = p.type
                        name = p.displayName
                        base = p.baseUrl
                        model = p.selectedModelId
                        key = ""
                        validated = false
                        reveal = false
                        models = emptyList()
                        confirmDelete = false
                        pairingNote = ""
                        message = resources.getString(R.string.provider_key_configured)
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.provider_edit_replace_key)) }
                }
            }
        } else if (pairing != null) {
            item {
                val server = pairing!!
                val formattedCountdown =
                    String.format(Locale.US, "%d:%02d", remainingSecs / 60, remainingSecs % 60)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.provider_pairing_expires, formattedCountdown),
                        fontSize = 13.sp,
                        color = Color(0xFFFFD28A),
                        modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally),
                    )
                    Text(
                        stringResource(R.string.provider_pairing_hint),
                        fontSize = 13.sp,
                        color = Color(0xFFB8BDC6),
                    )
                    if (pairingNote.isNotBlank()) {
                        Text(pairingNote, fontSize = 13.sp, color = Color(0xFFFFD28A))
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            pairingNote = resources.getString(R.string.provider_opening_on_phone)
                            scope.launch {
                                val opened = openUrlOnPairedPhone(context, server.url)
                                if (pairing === server) {
                                    pairingNote =
                                        resources.getString(
                                            if (opened.isSuccess) {
                                                R.string.provider_opened_on_phone
                                            } else {
                                                R.string.provider_open_on_phone_failed
                                            },
                                        )
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.provider_open_on_phone), fontSize = 14.sp)
                    }
                    // Drawn from the SAME capability URL the phone page is served from, centered
                    // inside the 466 px circular safe area so corners are never bezel-clipped.
                    val qr = remember(server.url) { QrCode.matrix(server.url) }
                    val qrLabel = stringResource(R.string.provider_pairing_qr)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp)
                            .aspectRatio(1f)
                            .semantics { contentDescription = qrLabel }
                            .drawWithCache {
                                val side = QrCode.sidePx(qr, minOf(size.width, size.height).toInt())
                                if (side <= 0) {
                                    onDrawBehind {}
                                } else {
                                    val module = side.toFloat() / qr.size
                                    val left = ((size.width.toInt() - side) / 2).toFloat()
                                    val top = ((size.height.toInt() - side) / 2).toFloat()
                                    val bgTopLeft = Offset(left, top)
                                    val bgSize = Size(side.toFloat(), side.toFloat())
                                    val darkPath =
                                        Path().apply {
                                            for (y in 0 until qr.size) {
                                                val rowTop = top + y * module
                                                val rowBottom = rowTop + module
                                                var x = 0
                                                while (x < qr.size) {
                                                    if (!qr.isDark(x, y)) {
                                                        x++
                                                        continue
                                                    }
                                                    val runStart = x
                                                    while (x < qr.size && qr.isDark(x, y)) x++
                                                    addRect(
                                                        Rect(
                                                            left + runStart * module,
                                                            rowTop,
                                                            left + x * module,
                                                            rowBottom,
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    onDrawBehind {
                                        drawRect(Color.White, bgTopLeft, bgSize)
                                        drawPath(darkPath, Color.Black)
                                    }
                                }
                            },
                    )
                    OutlinedButton(
                        onClick = { stopPairing() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.provider_cancel_pairing), fontSize = 14.sp)
                    }
                    // Kept as the last resort and for URL verification on untrusted Wi-Fi.
                    Text(server.url, fontSize = 14.sp, color = Color(0xFFA8C7FA))
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && !pairingStarting,
                        onClick = { startPairing() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.provider_setup_from_phone),
                            fontSize = 14.sp,
                        )
                    }
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
                    if (edit != null) {
                        Text(
                            stringResource(R.string.provider_configured_replace),
                            fontSize = 14.sp,
                            color = Color(0xFFB8BDC6),
                        )
                    }
                    impliedProviderMismatch(key, type)?.let {
                        Text(
                            stringResource(R.string.provider_key_mismatch, stringResource(it.nameRes())),
                            fontSize = 14.sp,
                            color = Color(0xFFFFD28A),
                        )
                    }
                    if (key.isBlank() && remoteTyping) {
                        Text(
                            stringResource(R.string.provider_remote_typing),
                            fontSize = 14.sp,
                            color = Color(0xFFB8BDC6),
                        )
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
                        val input = Triple(type, base, key)
                        val inputProfileId = edit?.id
                        try {
                            val p = profile()
                            val adapter = app.provider(p.type)
                            if (adapter == null) {
                                message = resources.getString(R.string.provider_type_unavailable)
                                return@launch
                            }
                            val result =
                                withTimeoutOrNull(DISCOVERY_BUDGET_MS) {
                                    adapter.listModels(p, secret(p))
                                } ?: ModelDiscoveryResult.Unavailable(ProviderError.TIMEOUT)
                            if (input != Triple(type, base, key) || inputProfileId != edit?.id ||
                                (!adding && editId == null)
                            ) {
                                return@launch
                            }
                            when (result) {
                                is ModelDiscoveryResult.Models -> {
                                    models = result.ids
                                    message = resources.getString(R.string.provider_models_listed)
                                }

                                is ModelDiscoveryResult.Unavailable -> {
                                    models = emptyList()
                                    val specific = errorText(resources, result.error.code(hasText = false))
                                    message =
                                        if (specific != null) {
                                            resources.getString(R.string.provider_models_unavailable_reason, specific)
                                        } else {
                                            resources.getString(R.string.provider_models_unavailable)
                                        }
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            if (input == Triple(type, base, key) && inputProfileId == edit?.id &&
                                (adding || editId != null)
                            ) {
                                message =
                                    validationErrorText(resources, failure)
                                        ?: resources.getString(R.string.provider_check_url_key)
                            }
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.provider_discover_models)) }
            }
            item {
                if (message.isNotBlank()) Text(message, fontSize = 14.sp, color = Color(0xFFFFD28A))
            }
            if (models.isNotEmpty()) {
                item {
                    OutlinedButton(
                        onClick = { models = emptyList() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.provider_hide_models), fontSize = 13.sp) }
                }
            }
            items(models, key = { it }) { id ->
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
                            val inputKey = key
                            val inputProfileId = edit?.id
                            val adapter = app.provider(p.type)
                            if (adapter == null) {
                                message = resources.getString(R.string.provider_type_unavailable)
                                return@launch
                            }
                            val error = adapter.validate(p, secret(p))
                            if (!adding && editId == null) return@launch
                            if (p.type != type || p.displayName != name.trim() ||
                                p.baseUrl != runCatching { Endpoints.normalize(base, BuildConfig.DEBUG) }.getOrNull() ||
                                p.selectedModelId != model.trim() ||
                                key != inputKey ||
                                inputProfileId != edit?.id
                            ) {
                                message = resources.getString(R.string.provider_inputs_changed)
                                return@launch
                            }
                            validated = error == null
                            message =
                                error
                                    ?.let { errorText(resources, it.code(hasText = false)) }
                                    ?: resources.getString(R.string.provider_connected)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            if (adding || editId != null) {
                                message =
                                    validationCause(failure)
                                        ?.let { errorText(resources, it.code(hasText = false)) }
                                        ?: validationErrorText(resources, failure)
                                        ?: resources.getString(R.string.provider_error_check_fields)
                            }
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.provider_validate)) }
            }
            item {
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
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        onClick = {
                            if (!confirmDelete) {
                                confirmDelete = true
                                return@OutlinedButton
                            }
                            scope.launch {
                                stopPairing()
                                busy = true
                                try {
                                    app.settings.delete(deleting.id)
                                    editId = null
                                    key = ""
                                    confirmDelete = false
                                    message = resources.getString(R.string.provider_deleted)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    message = resources.getString(R.string.provider_delete_failed)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                if (confirmDelete) R.string.provider_delete_confirm else R.string.provider_delete,
                            ),
                        )
                    }
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
