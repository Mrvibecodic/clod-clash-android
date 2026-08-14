package com.github.kr328.clash.design.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.model.PanelInfo

/**
 * Одна ссылка провайдера в списке настроек.
 *
 * Ссылка приходит заголовком подписки и открывается снаружи приложения,
 * поэтому вместе с адресом хранится и то, чем её подписать: разные ссылки
 * ведут в очень разные места, и «Открыть» на всех пяти строках не сказало бы
 * человеку ничего.
 */
data class ProviderLink(
    @StringRes val title: Int,
    @DrawableRes val icon: Int,
    val url: String,
)

/**
 * Ссылки провайдера в том порядке, в каком их показывают настройки.
 *
 * Порядок тот же, что на ПК: кабинет, поддержка, бот, мониторинг, инструкция.
 * Человеку, которому провайдер объяснил дорогу по одному клиенту, второй
 * должен показывать то же самое и на том же месте.
 *
 * Пустая строка означает «заголовка не было»: панель шлёт только те ссылки,
 * которые у провайдера есть, и выдумывать остальные приложению нечем. Не
 * пришло ни одной — список пуст, и блока в настройках нет вовсе.
 *
 * Отдельной функцией, а не выражением внутри разметки: это единственная
 * логика во всём блоке, и проверять её обычным тестом дешевле, чем глазами
 * на телефоне с настоящей подпиской.
 */
fun providerLinks(panel: PanelInfo?): List<ProviderLink> {
    if (panel == null) return emptyList()

    return listOf(
        ProviderLink(R.string.clod_portal, R.drawable.ic_baseline_account, panel.portalUrl),
        ProviderLink(R.string.clod_support, R.drawable.ic_baseline_chat, panel.supportUrl),
        ProviderLink(R.string.clod_bot, R.drawable.ic_baseline_smart_toy, panel.botUrl),
        ProviderLink(R.string.clod_monitor, R.drawable.ic_baseline_monitor_heart, panel.monitorUrl),
        ProviderLink(R.string.clod_guide, R.drawable.ic_baseline_menu_book, panel.guideUrl),
    ).filter { it.url.isNotBlank() }
}
