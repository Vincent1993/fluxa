package com.fluxa.app

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.fluxa.app.navigation.*
import com.fluxa.app.ui.feedlist.SearchScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real navigation + production delivery effects. Synthetic validated source, not OS notification QA. */
class NotificationRoutingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun currentOutgoingAndRestoredEntriesDeliverExactScopeOnceWithoutReplacingCurrentList() {
        lateinit var nav: NavHostController
        var source by mutableStateOf<String?>(null)
        var generation by mutableIntStateOf(0)
        var restored: Bundle? = null
        val applied = mutableListOf<Pair<String, SearchScope>>()
        var consumed = 0
        compose.setContent {
            key(generation) {
                val controller = rememberNavController()
                nav = controller
                remember(controller) { controller.restoreState(restored); true }
                NotificationNavigation(controller, source)
                NavHost(controller, startDestination = Routes.FeedList) {
                    composable(Routes.FeedList) { entry ->
                        NotificationScopeDelivery(controller, entry, source,
                            applySource = { id ->
                                assertSame(entry, controller.currentBackStackEntry)
                                applied += entry.id to SearchScope(source = id)
                            }, onConsumed = { consumed++; source = null })
                    }
                    composable(Routes.Login) {}
                    composable(Routes.GitHub) {}
                    composable(Routes.Article, arguments = listOf(navArgument("id") { type = NavType.StringType })) {}
                }
            }
        }
        compose.waitForIdle()
        val knownSource = "direct-rss:owned-notification"
        var expected = 0
        fun deliver() {
            compose.runOnIdle { source = knownSource }
            compose.waitForIdle()
            expected++
            compose.runOnIdle {
                assertEquals(expected, consumed)
                assertEquals(expected, applied.size)
                assertEquals(Routes.FeedList, nav.currentDestination?.route)
                assertEquals(nav.currentBackStackEntry!!.id, applied.last().first)
                assertEquals(SearchScope(source = knownSource), applied.last().second)
                assertNull(source)
            }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(expected, consumed) }
        }
        var originalEntry = ""
        compose.runOnIdle { originalEntry = nav.currentBackStackEntry!!.id }
        deliver()
        compose.runOnIdle { assertEquals(originalEntry, nav.currentBackStackEntry!!.id) }
        // Same source can be delivered again after acknowledgement (warm, no entry replacement).
        deliver()
        compose.runOnIdle { assertEquals(originalEntry, nav.currentBackStackEntry!!.id) }
        for (route in listOf(Routes.article("owned"), Routes.GitHub, Routes.Login)) {
            compose.runOnIdle { nav.navigate(route) }
            compose.waitForIdle()
            deliver()
            compose.runOnIdle { assertNull(nav.previousBackStackEntry) }
        }
        // Recreate a controller with a saved reader stack and an already pending validated source.
        compose.runOnIdle {
            nav.navigate(Routes.article("restored-owned"))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            restored = nav.saveState()
            source = knownSource
            generation++
        }
        compose.waitForIdle()
        expected++
        compose.runOnIdle {
            assertEquals(expected, consumed)
            assertEquals(expected, applied.size)
            assertEquals(Routes.FeedList, nav.currentDestination?.route)
            assertEquals(nav.currentBackStackEntry!!.id, applied.last().first)
            assertEquals(SearchScope(source = knownSource), applied.last().second)
            assertNull(source)
            assertNull(nav.previousBackStackEntry)
        }
    }
}
