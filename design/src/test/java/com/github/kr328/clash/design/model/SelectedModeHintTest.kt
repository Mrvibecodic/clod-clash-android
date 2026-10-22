package com.github.kr328.clash.design.model

import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.util.onlySelfThroughTunnel
import com.github.kr328.clash.service.util.tunApps
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectedModeHintTest {
    private val self = "app.self"
    private val installed = mapOf(self to 1, "chat" to 11, "bank" to 10, "browser" to 12)
    private val uidOf: (String) -> Int? = { installed[it] }

    // Как экран собирает своё состояние: включения подписки — только установленные,
    // отметки удалённых приложений выбором не считаются
    private fun hint(selected: Set<String>, include: Set<String>, exclude: Set<String>, provider: Boolean) =
        selectedModeHint(
            nothingSelected = selected.none { uidOf(it) != null },
            subIncluded = (include.filter { uidOf(it) != null }.toSet() - exclude).isNotEmpty(),
            subExcluded = exclude.isNotEmpty(),
            providerAllApps = provider,
        )

    @Test
    fun `подсказка говорит то же, что делает туннель`() {
        val selections = listOf(emptySet(), setOf("gone"), setOf("browser"))
        val includes = listOf(emptySet(), setOf("chat"), setOf("absent"))
        val excludes = listOf(emptySet(), setOf("bank"), setOf("absent"))

        for (selected in selections) for (include in includes) for (exclude in excludes) for (provider in listOf(false, true)) {
            val apps = tunApps(AccessControlMode.AcceptSelected, selected, include, exclude, self, uidOf, provider)
            val onlySelf = onlySelfThroughTunnel(apps.allowed.keys, self)
            val case = "$selected / $include / $exclude / $provider"

            when (hint(selected, include, exclude, provider)) {
                SelectedModeHint.Empty -> assertEquals(case, true, onlySelf)
                SelectedModeHint.ProviderAll -> assertEquals(case, true, apps.allowed.isEmpty() && apps.disallowed.isEmpty())
                SelectedModeHint.SubscriptionExclude -> assertEquals(case, true, apps.allowed.isEmpty())
                null -> assertEquals(case, false, onlySelf)
            }
        }
    }

    @Test
    fun `режим провайдера — только без отметок и без списков подписки`() {
        assertEquals(SelectedModeHint.ProviderAll, hint(emptySet(), emptySet(), emptySet(), true))
        assertEquals(SelectedModeHint.ProviderAll, hint(setOf("gone"), setOf("absent"), emptySet(), true))
        assertEquals(SelectedModeHint.SubscriptionExclude, hint(emptySet(), emptySet(), setOf("bank"), true))
        assertEquals(null, hint(emptySet(), setOf("chat"), emptySet(), true))
        assertEquals(null, hint(setOf("browser"), emptySet(), emptySet(), true))
        assertEquals(SelectedModeHint.Empty, hint(emptySet(), emptySet(), emptySet(), false))
    }
}
