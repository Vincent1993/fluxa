package com.fluxa.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.fluxa.app.navigation.Routes
import com.fluxa.app.navigation.openSavedArticle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Compose navigation graph, without starting auth/reader or opening any application database. */
class SavedArticleNavigationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun coldAndWarmSharesPrepareLibraryBackStackAndPreserveEncodedIdentity() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Routes.Login) {
                composable(Routes.Login) {}
                composable(Routes.FeedList) {}
                composable(Routes.Article, arguments = listOf(navArgument("id") { type = NavType.StringType })) {}
            }
        }
        val first = "direct-rss:page:fixture-one"
        compose.runOnIdle {
            nav.openSavedArticle(first)
            assertEquals(first, nav.currentBackStackEntry!!.arguments!!.getString("id"))
            assertEquals(Routes.FeedList, nav.previousBackStackEntry!!.destination.route)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            nav.openSavedArticle("direct-rss:page:fixture-two")
            assertEquals(Routes.FeedList, nav.previousBackStackEntry!!.destination.route)
            assertTrue(nav.popBackStack())
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Routes.FeedList, nav.currentBackStackEntry!!.destination.route) }
    }
}
