package com.github.kr328.clash.service.clash.module

internal fun shouldProbe(now: Long, lastProbeAt: Long, minGapMs: Long, force: Boolean): Boolean =
    force || now - lastProbeAt >= minGapMs

// Сеть, на которую уйти, когда текущая потеряла проверку системой: лучшая по
// очереди сетей, если это другая и уже проверенная. Нужна, потому что система,
// уводя трафик (Wi-Fi без интернета → SIM), сообщает о новой сети раньше, чем о
// потере проверки у прежней, — в тот момент новая ещё не лучшая. На
// непроверенную не уходим: SIM, на миг потерявшая проверку, не меняется на
// Wi-Fi без интернета
internal fun <N> networkAfterValidationLost(current: N?, lost: N, preferred: N?, preferredValidated: Boolean): N? =
    preferred.takeIf { lost == current && it != null && it != lost && preferredValidated }
