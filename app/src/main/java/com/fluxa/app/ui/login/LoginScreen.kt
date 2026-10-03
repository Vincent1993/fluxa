package com.fluxa.app.ui.login

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
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
    viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state) { if (state is UiState.Success) onLoginSuccess() }
    val context = LocalContext.current
    LoginScreen(state, viewModel::signIn, onOpenCache) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.newsblur.com/")))
    }
}

@Composable
fun LoginScreen(state: UiState<Unit>, onSignIn: (String, String) -> Unit,
    onOpenCache: () -> Unit, onCreateAccount: () -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    // Do not persist passwords in SavedState or on disk.
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Fluxa", style = MaterialTheme.typography.displaySmall)
        Text("安静阅读，离线也能收藏", style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
        Text("登录 NewsBlur · 支持免费账号", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(username, { username = it }, label = { Text("NewsBlur 用户名") },
            singleLine = true, enabled = state != UiState.Loading,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
        OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true,
            enabled = state != UiState.Loading, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Button(onClick = { onSignIn(username, password); password = "" },
            enabled = username.isNotBlank() && state != UiState.Loading,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("登录") }
        if (state == UiState.Loading) CircularProgressIndicator(Modifier.padding(16.dp))
        if (state is UiState.Error) Text(state.message, Modifier.padding(vertical = 12.dp))
        TextButton(onClick = onOpenCache) { Text("阅读本地缓存") }
        TextButton(onClick = onCreateAccount) { Text("在 NewsBlur 网站创建账号") }
        Text("密码不会保存。登录会话仅保存在此设备的加密存储中。",
            style = MaterialTheme.typography.bodySmall)
        Text("${BuildConfig.CHANNEL} · ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp))
    }
}
