package com.nirowear.settings

/**
 * The NDEF payload and the Type 2 Tag file layout that Android's host card emulation exposes to a
 * phone, as pure byte functions.
 *
 * Everything here is deliberately free of `android.*` so the exact bytes the watch puts on the
 * contactless link can be asserted in a JVM test in CI. The `HostApduService` next door only routes
 * [NdefApduResponder.respond] and holds no logic of its own.
 *
 * Why a Type 2 Tag projection at all: a phone does not read a custom AID, it reads NDEF. Android's
 * NFC stack selects the NFC Forum NDEF application, reads the capability container, then reads the
 * NDEF file; only then does the URL in the record get dispatched to a browser. A service that
 * answered SELECT and READ with the raw NDEF message but skipped the CC/NLEN framing would look fine
 * in a unit test and be invisible to every real handset.
 */
internal object NdefTag {
    /** NFC Forum NDEF application AID: the one a reader selects first. */
    val NDEF_AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)

    /** Project-chosen AID, kept so an explicit SELECT for this app also works. */
    val PROJECT_AID = byteArrayOf(0xF0.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)

    /** Capability container file id, then the NDEF file id, per NFC Forum T2T. */
    val CC_FILE = byteArrayOf(0xE1.toByte(), 0x03)
    val NDEF_FILE = byteArrayOf(0xE1.toByte(), 0x04)

    val OK = byteArrayOf(0x90.toByte(), 0x00)
    val NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
    val WRONG_PARAMETERS = byteArrayOf(0x6B, 0x00)
    val INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
    val WRONG_LENGTH = byteArrayOf(0x67, 0x00)

    private const val TENF_WELL_KNOWN = 0x01
    private const val RTD_URI = 0x55

    /** RTD URI prefix code for `https://` (URI Record Type Definition, table 3). */
    private const val URI_PREFIX_HTTPS = 0x04

    /**
     * Maximum NDEF message size advertised in the capability container.
     *
     * 256 bytes is the whole short-record space a Type 2 Tag file can hold and is far above what the
     * capability URL needs (measured 45 bytes for `https://192.168.8.209:43210/<22 chars>/`).
     */
    const val MAX_NDEF_BYTES = 256

    /**
     * One NDEF message holding exactly one URI record.
     *
     * `https://` is stripped into the URI record's prefix byte, which is why the payload is 7 bytes
     * shorter than the URL. Header 0xD1 = MB|ME|SR with TNF 0x01 (well known); a long record (no SR)
     * would need a four-byte length and no reader on a phone accepts that for a URL this short.
     */
    fun message(url: String): ByteArray {
        require(url.startsWith("https://")) { "only https capability URLs are advertised" }
        val payload =
            byteArrayOf(URI_PREFIX_HTTPS.toByte()) +
                (url.removePrefix("https://")).toByteArray(Charsets.US_ASCII)
        // A short record holds at most 255 payload bytes, and the message must also fit the file
        // size the capability container advertises (MAX_NDEF_BYTES minus the two NLEN bytes);
        // otherwise a reader stops at the advertised size and sees a truncated record.
        require(payload.size <= 255) { "NDEF payload does not fit a short record" }
        val record =
            byteArrayOf(
                0xD1.toByte(), // MB | ME | SR, TNF = 0x01
                0x01, // type length
                payload.size.toByte(),
                RTD_URI.toByte(),
            ) + payload
        require(record.size <= MAX_NDEF_BYTES - 2) { "NDEF message does not fit the advertised file" }
        return record
    }

    /** The 15-byte capability container a reader parses before it will read [ndefFile]. */
    fun capabilityContainer(): ByteArray =
        byteArrayOf(
            0x00,
            0x0F, // CCLEN = 15
            0x20, // mapping version 2.0
            0x00,
            0x3B, // MLe = 59 bytes per read
            0x00,
            0x04, // MLc = 4 bytes per write
            0x04,
            0x06, // NDEF file control TLV (T, L)
            NDEF_FILE[0],
            NDEF_FILE[1],
            (MAX_NDEF_BYTES shr 8).toByte(),
            (MAX_NDEF_BYTES and 0xFF).toByte(),
            0x00, // read access: free
            // Write access 0xFF = read-only. The file answers 6D00 to UPDATE BINARY, so advertising
            // it as writable only invites a writer app to try and fail.
            0xFF.toByte(),
        )

    /**
     * The NDEF file: a two-byte NLEN followed by the message. The NLEN is part of the file, not of
     * the message — a reader asks for bytes 0..1, learns the length, then asks for the rest.
     */
    fun ndefFile(message: ByteArray): ByteArray =
        byteArrayOf((message.size shr 8).toByte(), (message.size and 0xFF).toByte()) + message
}

