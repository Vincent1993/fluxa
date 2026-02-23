package com.fluxa.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fluxa.app.navigation.FluxaNavHost
import com.fluxa.app.ui.theme.FluxaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var pendingAuthCode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleOAuthIntent(intent)
        enableEdgeToEdge()
        setContent {
            FluxaTheme {
                FluxaNavHost(
                    oauthCode = pendingAuthCode,
                    consumeOAuthCode = { pendingAuthCode = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthIntent(intent)
    }

    private fun handleOAuthIntent(intent: Intent?) {
        val code = intent?.data?.getQueryParameter("code")
        if (!code.isNullOrBlank()) {
            pendingAuthCode = code
        }
    }
}
