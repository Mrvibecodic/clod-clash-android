package com.github.kr328.clash.design.model

// Приложения идут мимо VPN: через туннель пускается только сам клиент. При
// работающей службе — по туннелю, который она подняла (нет туннеля — режим без
// VPN), и только если нынешние настройки дают то же: иначе служба уже
// пересобирает туннель. Без службы — по тому, что она соберёт при включении.
// Будущий состав туннеля спрашивается, только когда ответ зависит от него.
inline fun appsBypassVpn(running: Boolean, tunnelOnlySelf: Boolean?, vpnMode: Boolean, plannedOnlySelf: () -> Boolean): Boolean =
    (if (running) tunnelOnlySelf == true else vpnMode) && plannedOnlySelf()

// Лист о приложениях — только при работающей службе и пока приложения идут
// мимо. Открывается поводом (Главная стала видна, служба запустилась) или
// когда приложения только что ушли мимо VPN; закрывается сам, когда причины нет.
fun appsPromptAfter(prompt: Boolean, before: Boolean?, bypass: Boolean, running: Boolean, opportunity: Boolean): Boolean =
    running && bypass && (prompt || opportunity || before == false)
