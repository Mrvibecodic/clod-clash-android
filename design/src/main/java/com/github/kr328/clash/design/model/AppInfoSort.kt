package com.github.kr328.clash.design.model

import java.text.Collator

private val collators = object : ThreadLocal<Collator>() {
    override fun initialValue(): Collator = Collator.getInstance()
}

enum class AppInfoSort(comparator: Comparator<AppInfo>) : Comparator<AppInfo> by comparator {
    Label(Comparator { a, b -> collators.get()!!.compare(a.label, b.label) }),
    PackageName(compareBy(AppInfo::packageName)),
    InstallTime(compareBy(AppInfo::installTime)),
    UpdateTime(compareBy(AppInfo::updateDate)),
}
