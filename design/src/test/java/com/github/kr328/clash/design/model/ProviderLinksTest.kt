package com.github.kr328.clash.design.model

import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.model.PanelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Блок ссылок провайдера в настройках строится только из того, что прислала
 * панель. Проверяется здесь ровно это: состав, порядок и то, что без
 * заголовков блока нет вовсе.
 */
class ProviderLinksTest {
    @Test
    fun `без подписки ссылок нет`() {
        assertTrue(providerLinks(null).isEmpty())
    }

    @Test
    fun `панель не прислала ни одной ссылки — блока нет`() {
        // Заголовок с именем провайдера и пустотой под ним человек читает
        // как поломку приложения, а не как «у провайдера нет бота».
        assertTrue(providerLinks(PanelInfo(title = "Провайдер")).isEmpty())
    }

    @Test
    fun `порядок тот же, что на ПК`() {
        val links = providerLinks(
            PanelInfo(
                portalUrl = "https://provider.example/cabinet",
                supportUrl = "https://t.me/provider_support",
                botUrl = "tg://resolve?domain=provider_bot",
                monitorUrl = "https://status.provider.example",
                guideUrl = "https://provider.example/help",
            ),
        )

        assertEquals(
            listOf(
                R.string.clod_portal,
                R.string.clod_support,
                R.string.clod_bot,
                R.string.clod_monitor,
                R.string.clod_guide,
            ),
            links.map { it.title },
        )
    }

    @Test
    fun `непришедшие ссылки выпадают, остальные не сдвигаются`() {
        // Провайдер вправе иметь бота и не иметь мониторинга — дыры в списке
        // быть не должно, а порядок оставшихся не меняется.
        val links = providerLinks(
            PanelInfo(
                botUrl = "tg://resolve?domain=provider_bot",
                guideUrl = "https://provider.example/help",
            ),
        )

        assertEquals(listOf(R.string.clod_bot, R.string.clod_guide), links.map { it.title })
        assertEquals("tg://resolve?domain=provider_bot", links[0].url)
    }

    @Test
    fun `пробелы вместо адреса ссылкой не считаются`() {
        assertTrue(providerLinks(PanelInfo(monitorUrl = "   ")).isEmpty())
    }
}
