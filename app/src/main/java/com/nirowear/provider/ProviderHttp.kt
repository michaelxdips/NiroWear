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
) {
    // Native completion repeats accepted text; JSON escaping can use six wire bytes per unit.
    private val maxFrameBytes = 6L * MAX_RESPONSE_CHARS + 256 * 1024

    suspend fun fetch(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
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
                                            send(StreamEvent.Failure(httpError(it.code)))
                                            return@launch
                                        }
                                        if (!it
                                                .header(
                                                    "Content-Type",
                                                ).orEmpty()
                                                .startsWith("text/event-stream", ignoreCase = true)
                                        ) {
                                            send(StreamEvent.Failure(ProviderError.PROTOCOL))
                                            return@launch
                                        }
                                        val source = it.body.source()
                                        val frame = StringBuilder()
                                        var visibleChars = 0

                                        suspend fun dispatch(): Boolean {
                                            if (frame.isEmpty()) return false
                                            val events = decode(frame.toString().removeSuffix("\n"))
                                            frame.clear()
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
                                            return events.any { event ->
                                                event is StreamEvent.Failure ||
                                                    event == StreamEvent.Completed
                                            }
                                        }
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
                                                if (dispatch()) return@launch
                                            } else if (line.startsWith("data:")) {
                                                frame.append(line.removePrefix("data:").removePrefix(" ")).append('\n')
                                                if (frame.length >
                                                    maxFrameBytes
                                                ) {
                                                    throw SafeProviderException(ProviderError.RESPONSE_TOO_LARGE)
                                                }
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
