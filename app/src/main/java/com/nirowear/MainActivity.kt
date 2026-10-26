package com.nirowear

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.nirowear.chat.ChatViewModel
import com.nirowear.chat.ReaderAnchor
import com.nirowear.data.ProviderSettingsState
import com.nirowear.renderer.ContentBlock
import com.nirowear.renderer.DocumentBlock
import com.nirowear.renderer.InlineContent
import com.nirowear.renderer.ResponseParser
import com.nirowear.settings.ProviderScreen
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

// ponytail: single-activity app; the reader publishes its crown handler here. Move to a
// CompositionLocal if a second activity or split-screen reader ever exists.
private var rotary: ((Float) -> Boolean)? = null

class MainActivity : ComponentActivity() {
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.source and InputDevice.SOURCE_ROTARY_ENCODER == InputDevice.SOURCE_ROTARY_ENCODER
        ) {
            val delta = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (delta != 0f && rotary?.invoke(delta) == true) return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fixture =
            if (BuildConfig.DEBUG) {
                assets.open("RENDER_TORTURE_FIXTURE.md").bufferedReader().use {
                    it.readText()
                }
            } else {
                ""
            }
        setContent {
            MaterialTheme {
                ChatReader(fixture, if (BuildConfig.DEBUG) intent.getStringExtra("render_section") else null)
            }
        }
    }
}

/**
 * B3-03 coalescing window for streaming re-parses.
 *
 * One frame at 60 Hz. Long enough that a burst of token deltas collapses into a single parse,
 * short enough that partial content still becomes visible within a frame of arriving. See the
 * comment on the parse effect in [ChatReader] for why this is a delay rather than a memo change.
 */
private const val STREAM_PARSE_COALESCE_MS = 16L

