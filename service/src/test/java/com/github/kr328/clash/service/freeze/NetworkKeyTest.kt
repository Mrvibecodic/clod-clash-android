package com.github.kr328.clash.service.freeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkKeyTest {
    private fun wifi(gateway: String, dns: List<String> = listOf("192.168.0.1"), dhcp: String = "192.168.0.1") =
        NetworkKey.Seen(
            transport = "wifi",
            gateways = listOf(gateway),
            subnets = listOf("192.168.0.0/24"),
            dhcp = dhcp,
            dns = dns,
            domains = "lan",
        )

    @Test
    fun `сеть узнаётся по шлюзу, подсети, DHCP и DNS, порядок DNS не важен`() {
        val home = NetworkKey.keyOf(wifi("192.168.0.1", dns = listOf("1.1.1.1", "8.8.8.8")))

        assertEquals(16, home?.length)
        assertEquals(home, NetworkKey.keyOf(wifi("192.168.0.1", dns = listOf("8.8.8.8", "1.1.1.1"))))
        assertNotEquals(home, NetworkKey.keyOf(wifi("192.168.1.1", dns = listOf("1.1.1.1", "8.8.8.8"))))
        assertNotEquals(home, NetworkKey.keyOf(wifi("192.168.0.1", dns = listOf("9.9.9.9"))))
        assertNotEquals(home, NetworkKey.keyOf(wifi("192.168.0.1", dns = listOf("1.1.1.1", "8.8.8.8"), dhcp = "")))
    }

    @Test
    fun `мобильная сеть — код оператора, кабель и Wi-Fi с тем же роутером различаются`() {
        val mts = NetworkKey.keyOf(NetworkKey.Seen(transport = "cellular", operator = "25001"))

        assertEquals(mts, NetworkKey.keyOf(NetworkKey.Seen(transport = "cellular", operator = "25001")))
        assertNotEquals(mts, NetworkKey.keyOf(NetworkKey.Seen(transport = "cellular", operator = "25002")))
        assertNull(NetworkKey.keyOf(NetworkKey.Seen(transport = "cellular", operator = "")))

        assertNotEquals(NetworkKey.keyOf(wifi("192.168.0.1")), NetworkKey.keyOf(wifi("192.168.0.1").copy(transport = "ethernet")))
    }

    @Test
    fun `без шлюза и без сети ключа нет`() {
        assertNull(NetworkKey.keyOf(null))
        assertNull(NetworkKey.keyOf(wifi("192.168.0.1").copy(gateways = emptyList())))
    }

    @Test
    fun `адреса IPv6 в счёт только в сети без IPv4`() {
        assertEquals(listOf("192.168.0.1"), NetworkKey.preferIpv4(listOf("fe80::1%wlan0", "192.168.0.1")))
        assertEquals(listOf("192.168.0.1"), NetworkKey.preferIpv4(listOf("192.168.0.1", "2001:db8::53")))
        assertEquals(listOf("fe80::1%wlan0"), NetworkKey.preferIpv4(listOf("fe80::1%wlan0")))
        assertEquals(emptyList<String>(), NetworkKey.preferIpv4(emptyList()))
    }

    @Test
    fun `подсеть считается по длине префикса`() {
        assertEquals("192.168.1.0/24", NetworkKey.subnetOf(byteArrayOf(192.toByte(), 168.toByte(), 1, 37), 24))
        assertEquals("10.0.0.0/8", NetworkKey.subnetOf(byteArrayOf(10, 20, 30, 40), 8))
        assertEquals("172.16.4.0/22", NetworkKey.subnetOf(byteArrayOf(172.toByte(), 16, 7, 200.toByte()), 22))
    }
}
