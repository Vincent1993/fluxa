package com.fluxa.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.fluxa.app.data.local.SecureTokenStore
import com.fluxa.app.ui.article.ArticleRoute
import com.fluxa.app.ui.feedlist.FeedListRoute
import com.fluxa.app.ui.login.LoginRoute

@Composable
fun FluxaNavHost() {
    val nav = rememberNavController()
    val context = LocalContext.current
    val store = remember(context) { SecureTokenStore(context) }
    val start = remember { if (store.getNewsBlurSession() != null) Routes.FeedList else Routes.Login }
    NavHost(nav, startDestination = start) {
        composable(Routes.Login) {
            LoginRoute(onOpenCache = { nav.navigate(Routes.FeedList) },
                onLoginSuccess = {
                    nav.navigate(Routes.FeedList) {
                        popUpTo(Routes.Login) { inclusive = true }
                        launchSingleTop = true
                    }
                })
        }
        composable(Routes.FeedList) {
            FeedListRoute(onOpenArticle = { nav.navigate(Routes.article(it)) },
                onLogin = { nav.navigate(Routes.Login) { launchSingleTop = true } })
        }
        composable(Routes.Article, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            ArticleRoute(onBack = { nav.popBackStack() })
        }
    }
}
