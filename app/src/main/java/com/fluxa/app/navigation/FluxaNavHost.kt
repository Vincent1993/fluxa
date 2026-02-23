package com.fluxa.app.navigation

import android.content.Context
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideIntoContainer
import androidx.compose.animation.slideOutOfContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navArgument
import androidx.navigation.compose.rememberNavController
import com.fluxa.app.data.api.OAuthManager
import com.fluxa.app.data.local.SecureTokenStore
import com.fluxa.app.ui.article.ArticleRoute
import com.fluxa.app.ui.feedlist.FeedListRoute
import com.fluxa.app.ui.login.LoginRoute

@Composable
fun FluxaNavHost(
    oauthCode: String?,
    consumeOAuthCode: () -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val tokenStore = rememberTokenStore(context)

    val startDestination = if (tokenStore.isLoggedIn()) Routes.FeedList else Routes.Login

    NavHost(navController = navController, startDestination = startDestination) {
        composable(
            route = Routes.Login,
            enterTransition = { fadeIn() },
            exitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left) + fadeOut()
            }
        ) {
            val oauthManager = OAuthManager()
            LoginRoute(
                oauthCode = oauthCode,
                onSignInClick = { context.startActivity(oauthManager.createLoginIntent()) },
                onLoginSuccess = {
                    consumeOAuthCode()
                    navController.navigate(Routes.FeedList) {
                        popUpTo(Routes.Login) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.FeedList,
            enterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left) + fadeIn()
            },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right) + fadeOut()
            }
        ) {
            FeedListRoute(onOpenArticle = { id -> navController.navigate(Routes.article(id)) })
        }

        composable(
            route = Routes.Article,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
            enterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left) + fadeIn()
            },
            popEnterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right) + fadeIn()
            }
        ) {
            ArticleRoute()
        }
    }
}

@Composable
private fun rememberTokenStore(context: Context): SecureTokenStore = SecureTokenStore(context)
