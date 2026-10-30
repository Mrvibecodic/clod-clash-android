package com.github.kr328.clash.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.screen.MainAction
import com.github.kr328.clash.design.compose.screen.MainScreen
import com.github.kr328.clash.design.compose.screen.MainScreenState
import com.github.kr328.clash.design.compose.screen.MainTab
import com.github.kr328.clash.design.compose.screen.ProxyGroupState
import com.github.kr328.clash.design.compose.screen.ServersState
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

private const val LONG_GROUP = "Первая группа с длинным названием"
private const val SHORT_GROUP = "Все"
private const val LAST_GROUP = "Третья группа"

private fun groupsNamed(vararg names: String) = names.map { name ->
    ProxyGroupState(
        name = name,
        now = "Node",
        selectable = true,
        proxies = listOf(Proxy("Node", "Node", "Shadowsocks", "Shadowsocks", 50, false)),
    )
}

private val shortGroups = groupsNamed(SHORT_GROUP, "Видео", "Игры")
private val longGroups = groupsNamed(LONG_GROUP, "Вторая группа с длинным названием", LAST_GROUP)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainTabSwipeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val chosen = mutableListOf<MainTab>()

    private val groupsChosen = mutableListOf<Int>()

    private var state by mutableStateOf(MainScreenState())

    private fun show(tab: MainTab, groups: List<ProxyGroupState> = shortGroups) {
        state = MainScreenState(selectedTab = tab, servers = ServersState(groups = groups))

        compose.setContent {
            ClodClashTheme {
                MainScreen(
                    state = state,
                    onAction = { action ->
                        when (action) {
                            is MainAction.SelectTab -> {
                                chosen += action.tab
                                state = state.copy(selectedTab = action.tab)
                            }
                            is MainAction.SelectGroup -> groupsChosen += action.index
                            else -> Unit
                        }
                    },
                )
            }
        }
    }

    private fun tabButton(res: Int) =
        compose.onNode(hasText(compose.activity.getString(res)) and hasClickAction())

    @Test
    fun swipeLeftOpensNextTab() {
        show(MainTab.Home)

        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(listOf(MainTab.Servers), chosen)
    }

    @Test
    fun swipeRightOpensPreviousTab() {
        show(MainTab.More)

        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertEquals(listOf(MainTab.Subscriptions), chosen)
    }

    @Test
    fun swipeStopsAtHome() {
        show(MainTab.Home)

        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertEquals(emptyList<MainTab>(), chosen)
    }

    @Test
    fun swipeStopsAtMore() {
        show(MainTab.More)

        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(emptyList<MainTab>(), chosen)
    }

    private fun swipeStrip(group: String) {
        compose.onNode(hasScrollAction() and hasAnyChild(hasText(group, substring = true))).performTouchInput {
            swipeLeft(startX = right - 1f, endX = left + 1f)
        }
        compose.waitForIdle()
    }

    @Test
    fun swipeOnFittingGroupStripDoesNotChangeTab() {
        show(MainTab.Servers)

        swipeStrip(SHORT_GROUP)

        assertEquals(emptyList<MainTab>(), chosen)
    }

    @Test
    fun swipePastEndOfGroupStripDoesNotChangeTab() {
        show(MainTab.Servers, longGroups)

        repeat(3) { swipeStrip(LONG_GROUP) }

        assertEquals(emptyList<MainTab>(), chosen)
    }

    @Test
    fun groupTapRightAfterFlingToStripEndWorks() {
        show(MainTab.Servers, longGroups)
        compose.mainClock.autoAdvance = false

        compose.onNode(hasScrollAction() and hasAnyChild(hasText(LONG_GROUP, substring = true))).performTouchInput {
            swipeLeft(startX = right - 1f, endX = left + 1f, durationMillis = 50)
        }
        repeat(30) { compose.mainClock.advanceTimeByFrame() }
        compose.onNode(hasText(LAST_GROUP, substring = true) and hasClickAction()).performClick()
        repeat(5) { compose.mainClock.advanceTimeByFrame() }

        assertEquals(listOf(2), groupsChosen)
    }

    @Test
    fun farTabButtonSelectsOnlyThatTab() {
        show(MainTab.Home)

        tabButton(R.string.clod_tab_more).performClick()
        compose.waitForIdle()

        assertEquals(listOf(MainTab.More), chosen)
    }

    @Test
    fun backFromMoreGoesStraightHome() {
        show(MainTab.More)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertEquals(listOf(MainTab.Home), chosen)
    }

    private fun shown(res: Int): Boolean =
        compose.onAllNodes(hasContentDescription(compose.activity.getString(res)) or hasText(compose.activity.getString(res)))
            .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun farTabButtonPassesThroughTabsBetween() {
        show(MainTab.Home)
        compose.mainClock.autoAdvance = false

        tabButton(R.string.clod_tab_more).performClick()

        var serversSeen = false
        repeat(120) {
            compose.mainClock.advanceTimeByFrame()
            serversSeen = serversSeen || shown(R.string.clod_test_delays)
        }

        assertTrue(serversSeen)
        assertEquals(listOf(MainTab.More), chosen)
        assertEquals(MainTab.More, displayedTab())
    }

    private fun displayedTab(): MainTab {
        val pager = compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(HorizontalScrollAxisRange))
            .fetchSemanticsNode()

        return MainTab.entries[(pager.config[HorizontalScrollAxisRange].value() / pager.size.width).roundToInt()]
    }

    @Test
    fun backWhileHoldingPagerKeepsTabAndPageTogether() {
        show(MainTab.More)

        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(width * 0.3f, 0f))
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()

        assertEquals(state.selectedTab, displayedTab())
    }

    @Test
    fun touchDuringFarTabTravelKeepsTabAndPageTogether() {
        show(MainTab.Home)
        compose.mainClock.autoAdvance = false

        tabButton(R.string.clod_tab_more).performClick()
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
        compose.onRoot().performTouchInput {
            down(center)
            up()
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertEquals(state.selectedTab, displayedTab())
    }

    @Test
    fun secondTapDuringScrollWins() {
        show(MainTab.Home)
        compose.mainClock.autoAdvance = false

        tabButton(R.string.clod_tab_more).performClick()
        compose.mainClock.advanceTimeBy(100)
        tabButton(R.string.clod_tab_servers).performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertEquals(listOf(MainTab.More, MainTab.Servers), chosen)
        assertEquals(MainTab.Servers, state.selectedTab)
    }
}
