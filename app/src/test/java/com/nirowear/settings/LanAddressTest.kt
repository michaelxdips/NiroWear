package com.nirowear.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing listener must never bind or advertise the mobile-data interface.
 *
 * Enumeration order is unspecified and carrier NAT space passes isPrivateAddress, so picking an
 * rmnet address would make the screen say "Same Wi-Fi only" while the socket was reachable over
 * cellular.
 */
class LanAddressTest {
    @Test
    fun mobileInterfacesAreRejected() {
        for (name in listOf("rmnet_data0", "rmnet0", "ccmni0", "pdp0", "wwan0", "dummy0", "tun0", "ppp0")) {
            assertTrue("$name must not be advertised", isMobileInterface(name))
        }
    }

    @Test
    fun wirelessAndEthernetInterfacesAreAllowed() {
        for (name in listOf("wlan0", "eth0", "ap0", "swlan0")) {
            assertFalse("$name is a local network interface", isMobileInterface(name))
        }
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertTrue(isMobileInterface("RMNET_DATA1"))
    }

    @Test
    fun rfc1918DetectionMatchesTheSubnetsThatMatter() {
        assertTrue(isPrivateAddress("10.1.2.3"))
        assertTrue(isPrivateAddress("192.168.8.2"))
        assertTrue(isPrivateAddress("172.16.0.1"))
        assertTrue(isPrivateAddress("172.20.1.1"))
        assertTrue(isPrivateAddress("172.31.255.254"))
        assertTrue(isPrivateAddress("169.254.1.1"))
        assertFalse("172.15 is public", isPrivateAddress("172.15.255.255"))
        assertFalse("172.32 is public", isPrivateAddress("172.32.0.1"))
        assertFalse("leading zero is rejected", isPrivateAddress("172.016.0.1"))
        assertFalse("three-digit octet is rejected", isPrivateAddress("172.160.0.1"))
        assertFalse(isPrivateAddress("8.8.8.8"))
        assertFalse(isPrivateAddress(""))
    }
}