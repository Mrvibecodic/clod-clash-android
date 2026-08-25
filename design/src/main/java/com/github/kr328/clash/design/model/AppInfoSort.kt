package com.github.kr328.clash.design.model

import com.github.kr328.clash.design.util.localeCollator

enum class AppInfoSort(comparator: Comparator<AppInfo>) : Comparator<AppInfo> by comparator {
    Label(Comparator { a, b -> localeCollator().compare(a.label, b.label) }),
    PackageName(compareBy(AppInfo::packageName)),
    InstallTime(compareBy(AppInfo::installTime)),
    UpdateTime(compareBy(AppInfo::updateDate)),
}
