package com.github.kr328.clash.design.util

import android.animation.ValueAnimator
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.view.ActivityBarLayout
import com.github.kr328.clash.design.view.ObservableScrollView

private class AppBarElevationController(
    private val activityBar: ActivityBarLayout
) {
    private var animator: ValueAnimator? = null

    var elevated: Boolean = false
        set(value) {
            if (field == value)
                return

            field = value

            animator?.end()

            animator = if (value) {
                ValueAnimator.ofFloat(
                    activityBar.elevation,
                    activityBar.context.getPixels(R.dimen.toolbar_elevation).toFloat()
                )
            } else {
                ValueAnimator.ofFloat(
                    activityBar.elevation,
                    0f
                )
            }.apply {
                addUpdateListener {
                    activityBar.elevation = it.animatedValue as Float
                }

                start()
            }
        }
}

/**
 * Тень под шапкой, пока содержимое отлистано вниз.
 *
 * Вариант для `RecyclerView` убран вместе с последним списком на нём:
 * все списки теперь на `LazyColumn`, и единственный оставшийся экран
 * на разметке — «APK повреждён» — прокручивается `ObservableScrollView`.
 */
fun ObservableScrollView.bindAppBarElevation(activityBar: ActivityBarLayout) {
    val controller = AppBarElevationController(activityBar)

    addOnScrollChangedListener { view, _, _, _, _ ->
        controller.elevated = !view.isTop
    }
}