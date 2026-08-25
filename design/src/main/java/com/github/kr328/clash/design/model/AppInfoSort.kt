package com.github.kr328.clash.design.model

import java.text.Collator

enum class AppInfoSort(comparator: Comparator<AppInfo>) : Comparator<AppInfo> by comparator {
    Label(compareBy(Collator.getInstance(), AppInfo::label)),
    PackageName(compareBy(AppInfo::packageName)),
    InstallTime(compareBy(AppInfo::installTime)),
    UpdateTime(compareBy(AppInfo::updateDate)),
}
