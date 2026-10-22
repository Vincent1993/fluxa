package com.fluxa.app.ui.login

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import com.fluxa.app.ui.components.IconAction
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxa.app.ui.components.UiState
import com.fluxa.app.BuildConfig

@Composable
fun LoginRoute(onOpenCache: () -> Unit, onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(), onOpenGitHub: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state) { if (state is UiState.Success) onLoginSuccess() }
    val context = LocalContext.current
    LoginScreen(state, viewModel::signIn, onOpenCache, onOpenGitHub = onOpenGitHub) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.newsblur.com/"))) }
    }
}

@Composable
fun LoginScreen(state: UiState<Unit>, onSignIn: (String, String) -> Unit,
    onOpenCache: () -> Unit, onCreateAccount: () -> Unit) =
    LoginScreen(state, onSignIn, onOpenCache, {}, onCreateAccount)

@Composable
fun LoginScreen(state: UiState<Unit>, onSignIn: (String, String) -> Unit,
    onOpenCache: () -> Unit, onOpenGitHub: () -> Unit, onCreateAccount: () -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    // Do not persist passwords in SavedState or on disk.
    var password by remember { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 4.dp),
                        color = MaterialTheme.colorScheme.primary) {
                        Icon(Icons.Outlined.RssFeed, null, Modifier.padding(12.dp).size(28.dp),
                            tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Text("Fluxa", style = MaterialTheme.typography.displaySmall)
                }
                Text("安静阅读，离线也能收藏", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 24.dp))
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(20.dp)) {
                        Text("登录 NewsBlur · 支持免费账号", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(username, { username = it }, label = { Text("NewsBlur 用户名") },
                            singleLine = true, enabled = state != UiState.Loading,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                        OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true,
                            enabled = state != UiState.Loading, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        IconAction("登录", Icons.Outlined.Cloud, onClick = { onSignIn(username, password); password = "" },
                            enabled = username.isNotBlank() && state != UiState.Loading,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp))
                        if (state == UiState.Loading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(12.dp))
                        if (state is UiState.Error) Text(state.message, Modifier.padding(top = 12.dp),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                IconAction("本地 RSS · 无需账号", Icons.Outlined.RssFeed, onOpenCache,
                    modifier = Modifier.padding(top = 16.dp))
                IconAction("直接阅读", Icons.Outlined.Inventory2, onOpenCache)
                IconAction("GitHub 收藏 · 无需登录", Icons.Outlined.Star, onOpenGitHub)
                IconAction("在 NewsBlur 网站创建账号", Icons.Outlined.OpenInNew, onCreateAccount)
                Text("密码不会保存。登录会话仅保存在此设备的加密存储中。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${BuildConfig.CHANNEL} · ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}
