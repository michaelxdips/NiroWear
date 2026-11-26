package com.nirowear.settings

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * The pairing listener lives only while the setup screen is composed. close() must refuse
 * new connections fast AND kill the worker thread — even with a client holding a connection
 * open in readHead.
 */
class PairingCloseTest {
    private fun workers(): List<Thread> =
        Thread.getAllStackTraces().keys.filter { it.name == "nirowear-pairing" && it.isAlive }

    @Test
    fun closeRefusesConnectionsAndKillsWorkerWithHeldClient() {
        val received = AtomicReference<PairingPayload?>(null)
        val pairing = PairingServer("127.0.0.1", ttlMillis = 120_000) { received.set(it) }
        pairing.start()
        val started = System.currentTimeMillis()
        while (workers().isEmpty() && System.currentTimeMillis() - started < 2000) Thread.sleep(50)
        assertTrue("pairing worker never started", workers().isNotEmpty())

        // A held half-open client: the worker accepts it and blocks in readHead.
        // Give it a timeout so a lingered readHead cannot park the connection thread forever;
        // the thread dies with the socket when close() runs.
        val held = Socket("127.0.0.1", pairing.port).apply { soTimeout = 3000 }
        Thread.sleep(300)
        pairing.close()

        val connectStart = System.currentTimeMillis()
        var refused = false
        try {
            Socket("127.0.0.1", pairing.port).close()
        } catch (_: IOException) {
            refused = true
        } catch (_: SocketTimeoutException) {
            refused = false
        }
        val connectMs = System.currentTimeMillis() - connectStart
        assertTrue("connect() succeeded after close()", refused)
        assertTrue("refused connect took ${connectMs}ms, bound is 2000ms", connectMs < 2000)

        val stopStart = System.currentTimeMillis()
        while (workers().isNotEmpty() && System.currentTimeMillis() - stopStart < 5000) Thread.sleep(50)
        val lingering = workers()
        held.runCatching { close() }
        if (lingering.isNotEmpty()) fail("pairing worker still alive 5s after close(); join it in close()")
    }

    @Test
    fun concurrentCloseDoesNotLeaveAcceptedClientsUnclosed() {
        repeat(8) {
            val pairing = PairingServer("127.0.0.1", ttlMillis = 120_000) {}
            pairing.start()
            val port = pairing.port
            val connector =
                Thread {
                    repeat(5) {
                        runCatching { Socket("127.0.0.1", port).use { s -> s.soTimeout = 500; s.getInputStream().read() } }
                    }
                }.apply { isDaemon = true; start() }
            Thread.sleep(15)
            pairing.close()
            connector.join(3000)
            val deadline = System.currentTimeMillis() + 2000
            while (
                System.currentTimeMillis() < deadline &&
                (
                    workers().isNotEmpty() ||
                        Thread.getAllStackTraces().keys.any { it.name == "nirowear-pairing-request" && it.isAlive }
                )
            ) {
                Thread.sleep(25)
            }
            val lingeringWorkers = workers()
            val lingeringReqs =
                Thread.getAllStackTraces().keys.filter { it.name == "nirowear-pairing-request" && it.isAlive }
            assertTrue("pairing worker survived close", lingeringWorkers.isEmpty())
            assertTrue("pairing request handler survived close", lingeringReqs.isEmpty())
        }
    }
}
