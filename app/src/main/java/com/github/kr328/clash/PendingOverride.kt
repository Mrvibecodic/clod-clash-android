package com.github.kr328.clash

import com.github.kr328.clash.core.model.ConfigurationOverride

// Несохранённые правки переопределения держатся в процессе: целиком в Bundle они не помещаются
// (большой список hosts не проходит через Binder), а признак «правки есть» лежит в Bundle
internal object PendingOverride {
    const val KEY = "pending_override"

    var value: ConfigurationOverride? = null
}
