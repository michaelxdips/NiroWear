package com.nirowear.settings

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.SystemClock

/**
 * Makes the watch look like an NDEF tag carrying the current pairing URL, so a phone tap opens the
 * setup page instead of a URL being typed on a watch keyboard.
 *
 * The service holds no protocol logic: [NdefTag] builds the bytes and [NdefApduResponder] answers the
 * APDUs, both are JVM-tested. What lives here is only the Android binding and the session gate — an
 * unarmed bridge answers 6A82, so the emulated tag exists for the 120 s pairing window and is dead
 * the rest of the time.
 *
 * `nfc.hce` is optional in the manifest and this class is never required for the app to run: the
 * Xiaomi Watch 2 reports `android.hardware.nfc.hce`, a device without it simply never binds this
 * service and the QR and manual URL paths still work.
 */
class NfcPairingService : HostApduService() {
    private var responder: NdefApduResponder? = null
    private var responderUrl: String? = null

    override fun processCommandApdu(
        commandApdu: ByteArray?,
        extras: Bundle?,
    ): ByteArray {
        val url = NfcPairing.bridge.url(SystemClock.elapsedRealtime()) ?: return NdefTag.NOT_FOUND
        if (commandApdu == null) return NdefTag.WRONG_LENGTH
        // Rebuilt only when the armed URL changes; the selected file has to survive between the
        // reader's SELECT and its READs, so a per-command responder would answer 6A82 forever.
        val active =
            responder?.takeIf { responderUrl == url }
                ?: NdefApduResponder(NdefTag.message(url)).also {
                    responder = it
                    responderUrl = url
                }
        return active.respond(commandApdu)
    }

    /** A new tap starts from no selected file. */
    override fun onDeactivated(reason: Int) {
        responder?.reset()
    }
}
