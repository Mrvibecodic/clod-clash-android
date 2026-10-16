package com.github.kr328.clash.design.model

import android.content.Context
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.design.R

fun ChannelStage.text(context: Context): String = when (this) {
    ChannelStage.Checking -> context.getString(R.string.clod_chan_stage_checking)
    is ChannelStage.Retry -> context.getString(R.string.clod_chan_stage_retry, attempt, total)
    ChannelStage.Plain -> context.getString(R.string.clod_chan_stage_plain)
}

fun FetchStatus.text(context: Context): String? = when (action) {
    FetchStatus.Action.FetchConfiguration ->
        context.getString(R.string.format_fetching_configuration, args.firstOrNull().orEmpty())

    FetchStatus.Action.FetchProviders ->
        context.getString(R.string.format_fetching_provider, args.firstOrNull().orEmpty())

    FetchStatus.Action.ProviderFailed ->
        context.getString(R.string.clod_provider_failed, args.firstOrNull().orEmpty())

    FetchStatus.Action.Verifying -> context.getString(R.string.verifying)
    FetchStatus.Action.SubscriptionInfo -> null
}
