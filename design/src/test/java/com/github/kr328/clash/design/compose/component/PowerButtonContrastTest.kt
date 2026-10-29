package com.github.kr328.clash.design.compose.component

import androidx.compose.ui.graphics.Color
import com.github.kr328.clash.design.compose.theme.DarkOnStatus
import com.github.kr328.clash.design.compose.theme.DarkPrimary
import com.github.kr328.clash.design.compose.theme.DarkStatusConnected
import com.github.kr328.clash.design.compose.theme.DarkStatusConnecting
import com.github.kr328.clash.design.compose.theme.DarkSurfaceContainer
import com.github.kr328.clash.design.compose.theme.LightOnStatus
import com.github.kr328.clash.design.compose.theme.LightPrimary
import com.github.kr328.clash.design.compose.theme.LightStatusConnected
import com.github.kr328.clash.design.compose.theme.LightStatusConnecting
import com.github.kr328.clash.design.compose.theme.LightSurfaceContainer
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerButtonContrastTest {
    private val themes = listOf(
        Triple(false, LightOnStatus, listOf(LightStatusConnected, LightStatusConnecting)),
        Triple(true, DarkOnStatus, listOf(DarkStatusConnected, DarkStatusConnecting)),
    )

    // Выключено: фиолетовый значок на светлом диске без градиента
    private val off = listOf(LightPrimary to LightSurfaceContainer, DarkPrimary to DarkSurfaceContainer)

    @Test
    fun captionAtFaceCenterReadsAsSmallText() {
        for ((dark, on, accents) in themes) {
            for (accent in accents) {
                val ratio = contrast(on, powerFaceCenter(accent, dark))

                assertTrue("dark=$dark accent=$accent center ratio=$ratio", ratio >= 4.5)
            }
        }
    }

    @Test
    fun offIconReadsAsGraphic() {
        for ((icon, face) in off) {
            val ratio = contrast(icon, face)

            assertTrue("icon=$icon face=$face ratio=$ratio", ratio >= 3.0)
        }
    }

    @Test
    fun iconAtFaceEdgeReadsAsGraphic() {
        for ((_, on, accents) in themes) {
            for (accent in accents) {
                val ratio = contrast(on, accent)

                assertTrue("accent=$accent edge ratio=$ratio", ratio >= 3.0)
            }
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)

        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val x = v.toDouble()

            return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        }

        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }
}