/**
 * Answers ISO 7816 APDUs the way a Type 2 Tag holding one NDEF message would, for one tap.
 *
 * Stateful on purpose: a reader selects the NDEF file once and then issues several READ BINARY
 * commands, so the selected file has to survive between commands. [reset] is what a new tap calls.
 */
internal class NdefApduResponder(
    private val message: ByteArray,
    private val container: ByteArray = NdefTag.capabilityContainer(),
    private val file: ByteArray = NdefTag.ndefFile(message),
) {
    private var selected: ByteArray? = null

    fun reset() {
        selected = null
    }

    fun respond(apdu: ByteArray): ByteArray {
        if (apdu.size < 4) return NdefTag.WRONG_LENGTH
        return when (apdu[1]) {
            INS_SELECT -> select(apdu)
            INS_READ_BINARY -> readBinary(apdu)
            else -> NdefTag.INS_NOT_SUPPORTED
        }
    }

    /** File identity comes from the last two data bytes, so SELECT by AID and by EF share this. */
    private fun select(apdu: ByteArray): ByteArray {
        // A case-1 SELECT (`00 A4 04 00`) has no Lc byte at all. Indexing apdu[4] behind a size<4
        // guard threw ArrayIndexOutOfBoundsException out of the exported HCE service, which is an
        // app kill any reader in range could trigger.
        if (apdu.size < 5) return NdefTag.WRONG_LENGTH
        var length = apdu[4].toInt() and 0xFF
        var start = 5
        // Lc 0x00 in a short APDU means "extended length follows": two more bytes carry it.
        if (length == 0 && apdu.size >= 7) {
            length = ((apdu[5].toInt() and 0xFF) shl 8) or (apdu[6].toInt() and 0xFF)
            start = 7
        }
        if (length == 0 || apdu.size < start + length) return NdefTag.WRONG_LENGTH
        val data = apdu.copyOfRange(start, start + length)
        // P1 0x04 = select by AID, P1 0x00 = select by file id. Anything else is not a SELECT a tag
        // answers, and pretending otherwise would hide a reader we do not actually support.
        if ((apdu[2].toInt() and 0xFF) == SELECT_BY_AID) {
            val known = data.contentEquals(NdefTag.NDEF_AID) || data.contentEquals(NdefTag.PROJECT_AID)
            if (!known) return NdefTag.NOT_FOUND
            selected = null
            return NdefTag.OK
        }
        if ((apdu[2].toInt() and 0xFF) != SELECT_BY_FILE) return NdefTag.NOT_FOUND
        selected =
            when {
                data.contentEquals(NdefTag.CC_FILE) -> container
                data.contentEquals(NdefTag.NDEF_FILE) -> file
                else -> return NdefTag.NOT_FOUND
            }
        return NdefTag.OK
    }

    private fun readBinary(apdu: ByteArray): ByteArray {
        val target = selected ?: return NdefTag.NOT_FOUND
        if (apdu.size < 5) return NdefTag.WRONG_LENGTH
        val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
        // Le 0x00 means 256 bytes, not zero. Treating it as zero returned an empty answer and the
        // reader stalled waiting for the NDEF body.
        val wanted = (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it }
        if (offset > target.size) return NdefTag.WRONG_PARAMETERS
        val end = minOf(target.size, offset + wanted)
        return target.copyOfRange(offset, end) + NdefTag.OK
    }

    private companion object {
        const val INS_SELECT = 0xA4.toByte()
        const val INS_READ_BINARY = 0xB0.toByte()
        const val SELECT_BY_AID = 0x04
        const val SELECT_BY_FILE = 0x00
    }
}

/**
 * Whether the watch is currently advertising an NDEF URL, and until when.
 *
 * The service is manifest-declared, so the system can bind it at any time — including long after the
 * pairing screen closed. Arming is therefore time-boxed here as well as in the screen: an expired
 * session answers 6A82 to every reader, so a stale tap can never reopen a page that no longer exists.
 *
 * The caller supplies the clock reading, deliberately: the pairing server runs on
 * `SystemClock.elapsedRealtime()` (monotonic) and comparing that against a wall clock would let a
 * time sync open or close the tag at the wrong moment.
 */
internal class NfcPairingBridge {
    @Volatile private var armed: Pair<String, Long>? = null

    fun arm(
        url: String,
        nowMillis: Long,
        ttlMillis: Long = 120_000,
    ) {
        armed = url to (nowMillis + ttlMillis)
    }

    fun disarm() {
        armed = null
    }

    fun url(nowMillis: Long): String? = armed?.takeIf { nowMillis < it.second }?.first
}

/** Process-wide bridge the HCE service reads; the pairing screen arms and disarms it. */
internal object NfcPairing {
    val bridge = NfcPairingBridge()
}
