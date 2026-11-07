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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
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
import com.nirowear.chat.ReaderAction
import com.nirowear.chat.ReaderAnchor
import com.nirowear.chat.coalesceDelayMs
import com.nirowear.chat.evictStaleConversations
import com.nirowear.chat.mergeParsed
import com.nirowear.chat.needsParse
import com.nirowear.data.Conversation
import com.nirowear.data.ProviderSettingsState
import com.nirowear.data.SearchHit
import com.nirowear.data.likeTerms
import com.nirowear.data.searchMessages
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
        // ponytail: 4.5 KB debug fixture still read on the main thread; move to Dispatchers.IO
        // with a loading state if it ever grows or blocks first-frame measurement.
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
    // NOT saveable, deliberately. After process death the ViewModel re-opens the NEWEST conversation,
    // so a restored `editedId` points into a conversation that is no longer open: the edit targeted the
    // wrong conversation, threw, and the composer had already cleared itself.
    var editedId by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    // The EDIT BODY lives in its own slot, not in `draft`.
    //
    // Sharing one slot meant an edit had to overwrite the user's typed prompt and then "restore" it on
    // Back — a backup that had to be cleared in three places and could still outlive its edit, and that
    // resurrected the message body as a draft after process death (which then sent a duplicate of the
    // question instead of editing it). Separate slots make the two independent: abandoning an edit
    // cannot touch the draft, and a restored draft is always a draft. `editText` is not saveable, like
    // `editedId`, so the two always agree.
    var editText by remember { mutableStateOf("") }
    // The body an edit turn was started with, kept until the write is confirmed. `edit()` returns true
    // when the JOB LAUNCHES, not when the turn succeeds, so an edit refused before it wrote (no
    // profile, a credential that will not decrypt, a target that left the chat) closed the editor and
    // left the typed text nowhere — the row still holds its old content. See the effect below.
    var lastEditAttempt by remember { mutableStateOf<Pair<Long, String>?>(null) }
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
    // Confirm or recover the edit: once the turn stops, compare the row on disk with what was typed.
    // Equal means the edit landed. Different with an error on screen means it never wrote, so give the
    // user their text back instead of only telling them it failed.
    LaunchedEffect(chatState.error, chatState.generating, storedMessages, lastEditAttempt) {
        val attempt = lastEditAttempt ?: return@LaunchedEffect
        if (chatState.generating) return@LaunchedEffect
        val stored = storedMessages.firstOrNull { it.id == attempt.first }?.rawContent
        if (stored == attempt.second) {
            lastEditAttempt = null
            return@LaunchedEffect
        }
        if (chatState.error != null && stored != null) {
            editText = attempt.second
            editedId = attempt.first
            editing = true
            lastEditAttempt = null
        }
    }
    val menuState = rememberLazyListState()
    val labState = rememberLazyListState()
    // Hoisted out of HistoryScreen: the composable leaves the tree when the screen closes, so
    // `rememberLazyListState()` inside it was discarded and reopening History always jumped back to
    // the top (measured: the item that had been at the top was no longer in the tree). A watch has
    // room for ~2 history rows, so losing the offset means re-scrolling a long list every time.
    val historyState = rememberLazyListState()
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
    // Task 5: recency for the parse-memo bound. The memo keeps raw content plus the block tree
    // per message, so only the last few conversations are retained.
    var recentConversations by remember { mutableStateOf(emptyList<String>()) }
    var restoredConversation by remember { mutableStateOf<String?>(null) }
    // Survives effect restarts, which is what turns the coalescing delay into a throttle rather
    // than a debounce. See the parse effect below.
    var lastParseAt by remember { mutableLongStateOf(0L) }
    var footerHeight by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val renderedHistory = historyBlocks
    val historyKeys = remember(renderedHistory) { historyKeysFor(renderedHistory) }
    // One lookup per message, not one per rendered block. The item body used to call
    // storedMessages.find { it.id == messageId } for EVERY block, which is O(blocks x messages) on
    // every recomposition — with a 200-message conversation and several blocks per message that is
    // thousands of comparisons per frame while streaming.
    val messageById = remember(storedMessages) { storedMessages.associateBy { it.id } }
    val currentSource by rememberUpdatedState(chatState.conversationId to storedMessages)
    // Resolved here: the presentation effect below is not a composable scope.
    val stoppedBeforeStart =
        androidx.compose.ui.res
            .stringResource(R.string.reader_stopped_before_start)
    val stoppedPartial =
        androidx.compose.ui.res
            .stringResource(R.string.reader_stopped_partial)
    // The live status row hardcoded these two strings (English only) while the identical persisted
    // status line already came from resources, so an Indonesian reader saw English mid-stream.
    val streamingLabel =
        androidx.compose.ui.res
            .stringResource(R.string.reader_streaming)
    // Presentation includes status/error/provenance, not just Markdown. All fields invalidate it.
    LaunchedEffect(storedMessages, chatState.conversationId) {
        val id = chatState.conversationId
        val messages = storedMessages
        val previous = evictStaleConversations(parsedRows, recentConversations + listOfNotNull(id))

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
        if (needsParse(messages, id, previous)) {
            val wait = coalesceDelayMs(System.currentTimeMillis(), lastParseAt, STREAM_PARSE_COALESCE_MS)
            if (wait > 0) kotlinx.coroutines.delay(wait)
        }

        val rows =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                messages.filter { it.conversationId == id }.associateWith { message ->
                    previous[message] ?: run {
                        val label =
                            if (message.role == "USER") {
                                "You · hold to edit"
                            } else {
                                // Provenance is optional: a turn that failed before a profile was
                                // resolved has neither field, and the old interpolation printed the
                                // literal "null · null" under it.
                                listOfNotNull(message.providerLabel, message.modelId)
                                    .joinToString(" · ")
                                    .ifBlank { "Answer" }
                            }
                        listOf(message.id to ContentBlock.Heading(4, listOf(InlineContent(label)))) +
                            ResponseParser.parse(message.rawContent).map { message.id to it } +
                            if (message.status == "STOPPED" ||
                                message.error != null
                            ) {
                                // The status line must match the row's actual content. A Stop that lands before any
                                // row exists (the credential read window) records a STOPPED row with no
                                // text, and the generic "partial answer kept" claimed a partial answer
                                // that did not exist — the same lie this fix set out to remove, just
                                // persisted instead of transient.
                                listOf(
                                    message.id to
                                        ContentBlock.Paragraph(
                                            listOf(
                                                InlineContent(
                                                    message.error
                                                        ?: if (message.rawContent.isBlank()) {
                                                            stoppedBeforeStart
                                                        } else {
                                                            stoppedPartial
                                                        },
                                                ),
                                            ),
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
        // Merge, then bound: storing `rows` alone (the open conversation's messages) made the bound
        // and the recency list inert, so History A -> B -> A re-parsed all of A on a watch.
        val merged = mergeParsed(parsedRows, rows, recentConversations, id)
        recentConversations = merged.second
        parsedRows = merged.first
        // Same bound as the parse memo above: one entry per conversation ever opened
        // grows without limit otherwise.
        (anchors.keys - (recentConversations + listOfNotNull(id)).toSet()).forEach { anchors.remove(it) }
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
                a?.resolve(historyBlocks, messageById.mapValues { it.value.rawContent.hashCode() }) ?: (0 to 0)
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
        // Detach only when the drag can actually move the list backwards, the same guard the crown
        // uses. A drag on a conversation that fits on screen scrolled nothing, yet it hid `following`
        // and raised the "Jump to latest" arrow over an unmoved reader.
        if (dragging && !sectionMenu && !historyScreen && !providerScreen && !renderLab &&
            listState.canScrollBackward
        ) {
            following = false
            followNewConversation = false
            userScrolled = true
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && !dragging && userScrolled) {
            userScrolled = false
            // "At the bottom" means the list cannot scroll forward any more. The previous test was
            // "the last content block's bottom is inside the viewport", which stays TRUE when the
            // reader scrolls UP one notch during streaming — the block merely moves up and remains
            // visible — so following flipped straight back to true and the next delta snapped the
            // reader to the newest token. One crown notch could never detach, which contradicts the
            // rule that scrolling up must not pull the reader back down. canScrollBackward is not the
            // test either: with a long answer it is still true at the bottom.
            following = !listState.canScrollForward
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
                    ?.let { spoken ->
                        // Voice ADDS to the draft. The draft deliberately survives backing out of the
                        // editor, so replacing it here destroyed a typed prompt with no undo.
                        draft = if (draft.isBlank()) spoken else draft.trimEnd() + " " + spoken
                        // Voice is a fresh compose, never a resumed edit.
                        editText = ""
                        editedId = null
                        editing = true
                    }
            }
        }
    BackHandler(editing) {
        editing = false
        // Backing out abandons the edit body and NOTHING else: the user's unsent draft lives in its own
        // slot, so it is neither restored nor destroyed here. A plain compose keeps what was typed.
        editText = ""
        editedId = null
    }
    BackHandler(providerScreen) { providerScreen = false }
    BackHandler(historyScreen) { historyScreen = false }
    BackHandler(sectionMenu && !historyScreen && !providerScreen && !editing) { sectionMenu = false }
    // `!sectionMenu` matters: BackHandlers are LIFO and this one is registered after the menu's, so
    // without the guard one Back press closed the menu AND discarded the RenderLab fixture.
    BackHandler(renderLab && !editing && !providerScreen && !historyScreen && !sectionMenu) {
        // No `sectionMenu = false` here: the guard already requires the menu to be closed, so that
        // assignment could never change anything.
        renderLab = false
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (providerScreen) {
            ProviderScreen { providerScreen = false }
        } else if (historyScreen) {
            HistoryScreen(
                conversations = conversations,
                generating = chatState.generating,
                unsaved = chatState.unsaved,
                currentId = chatState.conversationId,
                listState = historyState,
                onBack = { historyScreen = false },
                onSelect = { id, messageId ->
                    // A notice belongs to the screen that raised it (clipboard, voice). Leaving it
                    // set pinned that line above every later conversation for the whole process.
                    notice = ""
                    if (id == null) {
                        chat.newChat()
                        following = true
                        followNewConversation = false
                    } else {
                        // A search hit names a MESSAGE. Seed the reading anchor at it, otherwise the
                        // reader fell back to block 0 and dumped the user at the top of a long
                        // conversation after the row promised that message. contentHash = -1 never
                        // matches, so the restore lands on the message's first block rather than a
                        // saved scroll offset.
                        if (messageId != null) anchors[id] = ReaderAnchor(messageId, 0, 0, 0, -1, false)
                        if (id != chatState.conversationId) chat.open(id)
                        // Re-arm the restore effect even for the conversation already open, where the
                        // id is unchanged and nothing else would make it run.
                        restoredConversation = null
                        following = false
                        followNewConversation = false
                    }
                    historyScreen = false
                    renderLab = false
                },
            )
        } else if (editing) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Prompt", modifier = Modifier.align(Alignment.CenterHorizontally))
                val composing = editedId != null
                val editorText = if (composing) editText else draft
                BasicTextField(
                    value = editorText,
                    onValueChange = { if (composing) editText = it else draft = it },
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
                        if (editorText.isNotBlank()) {
                            val editTarget = editedId
                            val started =
                                if (editTarget != null) {
                                    anchors.remove(chatState.conversationId)
                                    chat.edit(editTarget, editorText)
                                } else {
                                    chat.send(editorText)
                                }
                            // Only clear when the ViewModel ACCEPTED the turn. Clearing
                            // unconditionally (before it could report refusal) threw away the
                            // user's typed question whenever a send was refused — a failed init,
                            // an unsaved answer — and closed the editor over it.
                            if (started) {
                                editing = false
                                renderLab = false
                                sectionMenu = false
                                following = true
                                followNewConversation = chatState.conversationId == null
                                // The text now lives in the conversation. Clearing the slot that was
                                // actually used leaves the OTHER one untouched, so an edit never eats
                                // the user's unsent prompt and a send never eats the other way round.
                                if (editTarget != null) {
                                    lastEditAttempt = editTarget to editorText
                                    editText = ""
                                } else {
                                    draft = ""
                                }
                                notice = ""
                                editedId = null
                            }
                        }
                    },
                    enabled =
                        (if (editedId != null) editText else draft).isNotBlank() &&
                            !chatState.generating && !chatState.unsaved,
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
            // Keyed by the list's own stable key string, not by the block value. `ContentBlock.Paragraph`
            // is a data class holding the whole inline run, so a value key hashed the entire streaming
            // paragraph on every layout write and inserted a fresh entry per parse; `retainAll` then
            // re-hashed every block of the conversation once per frame. A String key is an identity
            // compare, and it also removes the duplicate keys a value key produced for two identical
            // blocks inside one message.
            val blockHeights =
                remember(chatState.conversationId, LocalDensity.current) {
                    androidx.compose.runtime.mutableStateMapOf<String, Int>()
                }
            LaunchedEffect(historyKeys) { blockHeights.keys.retainAll(historyKeys.toSet()) }
            val spacingPx = with(LocalDensity.current) { 12.dp.roundToPx() }
            val answerId = storedMessages.lastOrNull()?.takeIf { it.role == "ASSISTANT" }?.id
            // Find the row BY ID rather than taking the last KEY. `parsedRows` is a LinkedHashMap keyed by
            // the row itself, and `previous + parsed` keeps an existing key's position, so after History
            // A→B→A nothing moved, the last key was B's message, `takeIf` failed and the row read as
            // absent: the STOPPED/error status paragraph was counted as answer content again.
            val answerRow =
                remember(answerId, parsedRows) {
                    answerId?.let { id -> parsedRows.keys.firstOrNull { it.id == id } }
                }
            val transientStopped = chatState.interrupted && !chatState.generating && storedMessages.isEmpty()
            // ONE source of truth for which trailing rows exist. The follow effect counts them and the
            // LazyColumn emits them, and the two had drifted: the count ignored the unsaved-answer row
            // and the provider-error row, and it counted live blocks even while they were suppressed —
            // so auto-follow stopped short of the real last item.
            val liveBlocksShown = !chatState.unsaved || historyBlocks.isEmpty()
            val transientErrorShown =
                !renderLab && chatState.error != null && storedMessages.none { it.error == chatState.error }
            val unsavedRowShown = !renderLab && chatState.unsaved
            val providerErrorShown = providerState.error != null
            // "Ask anything" needs the conversation's parse to have run first: `storedMessages` is
            // cleared on every observe(id), so opening a 40-message conversation flashed the empty
            // prompt and then repopulated it. No conversation, or a parse that found nothing, is the
            // only honest moment to invite the user to start.
            val emptyPromptShown =
                !renderLab && storedMessages.isEmpty() && chatState.rawMarkdown == null &&
                    (chatState.conversationId == null || parsedConversation == chatState.conversationId)
            val retryRowShown =
                !renderLab && !chatState.generating && storedMessages.lastOrNull()?.role == "ASSISTANT"
            val streamingRowShown = !renderLab && (chatState.generating || transientStopped)
            val noticeRowShown = notice.isNotEmpty()
            // Memoised like its two neighbours: it used to allocate an index list, a filter result and
            // two more copies on every recomposition, i.e. once per checkpoint emission.
            val answerIndices =
                remember(historyBlocks, answerId, answerRow?.status, answerRow?.error) {
                    historyBlocks.indices.filter { historyBlocks[it].first == answerId }.drop(1).let {
                        if (answerRow?.status == "STOPPED" || answerRow?.error != null) it.dropLast(1) else it
                    }
                }
            var measuredAnswer by remember(answerId, following, chatState.generating) { mutableStateOf(false) }
            LaunchedEffect(
                blocks,
                historyBlocks,
                // blockHeights is deliberately NOT a key. It is written once per rendered block from
                // onSizeChanged, and `blockHeights.toMap()` produced a new map on every write, so
                // every measurement restarted this effect and re-ran the whole scroll decision. The
                // map is read inside the effect instead, where it is a snapshot read, so new heights
                // are seen without relaunching.
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
                        historyBlocks.size +
                            (if (liveBlocksShown) blocks.size else 0) +
                            (if (noticeRowShown) 1 else 0) +
                            (if (transientErrorShown) 1 else 0) +
                            (if (unsavedRowShown) 1 else 0) +
                            (if (providerErrorShown) 1 else 0) +
                            (if (emptyPromptShown) 1 else 0) +
                            (if (retryRowShown) 1 else 0) +
                            (if (streamingRowShown) 1 else 0)
                    val firstAnswer = answerIndices.firstOrNull()?.plus(if (notice.isNotEmpty()) 1 else 0)
                    if (!chatState.generating && !measuredAnswer && firstAnswer != null) {
                        measuredAnswer = true
                        listState.scrollToItem(firstAnswer)
                        androidx.compose.runtime.withFrameNanos { }
                    }
                    val heights = answerIndices.map { blockHeights[historyKeys[it]] }
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
                        val height = lastContent?.let { blockHeights[historyKeys[it]] }
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
                        // Only detach when the crown can actually move the list backwards: an upward
                        // tick on a conversation that fits on screen scrolled nothing, yet it used to
                        // hide following and raise the jump-to-latest arrow over an unmoved reader.
                        if (delta < 0 && !renderLab && !sectionMenu && rotaryList.canScrollBackward) {
                            following = false
                            followNewConversation = false
                            // Let the scroll-end effect re-decide following by position, the same way
                            // a finger drag does.
                            userScrolled = true
                        }
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
                                        historyBlocks
                                            .getOrNull(
                                                index,
                                            )?.let { blockHeights[historyKeys[index]] }
                                            ?.toString()
                                            ?: "unknown"
                                    } +
                                    ";index=${listState.firstVisibleItemIndex}" +
                                    ";offset=${listState.firstVisibleItemScrollOffset}" +
                                    ";forward=${listState.canScrollForward}" +
                                    // Forward extent in PIXELS, not pixels-to-the-last-item's-edge:
                                    // `canScrollForward` is true for a 2 px residue produced by the
                                    // footer's own height padding, and no drag can move that far.
                                    ";remaining=" +
                                    listState.layoutInfo.let { info ->
                                        val last = info.visibleItemsInfo.lastOrNull()
                                        if (last == null) 0 else (last.offset + last.size) - info.viewportEndOffset
                                    } +
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
                        // `unsaved` implies generating == false, so the old gate enabled this button exactly in
                        // the state where ChatViewModel.newChat() refuses: the tap closed the menu and
                        // nothing else happened, with no explanation.
                        Button(enabled = !chatState.generating && !chatState.unsaved, onClick = {
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
                    if (noticeRowShown) item { Text(notice, fontSize = 14.sp) }
                    // The newest USER row is already rendered from Room. Painting the composer
                    // prompt again as a second copy ("You · hold to edit" heading plus this line)
                    // showed every question twice.
                    if (!renderLab && parsedConversation == chatState.conversationId) {
                        itemsIndexed(
                            renderedHistory,
                            key = { index, _ -> historyKeys[index] },
                        ) { index, (messageId, block) ->
                            val message = messageById[messageId]
                            val editable = message?.role == "USER" && !chatState.generating
                            val editModifier =
                                if (editable) {
                                    Modifier
                                        .combinedClickable(onClick = {
                                        }, onLongClickLabel = "Edit message", onLongClick = {
                                            saveReaderPosition()
                                            // The edit body goes to its OWN slot: the unsent draft is
                                            // left exactly as it was, so abandoning the edit cannot
                                            // destroy it and cannot resurrect it later either.
                                            editText = message.rawContent
                                            editedId = messageId
                                            editing = true
                                        })
                                } else {
                                    Modifier
                                }
                            Box(
                                Modifier.onSizeChanged { blockHeights[historyKeys[index]] = it.height }.then(
                                    editModifier,
                                ),
                            ) { DocumentBlock(block) }
                        }
                    }
                    // The live blocks render the IN-FLIGHT answer only. When a terminal write fails, the ViewModel
                    // puts the same text in `rawMarkdown` (so it is not lost) while the live row is
                    // already merged into `historyBlocks` — rendering both duplicated the whole
                    // answer and pushed the "Copy unsaved answer / Retry saving" row below the copy.
                    // The empty-history guard keeps the answer visible if the merge did not happen.
                    if (liveBlocksShown) {
                        items(blocks) { DocumentBlock(it) }
                    }
                    // One copy of the transient failure only. The same string also renders on
                    // the persisted assistant row (:255-266), so a bare `error?.let` prints it
                    // twice once the row exists. Cleared history (edit/retry/new chat) keeps the
                    // transient row as the only copy.
                    if (transientErrorShown) {
                        chatState.error?.let { message -> item { Text(message, fontSize = 14.sp) } }
                    }
                    if (unsavedRowShown) {
                        item {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    val clipboard =
                                        context.getSystemService(
                                            android.content.Context.CLIPBOARD_SERVICE,
                                        ) as android.content.ClipboardManager
                                    val clip =
                                        android.content.ClipData.newPlainText(
                                            "NiroWear unsaved answer",
                                            chatState.rawMarkdown.orEmpty(),
                                        )
                                    markClipSensitive(clip)
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
                    if (providerErrorShown) {
                        providerState.error?.let { message ->
                            item { Text(message, fontSize = 14.sp) }
                        }
                    }
                    if (emptyPromptShown) {
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
                    if (retryRowShown) {
                        item {
                            ReaderAction(retry = true, onClick = {
                                anchors.remove(chatState.conversationId)
                                following =
                                    true
                                chat.retry()
                            })
                        }
                    }
                    if (streamingRowShown) {
                        item {
                            Text(
                                if (chatState.generating) streamingLabel else stoppedPartial,
                                fontSize = 12.sp,
                                // Polite, not assertive: this node carries STATE, not answer text. A
                                // screen reader was told nothing when streaming started or stopped, and
                                // no live region existed anywhere in the app.
                                modifier =
                                    Modifier.semantics {
                                        liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                                    },
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
                            // `unsaved` means the turn is over and only the save is retrying: there
                            // is no stream to stop, and offering Stop there invited a cancel that
                            // was reported as a storage failure.
                            if (!chatState.unsaved) {
                                if (!following) Spacer(Modifier.width(48.dp))
                                // Minimums, not fixed sizes: at a 2.0x font scale "Stop" in a 72.dp box wraps
                                // and clips inside a 48.dp height. The touch target stays 48 dp (the
                                // platform minimum) while the label may grow.
                                Button(
                                    onClick = {
                                        chat.stop()
                                    },
                                    modifier = Modifier.widthIn(min = 72.dp).heightIn(min = 48.dp),
                                ) { Text("Stop", fontSize = 12.sp, maxLines = 1) }
                            }
                            if (!following) ReaderAction(retry = false, onClick = { following = true })
                        } else {
                            val iconColor = MaterialTheme.colorScheme.onSurfaceVariant
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = "Keyboard input" }
                                    // Re-entry keeps an unsent DRAFT: Back out of the editor is not a
                                    // discard. The re-entry also DROPS a pending edit target —
                                    // `editText` and `editedId` are cleared together, so a stale
                                    // edit body can never reappear as a fresh message. (The previous
                                    // comment claimed the target was kept; the line below contradicts
                                    // it, and the code is the intended behaviour.)
                                    .clickable(role = Role.Button, onClick = {
                                        saveReaderPosition()
                                        // The keyboard always composes the DRAFT. `editText` and
                                        // `editedId` are cleared together, so a stale edit body can
                                        // never reappear as a fresh message.
                                        editText = ""
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

/**
 * History: recent conversations plus a search over every message ever stored.
 *
 * Search lives here rather than on a second screen because History already owns the round-screen
 * budget for a list, and a hit is just a conversation to open. It is a SQLite `LIKE` scan
 * (`likeTerms` escapes the wildcards), not FTS5: Android's bundled SQLite has no fts5 module, which
 * was measured on a Wear emulator as `no such module: fts5`.
 */
@Composable
private fun HistoryScreen(
    conversations: List<Conversation>,
    generating: Boolean,
    // `open`/`newChat` refuse while an answer is unsaved. The list used to stay enabled anyway, so a
    // tap closed History, changed nothing, and took the search text with it — a dead tap over a
    // reader that never moved. The menu's own New chat button was fixed for exactly this.
    unsaved: Boolean,
    currentId: String?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onBack: () -> Unit,
    onSelect: (String?, Long?) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as NiroApplication
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searched by remember { mutableStateOf("") }
    // Debounced so a fast typist does not issue one SQL query per keystroke on a watch.
    LaunchedEffect(query) {
        if (likeTerms(query).isEmpty()) {
            hits = emptyList()
            searched = ""
        } else {
            // Drop the previous result set BEFORE the wait. It used to stay on screen for the whole
            // debounce window, so the rows shown did not match the text in the field, and tapping
            // one opened a conversation from the query the user had already replaced.
            hits = emptyList()
            searched = ""
            kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
            hits =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    app.database.searchMessages(query, SEARCH_LIMIT)
                }
            searched = query
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 30.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("History", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 18.sp)
        }
        item {
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally),
            ) {
                Text("Back")
            }
        }
        item {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .background(Color(0xFF202124))
                        .padding(12.dp)
                        .semantics { contentDescription = "Search messages" },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush =
                    androidx.compose.ui.graphics
                        .SolidColor(Color.White),
                decorationBox = { field ->
                    if (query.isEmpty()) Text("Search all messages", fontSize = 16.sp, color = Color(0xFF8E939B))
                    field()
                },
            )
        }
        item {
            Button(
                enabled = !generating && !unsaved,
                onClick = { onSelect(null, null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("New chat")
            }
        }
        if (query.isNotBlank()) {
            if (hits.isEmpty() && searched.isNotBlank()) item { Text("No matches.", fontSize = 14.sp) }
            items(hits, key = { "hit-${it.messageId}" }) { hit ->
                val label = hit.rawContent.replace(Regex("\\s+"), " ").take(80)
                // Same guard as the conversation rows: opening during generation is refused by the
                // ViewModel, and closing History anyway looked like a dead tap that lost the search.
                Button(
                    enabled = !generating && !unsaved,
                    onClick = { onSelect(hit.conversationId, hit.messageId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "${if (hit.role == "USER") "You" else "Answer"}: $label",
                        fontSize = 13.sp,
                        maxLines = 4,
                    )
                }
            }
        } else {
            items(conversations, key = { it.id }) { conversation ->
                androidx.wear.compose.material3.OutlinedButton(
                    enabled = (!generating || conversation.id == currentId) && !unsaved,
                    onClick = { onSelect(conversation.id, null) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(conversation.title) }
            }
        }
    }
}

private const val SEARCH_DEBOUNCE_MS = 250L

/** ponytail: 40 hits; a watch list never shows more without another query. */
private const val SEARCH_LIMIT = 40

/**
 * Mark a clip sensitive so Android 13+ does not show a content preview of a conversation answer on
 * the clipboard overlay. This is a preview/visibility control, NOT encryption: the clipboard still
 * holds plaintext and any app the user pastes into can read it.
 *
 * `EXTRA_IS_SENSITIVE` is `public static final String` — a compile-time constant that is inlined to
 * a literal — so referencing it cannot throw on minSdk 30. The InlinedApi warning is cosmetic and
 * is suppressed here, at the one place that uses it, with this reason rather than project-wide.
 */
@Suppress("InlinedApi")
private fun markClipSensitive(clip: android.content.ClipData) {
    clip.description.extras =
        android.os.PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
}

/**
 * Stable LazyColumn keys for the reader, one per (messageId, block) pair, in render order.
 *
 * Extracted from the composable so the ordering contract can be pinned by a JVM test before the
 * list rendering is moved (Task 4.1). The anchor logic indexes into this flat list, so if the order
 * or the per-message numbering changes, anchors silently point at the wrong block.
 */
internal fun historyKeysFor(renderedHistory: List<Pair<Long, com.nirowear.renderer.ContentBlock>>): List<String> {
    val counts = mutableMapOf<Long, Int>()
    return renderedHistory.map { (id, _) ->
        val block = counts[id] ?: 0
        counts[id] = block + 1
        "$id:$block"
    }
}
