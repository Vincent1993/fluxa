package com.fluxa.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.viewModels
import com.fluxa.app.ui.feedlist.LocalTransferViewModel
import com.fluxa.app.data.directrss.SharedLinks
import com.fluxa.app.data.directrss.sharedLinks
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fluxa.app.navigation.FluxaNavHost
import com.fluxa.app.ui.theme.FluxaTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

internal fun sharedLinksFromIntent(intent: Intent?): SharedLinks? {
    if (intent?.action != Intent.ACTION_SEND || intent.type !in setOf("text/plain", "text/html")) return null
    return try {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val html = intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
        if (intent.type == "text/html") sharedLinks(null, html ?: text)
        else sharedLinks(text, html)
    } catch (_: RuntimeException) { SharedLinks(emptyList(), "无法读取分享，请粘贴网址") }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var directRss: com.fluxa.app.data.directrss.DirectRssRepository
    private val transfer: LocalTransferViewModel by viewModels()
    private val notificationSource = androidx.compose.runtime.mutableStateOf<String?>(null)
    private var notificationGeneration = 0L
    private fun receiveNotification(intent: Intent?) {
        if (intent?.action != com.fluxa.app.data.directrss.LocalFeedNotifications.ACTION_SOURCE) return
        val ticket = ++notificationGeneration
        notificationSource.value = null
        val source = runCatching { intent.getStringExtra(com.fluxa.app.data.directrss.LocalFeedNotifications.EXTRA_SOURCE) }.getOrNull()
        setIntent(Intent(this, MainActivity::class.java))
        if (source == null || source.length > 200 || !source.startsWith(com.fluxa.app.data.directrss.LOCAL_PREFIX)) return
        lifecycleScope.launch {
            if (directRss.notificationSource(source) && ticket == notificationGeneration) notificationSource.value = source
        }
    }
    private fun receiveShare(intent: Intent?) {
        transfer.receive(sharedLinksFromIntent(intent) ?: return)
        // Consume once. Rotation must not restart extraction or resurrect a dismissed SEND.
        setIntent(Intent(this, MainActivity::class.java))
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveShare(intent)
        receiveNotification(intent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val incoming = intent
        if (savedInstanceState == null) receiveShare(incoming)
        // A real notification can relaunch a killed task with saved navigation state.
        // Consumed intents are cleared above, so restoration must not discard a new tap.
        receiveNotification(incoming)
        setContent { FluxaTheme { FluxaNavHost(transfer, notificationSource.value) { notificationSource.value = null } } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try { directRss.refreshOnResume() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Per-source attempt/error diagnostics are persisted by the repository. */ }
            }
        }
    }
}
