package com.nirowear.settings

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.wear.remote.interactions.RemoteActivityHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume

/**
 * Opens [url] in the paired phone's browser over the Wear OS Bluetooth bridge without a companion app.
 */
internal suspend fun openUrlOnPairedPhone(
    context: Context,
    url: String,
): Result<Unit> =
    try {
        val intent =
            Intent(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(url.toUri())
        val executor = Dispatchers.IO.asExecutor()
        val future = RemoteActivityHelper(context.applicationContext, executor).startRemoteActivity(intent, null)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { future.cancel(true) }
            future.addListener(
                {
                    if (!continuation.isActive) return@addListener
                    val outcome =
                        try {
                            future.get()
                            Result.success(Unit)
                        } catch (error: ExecutionException) {
                            Result.failure(error.cause ?: error)
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                    continuation.resume(outcome)
                },
                executor,
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }
