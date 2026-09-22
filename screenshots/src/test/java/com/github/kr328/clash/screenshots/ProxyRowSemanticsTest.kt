package com.github.kr328.clash.screenshots

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.kr328.clash.design.compose.component.ProxyRow
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProxyRowSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun row(title: String, selected: Boolean) {
        compose.setContent {
            ClodClashTheme {
                ProxyRow(
                    title = title,
                    subtitle = "",
                    delay = 0,
                    marksOnly = false,
                    selected = selected,
                    favorite = false,
                    onClick = {},
                    onToggleFavorite = {},
                )
            }
        }
    }

    @Test
    fun selectedServerIsAnnouncedAsSelected() {
        row("Нидерланды 01", selected = true)

        compose.onNodeWithText("Нидерланды 01", substring = true)
            .assertIsSelected()
    }

    @Test
    fun otherServerIsAnnouncedAsNotSelected() {
        row("Германия 02", selected = false)

        compose.onNodeWithText("Германия 02", substring = true)
            .assertIsNotSelected()
    }
}
