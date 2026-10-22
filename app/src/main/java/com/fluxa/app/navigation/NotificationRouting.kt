package com.fluxa.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

/** Input is a known, non-archived local source, validated by MainActivity. */
@Composable
internal fun NotificationNavigation(nav: NavHostController, source: String?) {
    val current by nav.currentBackStackEntryAsState()
    LaunchedEffect(source, current) {
        if (source != null && current != null && nav.currentDestination?.route != Routes.FeedList) {
            nav.navigate(Routes.FeedList) {
                popUpTo(nav.graph.id) { inclusive = false }
                launchSingleTop = true
            }
        }
    }
}

/** Outgoing/kept-alive entries may still compose during navigation; they cannot consume. */
@Composable
internal fun NotificationScopeDelivery(
    nav: NavHostController,
    entry: NavBackStackEntry,
    source: String?,
    applySource: (String) -> Unit,
    onConsumed: () -> Unit
) {
    val current by nav.currentBackStackEntryAsState()
    LaunchedEffect(source, current, entry) {
        // Check the live controller, not just the possibly outgoing composition's snapshot.
        // No suspension between ownership check, synchronous scope application and acknowledgement.
        if (source != null && nav.currentBackStackEntry === entry && entry.destination.route == Routes.FeedList) {
            applySource(source)
            onConsumed()
        }
    }
}
