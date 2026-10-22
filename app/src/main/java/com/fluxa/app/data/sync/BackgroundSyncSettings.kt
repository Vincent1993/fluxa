package com.fluxa.app.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackgroundSyncSettings @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("fluxa_sync_preferences", Context.MODE_PRIVATE)
    val enabled: Boolean get() = preferences.getBoolean("enabled", false)
    val configured: Boolean get() = preferences.contains("enabled")

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean("enabled", value).apply()
    }
}
