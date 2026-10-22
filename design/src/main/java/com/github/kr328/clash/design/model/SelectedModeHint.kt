package com.github.kr328.clash.design.model

enum class SelectedModeHint { Empty, SubscriptionExclude, ProviderAll }

// Подсказка под «Разрешить выбранные» — то же решение, что принимает туннель
// (tunApps). null — отмечено установленное приложение или подписка пускает свои
// включения: они помечены в списке сами.
fun selectedModeHint(
    nothingSelected: Boolean,
    subIncluded: Boolean,
    subExcluded: Boolean,
    providerAllApps: Boolean,
): SelectedModeHint? = when {
    !nothingSelected || subIncluded -> null
    subExcluded -> SelectedModeHint.SubscriptionExclude
    providerAllApps -> SelectedModeHint.ProviderAll
    else -> SelectedModeHint.Empty
}
