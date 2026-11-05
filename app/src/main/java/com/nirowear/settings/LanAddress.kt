package com.nirowear.settings

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * LAN address to advertise on the pairing page, or null when the watch has none.
 *
 * `NetworkInterface` enumeration instead of `ConnectivityManager` on purpose: it needs no permission,
 * and the manifest stays at `INTERNET` only. Link-local (169.254.x.x) and loopback addresses are
 * useless to a phone browser, so they are filtered out rather than printed.
 */
internal fun lanAddress(): String? =
    runCatching {
        NetworkInterface
            .getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint && !isMobileInterface(it.name) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("169.254.") && !it.startsWith("127.") }
    }.getOrNull()

/**
 * Interface names that carry mobile data rather than the local network.
 *
 * Enumeration order is unspecified and every up interface is considered, so on a watch with cellular
 * up and Wi-Fi down this could return the carrier address. The listener then bound the mobile
 * interface while the screen said "Same Wi-Fi only", and carrier NAT space (10/8, 172.16/12) passes
 * [isPrivateAddress]. No address is the honest answer there — the screen already tells the user to
 * turn Wi-Fi on. Point-to-point interfaces are excluded for the same reason; this list covers stacks
 * that do not set IFF_POINTOPOINT.
 */
private val MOBILE_INTERFACE_PREFIXES = listOf("rmnet", "ccmni", "pdp", "wwan", "dummy", "tun", "ppp")

internal fun isMobileInterface(name: String): Boolean =
    MOBILE_INTERFACE_PREFIXES.any { name.lowercase().startsWith(it) }

/** True only for RFC1918 / link-local space. A public address earns an extra warning, not a block. */
internal fun isPrivateAddress(host: String): Boolean =
    host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("172.16.") ||
        host.startsWith("172.17.") || host.startsWith("172.18.") || host.startsWith("172.19.") ||
        Regex("^172\\.(2[0-9]|3[01])\\.").containsMatchIn(host) || host.startsWith("169.254.")
