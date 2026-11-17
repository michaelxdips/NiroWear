package com.nirowear.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The exact bytes the watch puts on the contactless link, plus the session gate.
 *
 * A phone tap cannot be automated here (no handset on the LAN with a reader), so what is proven is
 * the half that can be: an NDEF message that reconstructs the capability URL, and the APDU exchange
 * a Type 2 Tag reader performs, byte for byte. The tap itself stays an explicitly unverified step.
 */
class NfcPairingTest {
    private val url = "https://192.168.8.209:43210/AbCdEfGhIjKlMnOpQrStUv/"

    private fun selectAid(aid: ByteArray): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid + byteArrayOf(0x00)

    private fun selectFile(id: ByteArray): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02) + id

    private fun readBinary(
        offset: Int,
        le: Int,
    ): ByteArray =
        byteArrayOf(0x00, 0xB0.toByte(), (offset shr 8).toByte(), (offset and 0xFF).toByte(), le.toByte())

    private fun unsigned(byte: Byte): Int = byte.toInt() and 0xFF

    @Test
    fun ndefMessageIsAnExactUriRecord() {
        val short = "https://192.168.1.2:5000/aa/"
        val rest = "192.168.1.2:5000/aa/"
        val expected =
            byteArrayOf(0xD1.toByte(), 0x01, (rest.length + 1).toByte(), 0x55, 0x04) +
                rest.toByteArray(Charsets.US_ASCII)
        assertArrayEquals(expected, NdefTag.message(short))
        // A short record carries its payload length in one byte; getting that wrong is how a
        // generated tag ends up readable on a bench and invisible to a phone. 0x15 = 1 prefix
        // byte + 20 ASCII characters, counted by hand and asserted literally.
        assertEquals(0x15, unsigned(NdefTag.message(short)[2]))
    }

    @Test
    fun ndefMessageReconstructsTheCapabilityUrl() {
        val message = NdefTag.message(url)
        assertEquals(0xD1, unsigned(message[0]))
        assertEquals(1, unsigned(message[1]))
        assertEquals(message.size - 4, unsigned(message[2]))
        assertEquals(0x55, unsigned(message[3]))
        assertEquals(0x04, unsigned(message[4]))
        assertEquals(
            "https://" + String(message, 5, message.size - 5, Charsets.US_ASCII),
            url,
        )
    }

    @Test
    fun type2TagExchangeReturnsTheMessageToAReader() {
        val message = NdefTag.message(url)
        val responder = NdefApduResponder(message)

        assertArrayEquals(NdefTag.OK, responder.respond(selectAid(NdefTag.NDEF_AID)))
        assertArrayEquals(NdefTag.OK, responder.respond(selectFile(NdefTag.CC_FILE)))
        assertArrayEquals(
            "capability container",
            NdefTag.capabilityContainer() + NdefTag.OK,
            responder.respond(readBinary(0, 15)),
        )
        assertArrayEquals(NdefTag.OK, responder.respond(selectFile(NdefTag.NDEF_FILE)))

        val nlen = responder.respond(readBinary(0, 2))
        assertEquals("NLEN is followed by 90 00", 4, nlen.size)
        assertArrayEquals(NdefTag.OK, nlen.copyOfRange(2, 4))
        val length = (unsigned(nlen[0]) shl 8) or unsigned(nlen[1])
        assertEquals(message.size, length)

        assertArrayEquals(
            "NDEF body at offset 2",
            message + NdefTag.OK,
            responder.respond(readBinary(2, length)),
        )
    }

    @Test
    fun leZeroMeansTwoHundredFiftySixBytes() {
        val message = NdefTag.message(url)
        val responder = NdefApduResponder(message)
        responder.respond(selectAid(NdefTag.NDEF_AID))
        responder.respond(selectFile(NdefTag.NDEF_FILE))
        assertArrayEquals(message + NdefTag.OK, responder.respond(readBinary(2, 0)))
    }

    @Test
    fun unknownReaderCommandsAreRefused() {
        val responder = NdefApduResponder(NdefTag.message(url))
        assertArrayEquals(
            "read before select",
            NdefTag.NOT_FOUND,
            responder.respond(readBinary(0, 15)),
        )
        assertArrayEquals(
            "unknown AID",
            NdefTag.NOT_FOUND,
            responder.respond(selectAid(byteArrayOf(0x11, 0x22, 0x33))),
        )
        responder.respond(selectAid(NdefTag.NDEF_AID))
        responder.respond(selectFile(NdefTag.NDEF_FILE))
        assertArrayEquals(
            "unsupported instruction",
            NdefTag.INS_NOT_SUPPORTED,
            responder.respond(byteArrayOf(0x00, 0xD6.toByte(), 0x00, 0x00, 0x01, 0x00)),
        )
        assertArrayEquals(
            "offset past the file",
            NdefTag.WRONG_PARAMETERS,
            responder.respond(readBinary(NdefTag.MAX_NDEF_BYTES + 8, 15)),
        )
    }

    @Test
    fun sessionGateExpiresAtTheDeadline() {
        var now = 1_000L
        val bridge = NfcPairingBridge { now }
        bridge.arm(url, 120_000)
        assertEquals(url, bridge.url())

        now += 119_999
        assertNotNull("still armed one millisecond before the deadline", bridge.url())

        now += 1
        assertNull("a stale tap must not reopen a dead session", bridge.url())
    }

    @Test
    fun disarmIsImmediate() {
        val bridge = NfcPairingBridge { 5_000L }
        bridge.arm(url, 120_000)
        bridge.disarm()
        assertNull(bridge.url())
    }
}