@Composable
private fun ChatReader(
    fixture: String,
    initialSection: String?,
) {
    val chat: ChatViewModel = viewModel()
    val chatState by chat.state.collectAsStateWithLifecycle()
    val providerState by chat.settings.collectAsStateWithLifecycle(initialValue = ProviderSettingsState())
    val storedMessages by chat.messages.collectAsStateWithLifecycle()
    val conversations by chat.conversations.collectAsStateWithLifecycle(initialValue = emptyList())
    var providerScreen by rememberSaveable { mutableStateOf(false) }
    var historyScreen by rememberSaveable { mutableStateOf(false) }
    var editedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var notice by rememberSaveable { mutableStateOf("") }
    var renderLab by rememberSaveable { mutableStateOf(initialSection != null) }
    var sectionMenu by rememberSaveable { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf(initialSection ?: "Full fixture") }
    var following by rememberSaveable { mutableStateOf(true) }
    var followNewConversation by rememberSaveable { mutableStateOf(false) }
    val idleLatest =
        !chatState.generating && !following && !renderLab && !sectionMenu &&
            (chatState.rawMarkdown != null || storedMessages.isNotEmpty())
    val listState = rememberLazyListState()
    val menuState = rememberLazyListState()
    val historyState = rememberLazyListState()
    val labState = rememberLazyListState()
    // ponytail: saved-instance anchors, not durable force-stop bookmarks; add local persistence only if cold-launch restore is required.
    val anchors =
        rememberSaveable(
            saver =
                Saver<MutableMap<String, ReaderAnchor>, ArrayList<Any>>(
                    save = { map ->
                        ArrayList<Any>().apply {
                            map.forEach { (id, a) ->
                                addAll(listOf(id, a.messageId, a.block, a.index, a.offset, a.contentHash, a.following))
                            }
                        }
                    },
                    restore = { values ->
                        values
                            .chunked(7)
                            .associate { v ->
                                v[0] as String to
                                    ReaderAnchor(
                                        v[1] as Long,
                                        v[2] as Int,
                                        v[3] as Int,
                                        v[4] as Int,
                                        v[5] as Int,
                                        v[6] as Boolean,
                                    )
                            }.toMutableMap()
                    },
                ),
        ) { mutableMapOf<String, ReaderAnchor>() }
    var historyBlocks by remember { mutableStateOf<List<Pair<Long, ContentBlock>>>(emptyList()) }
    var parsedRows by remember {
        mutableStateOf<Map<com.nirowear.data.StoredMessage, List<Pair<Long, ContentBlock>>>>(emptyMap())
    }
    var parsedConversation by remember { mutableStateOf<String?>(null) }
    var restoredConversation by remember { mutableStateOf<String?>(null) }
    // Survives effect restarts, which is what turns the coalescing delay into a throttle rather
    // than a debounce. See the parse effect below.
    var lastParseAt by remember { mutableStateOf(0L) }
    var footerHeight by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val renderedHistory = historyBlocks
    val historyKeys =
        remember(renderedHistory) {
            val counts = mutableMapOf<Long, Int>()
            renderedHistory.map { (id, _) ->
                val block = counts[id] ?: 0
                counts[id] = block + 1
                "$id:$block"
            }
        }
    val currentSource by rememberUpdatedState(chatState.conversationId to storedMessages)
    // Presentation includes status/error/provenance, not just Markdown. All fields invalidate it.
    LaunchedEffect(storedMessages, chatState.conversationId) {
        val id = chatState.conversationId
        val messages = storedMessages
        val previous = parsedRows

        // B3-03: coalesce re-parses, but guarantee progress.
        //
        // This effect restarts on every emission, and `ResponseParser.parse` is non-cooperative
        // once started, so without a suspension point before the work every superseded coroutine
        // still parsed the whole accumulated prefix and only then discarded its result -- 176
        // deltas meant 176 full re-parses (see StreamingReparseWorkloadTest).
        //
        // A plain debounce is WRONG here: if deltas keep arriving faster than the window, every
        // run is cancelled before it parses and the answer stays blank until the stream pauses
        // (measured: 0 parses over 400 ms at a 4 ms cadence, see StreamingParseStarvationTest).
        // So the wait is skipped when a parse is already overdue. `lastParseAt` is remembered
        // across restarts, which turns the debounce into a throttle: at most one parse per
        // STREAM_PARSE_COALESCE_MS, and always at least one, no matter how fast the stream is.
        //
        // The renderer only needs the LATEST accumulated text, so the skipped intermediate
        // snapshots are pure waste. The final state is always parsed.
        val needsParse = messages.any { it.conversationId == id && previous[it] == null }
        if (needsParse) {
            val sinceLastParse = System.currentTimeMillis() - lastParseAt
            if (sinceLastParse < STREAM_PARSE_COALESCE_MS) {
                kotlinx.coroutines.delay(STREAM_PARSE_COALESCE_MS - sinceLastParse)
            }
        }

        val rows =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                messages.filter { it.conversationId == id }.associateWith { message ->
                    previous[message] ?: run {
                        val label =
                            if (message.role ==
                                "USER"
                            ) {
                                "You · hold to edit"
                            } else {
                                "${message.providerLabel} · ${message.modelId}"
                            }
                        listOf(message.id to ContentBlock.Heading(4, listOf(InlineContent(label)))) +
                            ResponseParser.parse(message.rawContent).map { message.id to it } +
                            if (message.status == "STOPPED" ||
                                message.error != null
                            ) {
                                listOf(
                                    message.id to
                                        ContentBlock.Paragraph(
                                            listOf(InlineContent(message.error ?: "Stopped · partial answer kept")),
                                        ),
                                )
                            } else {
                                emptyList()
                            }
                    }
                }
            }
        currentCoroutineContext().ensureActive()
        if (currentSource != (id to messages)) return@LaunchedEffect
        lastParseAt = System.currentTimeMillis()
        parsedRows = rows // rebuilt from the live list each time, so deleted rows are dropped
        historyBlocks = rows.values.flatten()
        parsedConversation = id
    }

    fun saveReaderPosition() {
        val id = chatState.conversationId ?: return
        if (parsedConversation != id || historyBlocks.isEmpty() || renderLab) return
        val index =
            (listState.firstVisibleItemIndex - if (notice.isNotEmpty()) 1 else 0).coerceIn(
                historyBlocks.indices,
            )
        val messageId = historyBlocks[index].first
        val message = storedMessages.find { it.id == messageId } ?: return
        anchors[id] =
            ReaderAnchor(
                messageId,
                historyBlocks.take(index).count { it.first == messageId },
                index,
                listState.firstVisibleItemScrollOffset,
                message.rawContent.hashCode(),
                following,
            )
    }
    LaunchedEffect(sectionMenu) { if (sectionMenu) menuState.scrollToItem(0) }
    LaunchedEffect(chatState.conversationId) {
        if (chatState.conversationId == null) {
            listState.scrollToItem(0)
            restoredConversation = null
        }
    }
    LaunchedEffect(
        chatState.conversationId,
        parsedConversation,
        historyBlocks,
        historyScreen,
        providerScreen,
        sectionMenu,
        renderLab,
    ) {
        val id = chatState.conversationId
        if (!historyScreen && !providerScreen && !sectionMenu && !renderLab && parsedConversation == id &&
            restoredConversation != id &&
            historyBlocks.isNotEmpty()
        ) {
            val a = anchors[id]
            following = a?.following ?: (chatState.generating || followNewConversation)
            val position =
                a?.resolve(historyBlocks, storedMessages.associate { it.id to it.rawContent.hashCode() }) ?: (0 to 0)
            listState.scrollToItem(position.first + if (notice.isNotEmpty()) 1 else 0, position.second)
            restoredConversation = id
            followNewConversation = false
        }
    }
    val savePosition by rememberUpdatedState({
        if (!sectionMenu && !historyScreen && !providerScreen &&
            !editing
        ) {
            saveReaderPosition()
        }
    })
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) savePosition() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var userScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(dragging) {
        if (dragging && !sectionMenu && !historyScreen && !providerScreen &&
            !renderLab
        ) {
            following = false
            followNewConversation = false
            userScrolled = true
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && !dragging && userScrolled) {
            userScrolled = false
            val info = listState.layoutInfo
            val lastMessage = parsedRows.keys.lastOrNull()
            val lastContentKey =
                if (lastMessage?.status == "STOPPED" || lastMessage?.error != null) {
                    historyKeys.dropLast(1).lastOrNull()
                } else {
                    historyKeys.lastOrNull()
                }
            val lastContent = info.visibleItemsInfo.find { it.key == lastContentKey }
            following = chatState.generating && (
                if (lastContentKey != null) {
                    lastContent != null &&
                        lastContent.offset + lastContent.size <= info.viewportEndOffset + 48
                } else {
                    !listState.canScrollForward
                }
            )
            saveReaderPosition()
        }
    }
    val voice =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // Blank recognitions must not replace an existing draft or open an empty editor.
                result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        draft = it
                        editedId = null
                        editing = true
                    }
            }
        }
    BackHandler(editing) { editing = false }
    BackHandler(providerScreen) { providerScreen = false }
    BackHandler(historyScreen) { historyScreen = false }
    BackHandler(sectionMenu && !historyScreen && !providerScreen && !editing) { sectionMenu = false }
    BackHandler(renderLab && !editing && !providerScreen && !historyScreen) {
        renderLab = false
        sectionMenu = false
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (providerScreen) {
            ProviderScreen { providerScreen = false }
        } else if (historyScreen) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = historyState,
                contentPadding = PaddingValues(horizontal = 30.dp, vertical = 30.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "History",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontSize = 18.sp,
                    )
                }
                item {
                    Button(
                        onClick = {
                            historyScreen = false
                        },
                        modifier =
                            Modifier.fillMaxWidth().wrapContentWidth(
                                Alignment.CenterHorizontally,
                            ),
                    ) { Text("Back") }
                }
                item {
                    Button(enabled = !chatState.generating, onClick = {
                        chat.newChat()
                        historyScreen = false
                        renderLab =
                            false
                        following = true
                        followNewConversation = false
                    }, modifier = Modifier.fillMaxWidth()) { Text("New chat") }
                }
                items(conversations, key = { it.id }) { conversation ->
                    androidx.wear.compose.material3.OutlinedButton(
                        enabled =
                            !chatState.generating || conversation.id == chatState.conversationId,
                        onClick = {
                            if (conversation.id !=
                                chatState.conversationId
                            ) {
                                chat.open(conversation.id)
                                restoredConversation = null
                                following =
                                    false
                                followNewConversation = false
                            }
                            ; historyScreen = false
                            renderLab = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(conversation.title) }
                }
            }
        } else if (editing) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Prompt", modifier = Modifier.align(Alignment.CenterHorizontally))
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF202124))
                            .padding(8.dp),
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    cursorBrush =
                        androidx.compose.ui.graphics
                            .SolidColor(Color.White),
                )
                Button(
                    onClick = {
                        if (draft.isNotBlank()) {
                            editing = false
                            renderLab = false
                            sectionMenu = false
                            following = true
                            followNewConversation = chatState.conversationId == null
                            if (editedId !=
                                null
                            ) {
                                anchors.remove(chatState.conversationId)
                                chat.edit(editedId!!, draft)
                            } else {
                                chat.send(draft)
                            }
                            editedId = null
                        }
                    },
                    enabled = draft.isNotBlank() && !chatState.generating,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(
                        if (editedId ==
                            null
                        ) {
                            "Send"
                        } else {
                            "Edit & resend"
                        },
                    )
                }
            }
        } else {
            val source =
                if (!renderLab) {
                    chatState.rawMarkdown.orEmpty()
                } else {
                    when (section) {
                        "Unicode" -> {
                            "## Unicode and symbols" +
                                fixture.substringAfter("## Unicode and symbols").substringBefore("## Inline math")
                        }

                        "Math" -> {
                            "## Inline math" + fixture.substringAfter("## Inline math").substringBefore("## Code")
                        }

                        "Code" -> {
                            "## Code" + fixture.substringAfter("## Code").substringBefore("## Table")
                        }

                        "Table" -> {
                            "## Table" + fixture.substringAfter("## Table").substringBefore("## Very long URL")
                        }

                        "Partial syntax" -> {
                            "**unfinished bold\n\n`unfinished code\n\n\\[\\frac{1"
                        }

                        else -> {
                            fixture
                        }
                    }
                }
            var blocks by remember(
                renderLab,
                section,
                chatState.conversationId,
            ) { mutableStateOf<List<ContentBlock>>(emptyList()) }
            LaunchedEffect(source, renderLab, section, chatState.conversationId) {
                blocks =
                    kotlinx.coroutines.withContext(
                        kotlinx.coroutines.Dispatchers.Default,
                    ) { ResponseParser.parse(source) }
            }
            val blockHeights =
                remember(chatState.conversationId, LocalDensity.current) {
                    androidx.compose.runtime.mutableStateMapOf<Pair<Long, ContentBlock>, Int>()
                }
            LaunchedEffect(renderedHistory) { blockHeights.keys.retainAll(renderedHistory.toSet()) }
            val spacingPx = with(LocalDensity.current) { 12.dp.roundToPx() }
            val answerId = storedMessages.lastOrNull()?.takeIf { it.role == "ASSISTANT" }?.id
            val answerRow = parsedRows.keys.lastOrNull()?.takeIf { it.id == answerId }
            val transientStopped = chatState.interrupted && !chatState.generating && storedMessages.isEmpty()
            val answerIndices =
                historyBlocks.indices.filter { historyBlocks[it].first == answerId }.drop(1).let {
                    if (answerRow?.status == "STOPPED" || answerRow?.error != null) it.dropLast(1) else it
                }
            var measuredAnswer by remember(answerId, following, chatState.generating) { mutableStateOf(false) }
            LaunchedEffect(
                blocks,
                historyBlocks,
                blockHeights.toMap(),
                following,
                renderLab,
                sectionMenu,
                chatState.generating,
                restoredConversation,
                footerHeight,
                answerId,
                chatState.error,
                chatState.interrupted,
                notice,
            ) {
                if (!renderLab && !sectionMenu && following &&
                    (chatState.conversationId == null || restoredConversation == chatState.conversationId) &&
                    (chatState.rawMarkdown != null || storedMessages.isNotEmpty())
                ) {
                    val count =
                        blocks.size + historyBlocks.size + (if (chatState.prompt.isNotEmpty()) 1 else 0) +
                            (if (notice.isNotEmpty()) 1 else 0) + (if (chatState.error != null) 1 else 0) +
                            (if (chatState.generating || transientStopped) 1 else 0) +
                            (if (!chatState.generating && storedMessages.lastOrNull()?.role == "ASSISTANT") 1 else 0)
                    val firstAnswer = answerIndices.firstOrNull()?.plus(if (notice.isNotEmpty()) 1 else 0)
                    if (!chatState.generating && !measuredAnswer && firstAnswer != null) {
                        measuredAnswer = true
                        listState.scrollToItem(firstAnswer)
                        androidx.compose.runtime.withFrameNanos { }
                    }
                    val heights = answerIndices.map { blockHeights[historyBlocks[it]] }
                    val viewport = listState.layoutInfo.let { it.viewportEndOffset - it.viewportStartOffset }
                    val answerFits =
                        heights.isNotEmpty() && heights.all { it != null } &&
                            heights.sumOf { it ?: 0 } + spacingPx * (heights.size - 1) <= viewport - spacingPx * 2
                    if (!chatState.generating && answerRow?.status == "STOPPED") {
                        // Keep the single persisted status before Retry visible; never follow a detached reader.
                        listState.scrollToItem(historyBlocks.lastIndex + if (notice.isNotEmpty()) 1 else 0)
                    } else if (answerFits && firstAnswer != null) {
                        listState.scrollToItem(firstAnswer)
                    } else if (listState.canScrollForward) {
                        val lastContent = answerIndices.lastOrNull()
                        val height = lastContent?.let { blockHeights[historyBlocks[it]] }
                        if (!chatState.generating && lastContent != null && height != null) {
                            listState.scrollToItem(
                                lastContent + if (notice.isNotEmpty()) 1 else 0,
                                (height - viewport + spacingPx).coerceAtLeast(0),
                            )
                        } else {
                            listState.scrollToItem((count - 1).coerceAtLeast(0))
                        }
                    }
                }
            }
            val rotaryScope = rememberCoroutineScope()
            val rotaryList =
                if (sectionMenu) {
                    menuState
                } else if (renderLab) {
                    labState
                } else {
                    listState
                }
            // The crown scrolls whichever list owns the screen; cleared when the editor takes over.
            DisposableEffect(sectionMenu, renderLab) {
                rotary = { delta ->
                    if (editing) {
                        false
                    } else {
                        if (delta < 0 && !renderLab && !sectionMenu) following = false
                        rotaryScope.launch { rotaryList.scrollBy(delta) }
                        true
                    }
                }
                onDispose { rotary = null }
            }
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(
                            top = 48.dp,
                            bottom =
                                if (sectionMenu) {
                                    28.dp
                                } else {
                                    with(LocalDensity.current) {
                                        footerHeight.toDp()
                                    } + 6.dp
                                },
                        ).semantics {
                            contentDescription =
                                if (sectionMenu) {
                                    "Navigation menu"
                                } else if (renderLab) {
                                    "RenderLab reader"
                                } else {
                                    "Conversation reader"
                                }
                            if (BuildConfig.DEBUG) {
                                stateDescription =
                                    "viewport=${listState.layoutInfo.let {
                                        it.viewportEndOffset - it.viewportStartOffset
                                    }};answer=" +
                                    answerIndices.joinToString(",") { index ->
                                        historyBlocks.getOrNull(index)?.let { blockHeights[it] }?.toString()
                                            ?: "unknown"
                                    } +
                                    ";index=${listState.firstVisibleItemIndex}" +
                                    ";offset=${listState.firstVisibleItemScrollOffset}" +
                                    ";forward=${listState.canScrollForward}" +
                                    ";scrolling=${listState.isScrollInProgress}" +
                                    ";following=$following" +
                                    ";footer=$footerHeight" +
                                    ";visible=${listState.layoutInfo.visibleItemsInfo.joinToString {
                                        "${it.index}:${it.offset}:${it.size}"
                                    }}"
                            }
                        },
                state =
                    if (sectionMenu) {
                        menuState
                    } else if (renderLab) {
                        labState
                    } else {
                        listState
                    },
                contentPadding = PaddingValues(horizontal = if (sectionMenu) 44.dp else 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (sectionMenu) 4.dp else 12.dp),
            ) {
                if (sectionMenu) {
                    item {
                        Button(enabled = !chatState.generating, onClick = {
                            providerScreen = true
                            sectionMenu =
                                false
                        }, modifier = Modifier.fillMaxWidth()) { Text("Providers / model") }
                    }
                    item {
                        Button(onClick = {
                            historyScreen = true
                            sectionMenu = false
                        }, modifier = Modifier.fillMaxWidth()) { Text("History") }
                    }
                    item {
                        Button(enabled = !chatState.generating, onClick = {
                            chat.newChat()
                            renderLab = false
                            sectionMenu =
                                false
                            following = true
                            followNewConversation = false
                        }, modifier = Modifier.fillMaxWidth()) { Text("New chat") }
                    }
                    if (BuildConfig.DEBUG) {
                        items(listOf("Full fixture", "Unicode", "Math", "Code", "Table", "Partial syntax")) { name ->
                            Button(onClick = {
                                section = name
                                renderLab = true
                                sectionMenu = false
                            }, modifier = Modifier.fillMaxWidth()) { Text(name) }
                        }
                        item {
                            Button(onClick = {
                                renderLab = false
                                sectionMenu = false
                                following = true
                                chat.start("RenderLab streaming fixture", fixture)
                            }, enabled = !chatState.generating, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "Stream fixture",
                                )
                            }
                        }
                    }
                } else {
                    if (notice.isNotEmpty()) item { Text(notice, fontSize = 14.sp) }
                    if (!renderLab &&
                        chatState.prompt.isNotEmpty()
                    ) {
                        item { Text(chatState.prompt, fontSize = 14.sp, color = Color(0xFFA8C7FA)) }
                    }
                    if (!renderLab && parsedConversation == chatState.conversationId) {
                        itemsIndexed(
                            renderedHistory,
                            key = { index, _ -> historyKeys[index] },
                        ) { index, (messageId, block) ->
                            val message = storedMessages.find { it.id == messageId }
                            val editable = message?.role == "USER" && !chatState.generating
                            val editModifier =
                                if (editable) {
                                    Modifier
                                        .combinedClickable(onClick = {
                                        }, onLongClickLabel = "Edit message", onLongClick = {
                                            saveReaderPosition()
                                            editedId = messageId
                                            draft = message.rawContent
                                            editing = true
                                        })
                                } else {
                                    Modifier
                                }
                            Box(
                                Modifier.onSizeChanged { blockHeights[messageId to block] = it.height }.then(
                                    editModifier,
                                ),
                            ) { DocumentBlock(block) }
                        }
                    }
                    items(blocks) { DocumentBlock(it) }
                    if (!renderLab && chatState.error != null) item { Text(chatState.error!!, fontSize = 14.sp) }
                    if (!renderLab && chatState.unsaved) {
                        item {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    val clipboard =
                                        context.getSystemService(
                                            android.content.Context.CLIPBOARD_SERVICE,
                                        ) as android.content.ClipboardManager
                                    // Mark the clip sensitive so Android 13+ does not show a content preview of a
                                    // conversation answer on the clipboard overlay. This is a preview/visibility
                                    // control, NOT encryption: the clipboard still holds plaintext and any app the
                                    // user pastes into can read it. The notice below states that plainly.
                                    val clip =
                                        android.content.ClipData.newPlainText(
                                            "NiroWear unsaved answer",
                                            chatState.rawMarkdown.orEmpty(),
                                        )
                                    clip.description.extras =
                                        android.os.PersistableBundle().apply {
                                            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                                        }
                                    clipboard.setPrimaryClip(clip)
                                    notice = "Unsaved answer copied as sensitive. Clipboard still holds plain text."
                                }) { Text("Copy unsaved answer") }
                                Button(
                                    enabled = !chatState.generating,
                                    onClick = { chat.retrySaving() },
                                ) { Text("Retry saving") }
                            }
                        }
                    }
                    if (providerState.error != null) item { Text(providerState.error!!, fontSize = 14.sp) }
                    if (!renderLab && storedMessages.isEmpty() && chatState.rawMarkdown == null) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Ask anything", fontSize = 18.sp)
                                Text(
                                    if (providerState.selected ==
                                        null
                                    ) {
                                        "Add provider from menu, then use keyboard or voice."
                                    } else {
                                        "Use keyboard or voice to start."
                                    },
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    color = Color(0xFFB8BDC6),
                                )
                            }
                        }
                    }
                    if (!renderLab && !chatState.generating && storedMessages.lastOrNull()?.role == "ASSISTANT") {
                        item {
                            ReaderAction(retry = true, onClick = {
                                anchors.remove(chatState.conversationId)
                                following =
                                    true
                                chat.retry()
                            })
                        }
                    }
                    if (!renderLab && (chatState.generating || transientStopped)) {
                        item {
                            Text(
                                if (chatState.generating) "Streaming…" else "Stopped · partial answer kept",
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .width(130.dp)
                    .height(48.dp)
                    .semantics { contentDescription = "Menu" }
                    .clickable(role = Role.Button) {
                        if (!sectionMenu) saveReaderPosition()
                        sectionMenu = !sectionMenu
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    if (renderLab) {
                        "RenderLab · $section"
                    } else {
                        providerState.selected?.let { "${it.displayName} · ${it.selectedModelId}" }
                            ?: "Add provider · Menu"
                    },
                    modifier = Modifier.height(42.dp).wrapContentHeight(Alignment.Bottom).padding(bottom = 6.dp),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!sectionMenu) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged {
                            footerHeight = it.height
                        }.padding(bottom = if (idleLatest) 48.dp else 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (chatState.generating && !renderLab) {
                            if (chatState.generating) {
                                Button(onClick = {
                                    chat.stop()
                                }, modifier = Modifier.width(72.dp).height(48.dp)) { Text("Stop", fontSize = 12.sp) }
                            }
                            if (!following) ReaderAction(retry = false, onClick = { following = true })
                        } else {
                            val iconColor = MaterialTheme.colorScheme.onSurfaceVariant
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = "Keyboard input" }
                                    // Re-entry keeps an unsent draft: Back out of the editor is not a discard.
                                    .clickable(role = Role.Button, onClick = {
                                        saveReaderPosition()
                                        editedId = null
                                        editing = true
                                    }),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    painterResource(R.drawable.ic_keyboard),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    colorFilter = ColorFilter.tint(iconColor),
                                )
                            }
                            if (idleLatest) ReaderAction(retry = false, onClick = { following = true })
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = "Voice input" }
                                    .clickable(role = Role.Button, onClick = {
                                        saveReaderPosition()
                                        try {
                                            voice.launch(
                                                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                    putExtra(
                                                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                                    )
                                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask NiroWear")
                                                },
                                            )
                                        } catch (_: ActivityNotFoundException) {
                                            notice = "Voice recognition unavailable. Use keyboard."
                                        } catch (_: SecurityException) {
                                            notice = "Voice recognition blocked. Use keyboard."
                                        }
                                    }),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    painterResource(R.drawable.ic_mic),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    colorFilter = ColorFilter.tint(iconColor),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderAction(
    retry: Boolean,
    onClick: () -> Unit,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(48.dp)
            .semantics { contentDescription = if (retry) "Retry response" else "Jump to latest" }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 2.dp.toPx()
            val path = Path()
            if (retry) {
                drawArc(
                    color,
                    45f,
                    285f,
                    false,
                    topLeft =
                        androidx.compose.ui.geometry
                            .Offset(size.width * .15f, size.height * .15f),
                    size =
                        androidx.compose.ui.geometry
                            .Size(size.width * .7f, size.height * .7f),
                    style = Stroke(stroke),
                )
                path.moveTo(size.width * .85f, size.height * .08f)
                path.lineTo(size.width * .85f, size.height * .36f)
                path.lineTo(size.width * .57f, size.height * .36f)
            } else {
                path.moveTo(size.width * .5f, size.height * .12f)
                path.lineTo(size.width * .5f, size.height * .85f)
                path.moveTo(size.width * .2f, size.height * .55f)
                path.lineTo(size.width * .5f, size.height * .85f)
                path.lineTo(size.width * .8f, size.height * .55f)
            }
            drawPath(path, color, style = Stroke(stroke))
        }
    }
}
