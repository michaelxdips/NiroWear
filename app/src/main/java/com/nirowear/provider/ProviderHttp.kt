package com.nirowear.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

internal fun providerHttpClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

/** HTTP/SSE framing only. Adapters own JSON, native lifecycle and completion. No logging/replay. */
internal class ProviderHttp(
    private val client: OkHttpClient,
    /**
     * How long the stream may make NO PROGRESS before it is treated as dead. Injectable so the bound
     * can be tested in milliseconds instead of two minutes.
     */
    private val streamIdleTimeoutMs: Long = 120_000L,
) {
    // Native completion repeats accepted text; JSON escaping can use six wire bytes per unit.
    private val maxFrameBytes = 6L * MAX_RESPONSE_CHARS + 256 * 1024

    /**
     * Plain JSON GET.
     *
     * @param timeoutMs optional deadline for THIS call. Side requests that sit on the critical path of a
     *   send (Anthropic's per-turn model metadata) get their own deadline, and it is the SMALLER of this
     *   value and the client's configured read timeout: a caller that built a client with a tighter
     *   budget (a test, or a future low-latency configuration) keeps it, while production is bounded
     *   instead of inheriting the 90 s stream read timeout — a hanging metadata endpoint would otherwise
     *   delay the first token by a minute and a half with the UI already showing "generating".
     */
    suspend fun fetch(
        request: Request,
        timeoutMs: Long? = null,
    ): String =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            if (timeoutMs != null) {
                val clientRead = client.readTimeoutMillis.toLong()
                val effective = if (clientRead > 0) minOf(timeoutMs, clientRead) else timeoutMs
                call.timeout().timeout(effective, TimeUnit.MILLISECONDS)
            }
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) {
                            continuation.resumeWith(
                                Result.failure(SafeProviderException(networkError(e))),
                            )
                        }
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val result =
                            runCatching {
                                response.use {
                                    if (!it.isSuccessful) throw SafeProviderException(httpError(it.code))
                                    val source = it.body.source()
                                    if (source.request(
                                            1024 * 1024 + 1L,
                                        )
                                    ) {
                                        throw SafeProviderException(ProviderError.PROTOCOL)
                                    }
                                    source.readUtf8()
                                }
                            }
                        if (continuation.isActive) continuation.resumeWith(result)
                    }
                },
            )
        }

    @OptIn(DelicateCoroutinesApi::class)
    fun stream(
        request: Request,
        decode: (String) -> List<StreamEvent>,
        /**
         * Optional mapping for a non-2xx response, given the status and a bounded body. Some providers
         * report an authentication failure as HTTP 400 with the reason in the body (Gemini's
         * `API_KEY_INVALID`), and the status alone cannot tell that apart from a malformed request —
         * the old code threw on the status before the body was ever read, so a wrong key read as
         * "Provider rejected request. Check configuration."
         */
        decodeError: ((Int, String) -> ProviderError?)? = null,
    ): Flow<StreamEvent> =
        callbackFlow {
            val call = client.newCall(request)
            var reader: Job? = null
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (!call.isCanceled()) trySend(StreamEvent.Failure(networkError(e)))
                        close()
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        if (!isActive) {
                            response.close()
                            return
                        }
                        // ATOMIC enters finally even if cancellation precedes dispatch.
                        reader =
                            launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                                try {
                                    response.use {
                                        if (!it.isSuccessful) {
                                            val mapped =
                                                decodeError?.let { hook ->
                                                    runCatching {
                                                        hook(it.code, it.peekBody(8 * 1024).string())
                                                    }.getOrNull()
                                                }
                                            send(StreamEvent.Failure(mapped ?: httpError(it.code)))
                                            return@launch
                                        }
                                        if (!it
                                                .header(
                                                    "Content-Type",
                                                ).orEmpty()
                                                .startsWith("text/event-stream", ignoreCase = true)
                                        ) {
                                            // A 200 that is not an event stream never reached the model.
                                            // An HTML body means a WEB PAGE answered — captive portal,
                                            // hotel Wi-Fi, or a base URL pointing at a site rather than
                                            // the endpoint. Reporting that as "stream interrupted or
                                            // unsupported" described a stream that never existed and
                                            // promised a partial answer that was never there.
                                            send(StreamEvent.Failure(portalOrProtocol(it)))
                                            return@launch
                                        }
                                        val source = it.body.source()
                                        val frame = StringBuilder()
                                        var visibleChars = 0
                                        // Progress clock. readTimeout is per read and resets on every
                                        // byte, so a server that drips a keepalive every 89 s holds the
                                        // socket — and the watch radio — forever. It is reset only when a
                                        // frame DECODES to at least one event: `: ping` comments and
                                        // `{"type":"ping"}` data frames both carry no content, and
                                        // resetting on the raw bytes made this bound dead.
                                        var lastFrameAt = System.currentTimeMillis()

                                        suspend fun dispatch(): Boolean {
                                            if (frame.isEmpty()) return false
                                            val events = decode(frame.toString().removeSuffix("\n"))
                                            frame.clear()
                                            if (events.isNotEmpty()) lastFrameAt = System.currentTimeMillis()
                                            for (event in events) {
                                                if (event is StreamEvent.TextDelta) {
                                                    if (event.text.length > MAX_RESPONSE_CHARS - visibleChars) {
                                                        send(StreamEvent.Failure(ProviderError.RESPONSE_TOO_LARGE))
                                                        return true
                                                    }
                                                    visibleChars += event.text.length
                                                }
                                                send(event)
                                            }
                                            // Terminal proof. EOF alone is NOT success, so the read loop stops only on an event
                                            // that proves the protocol ended. Truncated is such a proof: the
                                            // provider sent its output-cap reason, which ends the answer. It
                                            // was left out here, so a truncated stream kept looping to EOF and
                                            // then appended Failure(PROTOCOL) — a cut answer reported as a
                                            // broken stream, which is why the adapters could not disclose
                                            // truncation.
                                            return events.any { event ->
                                                event is StreamEvent.Failure ||
                                                    event == StreamEvent.Completed ||
                                                    event == StreamEvent.Truncated
                                            }
                                        }
                                        // Idle deadline; see the progress clock above.
                                        while (isActive && !source.exhausted()) {
                                            val line =
                                                try {
                                                    source.readUtf8LineStrict(maxFrameBytes)
                                                } catch (_: EOFException) {
                                                    throw SafeProviderException(
                                                        if (source.buffer.size > maxFrameBytes) {
                                                            ProviderError.RESPONSE_TOO_LARGE
                                                        } else {
                                                            ProviderError.PROTOCOL
                                                        },
                                                    )
                                                }
                                            if (line.isEmpty()) {
                                                // The blank line terminator itself is not progress;
                                                // dispatch() decides, because only it knows whether the
                                                // frame carried anything.
                                                if (dispatch()) return@launch
                                            } else if (line.startsWith("data:")) {
                                                frame.append(line.removePrefix("data:").removePrefix(" ")).append('\n')
                                                if (frame.length >
                                                    maxFrameBytes
                                                ) {
                                                    throw SafeProviderException(ProviderError.RESPONSE_TOO_LARGE)
                                                }
                                            }
                                            // Two different bounds, deliberately:
                                            //
                                            // - `readTimeout` (90 s) already catches a dead socket, because it
                                            //   fires when one read returns nothing.
                                            // - `lastFrameAt` moves only when a frame decodes to an EVENT. A
                                            //   server that drips `: ping` comments, or data frames whose
                                            //   payload the adapter ignores, keeps the socket and the watch
                                            //   radio alive while the answer makes no progress; that is the
                                            //   case this deadline exists to stop.
                                            if (System.currentTimeMillis() - lastFrameAt > streamIdleTimeoutMs) {
                                                throw SafeProviderException(ProviderError.TIMEOUT)
                                            }
                                        }
                                        if (!dispatch()) send(StreamEvent.Failure(ProviderError.PROTOCOL))
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    if (isActive && !call.isCanceled()) {
                                        send(
                                            StreamEvent.Failure(
                                                if (error is SafeProviderException) {
                                                    error.error
                                                } else if (error is IOException) {
                                                    networkError(error)
                                                } else {
                                                    ProviderError.PROTOCOL
                                                },
                                            ),
                                        )
                                    }
                                } finally {
                                    response.close()
                                    close()
                                }
                            }
                    }
                },
            )
            awaitClose {
                call.cancel()
                reader?.cancel()
            }
        }
}

private fun networkError(error: IOException) =
    if (error is SocketTimeoutException) ProviderError.TIMEOUT else ProviderError.NETWORK_UNAVAILABLE

/**
 * A 200 whose body is not an event stream. An HTML body means a WEB PAGE answered instead of the API
 * — captive portal, hotel Wi-Fi, or a base URL pointing at a site rather than the endpoint — so the
 * request never reached the model and the connection is the thing to check. Anything else stays a
 * protocol problem: the provider answered, it just did not speak SSE.
 */
internal fun portalOrProtocol(response: Response): ProviderError {
    val head =
        runCatching { response.peekBody(1024).string() }.getOrDefault("")
    return if (head.trimStart().startsWith("<")) ProviderError.NETWORK_UNAVAILABLE else ProviderError.PROTOCOL
}
