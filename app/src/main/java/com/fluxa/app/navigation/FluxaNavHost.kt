package com.fluxa.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.fluxa.app.data.local.SecureTokenStore
import com.fluxa.app.ui.article.ArticleRoute
import com.fluxa.app.ui.feedlist.FeedListRoute
import com.fluxa.app.ui.login.LoginRoute

/** Cold SEND must not leave Login (or an unrelated reader) as the Back destination. */
internal fun NavHostController.openSavedArticle(id: String) {
    navigate(Routes.FeedList) {
        popUpTo(graph.id) { inclusive = false }
        launchSingleTop = true
    }
    navigate(Routes.article(id))
}

@Composable
fun FluxaNavHost(transfer: com.fluxa.app.ui.feedlist.LocalTransferViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(),
    notificationSource: String? = null, onNotificationConsumed: () -> Unit = {}) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val store = remember(context) { SecureTokenStore(context) }
    val scope = rememberCoroutineScope()
    val start by produceState<String?>(null) {
        value = if (store.getNewsBlurSession() != null || transfer.hasLocalContent()) Routes.FeedList else Routes.Login
    }
    val destination = start ?: return
    NotificationNavigation(nav, notificationSource)
    NavHost(nav, startDestination = destination) {
        composable(Routes.Login) {
            LoginRoute(onOpenCache = { nav.navigate(Routes.FeedList) },
                onOpenGitHub = { nav.navigate(Routes.GitHub) },
                onLoginSuccess = {
                    nav.navigate(Routes.FeedList) {
                        popUpTo(Routes.Login) { inclusive = true }
                        launchSingleTop = true
                    }
                })
        }
        composable(Routes.FeedList) { entry ->
            val viewModel: com.fluxa.app.ui.feedlist.FeedListViewModel =
                androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(entry)
            NotificationScopeDelivery(nav, entry, notificationSource,
                applySource = { viewModel.applyScope(com.fluxa.app.ui.feedlist.SearchScope(source = it)) },
                onConsumed = onNotificationConsumed)
            FeedListRoute(viewModel = viewModel, onOpenArticle = { nav.navigate(Routes.article(it)) },
                onLogin = { nav.navigate(Routes.Login) { launchSingleTop = true } },
                onOpenGitHub = { nav.navigate(Routes.GitHub) }, onOpml = transfer::openImport)
        }
        composable(Routes.GitHub) {
            com.fluxa.app.ui.github.GitHubRoute(onBack = { nav.popBackStack() })
        }
        composable(Routes.Article, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            // Queue turns select inside this entry; Back still pops once to the list.
            ArticleRoute(onBack = { nav.popBackStack() })
        }
    }
    com.fluxa.app.ui.feedlist.LocalTransferSheet(transfer) { id ->
        scope.launch {
            if (transfer.prepareReader(id)) {
                transfer.close()
                nav.openSavedArticle(id)
            }
        }
    }
}
