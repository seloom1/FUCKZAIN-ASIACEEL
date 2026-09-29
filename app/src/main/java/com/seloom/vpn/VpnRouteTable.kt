package com.seloom.vpn

/** An IPv4 route to add to Android's VPN routing table. */
data class VpnIpv4Route(val address: String, val prefix: Int)

/**
 * Builds Android VPN routes for full-tunnel mode, optionally leaving private
 * and link-local LAN networks on the underlying network. Bypass mode deliberately
 * uses a CIDR complement instead of installing a 0.0.0.0/0 default route.
 */
object VpnRouteTable {
    private const val MAX_IPV4 = 0xFFFF_FFFFL

    private val lanRanges = listOf(
        cidrRange(0x0A00_0000L, 8),  // 10.0.0.0/8
        cidrRange(0xA9FE_0000L, 16), // 169.254.0.0/16 link-local
        cidrRange(0xAC10_0000L, 12), // 172.16.0.0/12
        cidrRange(0xC0A8_0000L, 16)  // 192.168.0.0/16
    ).sortedBy { it.first }

    fun routes(bypassLan: Boolean): List<VpnIpv4Route> {
        if (!bypassLan) return listOf(VpnIpv4Route("0.0.0.0", 0))

        val result = ArrayList<VpnIpv4Route>()
        var cursor = 0L
        for ((first, last) in lanRanges) {
            if (cursor < first) appendRange(cursor, first - 1, result)
            cursor = last + 1
        }
        if (cursor <= MAX_IPV4) appendRange(cursor, MAX_IPV4, result)
        return result
    }

    private fun cidrRange(network: Long, prefix: Int): Pair<Long, Long> {
        val size = 1L shl (32 - prefix)
        return network to (network + size - 1)
    }

    private fun appendRange(start: Long, end: Long, routes: MutableList<VpnIpv4Route>) {
        var current = start
        while (current <= end) {
            val alignmentBits = if (current == 0L) 32 else
                java.lang.Long.numberOfTrailingZeros(current).coerceAtMost(32)
            val remainingBits = 63 - java.lang.Long.numberOfLeadingZeros(end - current + 1)
            val blockBits = minOf(alignmentBits, remainingBits)
            routes += VpnIpv4Route(toIpv4(current), 32 - blockBits)
            current += 1L shl blockBits
        }
    }

    private fun toIpv4(value: Long): String = listOf(24, 16, 8, 0)
        .joinToString(".") { shift -> ((value ushr shift) and 0xFF).toString() }
}
