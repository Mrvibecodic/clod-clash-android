package com.github.kr328.clash.service.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemResolverPickTest {
    private data class Net(val notVpn: Boolean, val resolvers: List<String>)

    private fun pick(vararg nets: Net): List<String> =
        pickSystemResolvers(nets.toList(), { it.notVpn }, { it.resolvers })

    @Test
    fun `пустой набор сетей даёт пустой список`() {
        assertEquals(emptyList<String>(), pick())
    }

    @Test
    fun `единственная сеть без vpn отдаёт свои резолверы`() {
        assertEquals(listOf("1.1.1.1:53"), pick(Net(true, listOf("1.1.1.1:53"))))
    }

    @Test
    fun `единственная сеть с vpn пропускается`() {
        assertEquals(emptyList<String>(), pick(Net(false, listOf("10.0.0.1:53"))))
    }

    @Test
    fun `сеть чужого vpn не перебивает сеть без vpn`() {
        assertEquals(
            listOf("8.8.8.8:53"),
            pick(Net(false, listOf("10.0.0.1:53")), Net(true, listOf("8.8.8.8:53"))),
        )
    }

    @Test
    fun `сеть без резолверов не останавливает перебор`() {
        assertEquals(
            listOf("8.8.8.8:53"),
            pick(Net(true, emptyList()), Net(true, listOf("8.8.8.8:53"))),
        )
    }

    @Test
    fun `из двух годных сетей берётся первая`() {
        assertEquals(
            listOf("1.1.1.1:53"),
            pick(Net(true, listOf("1.1.1.1:53")), Net(true, listOf("8.8.8.8:53"))),
        )
    }

    @Test
    fun `набор только из vpn даёт пустой список`() {
        assertEquals(
            emptyList<String>(),
            pick(Net(false, listOf("10.0.0.1:53")), Net(false, listOf("10.0.0.2:53"))),
        )
    }
}
