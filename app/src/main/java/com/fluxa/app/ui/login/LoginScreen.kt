package com.fluxa.app.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fluxa.app.ui.components.UiState

@Composable
fun LoginRoute(
    oauthCode: String?,
    onSignInClick: () -> Unit,
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(oauthCode) {
        if (!oauthCode.isNullOrBlank()) {
            viewModel.onOAuthCodeReceived(oauthCode)
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is UiState.Success) {
            onLoginSuccess()
        }
    }

    LoginScreen(uiState = uiState, onSignInClick = onSignInClick)
}

@Composable
private fun LoginScreen(
    uiState: UiState<Unit>,
    onSignInClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Fluxa", style = MaterialTheme.typography.displaySmall)
        Text(text = "A calm reader for your feeds", style = MaterialTheme.typography.bodyLarge)

        Button(
            onClick = onSignInClick,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            Text("Sign in with Inoreader")
        }

        when (uiState) {
            UiState.Loading -> CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
            is UiState.Error -> Text(text = uiState.message, modifier = Modifier.padding(top = 16.dp))
            else -> Unit
        }
    }
}
