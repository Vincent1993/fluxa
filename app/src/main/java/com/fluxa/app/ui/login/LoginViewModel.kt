package com.fluxa.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxa.app.data.repository.AuthRepository
import com.fluxa.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<UiState<Unit>>(UiState.Empty)
    val uiState: StateFlow<UiState<Unit>> = _uiState.asStateFlow()

    fun onOAuthCodeReceived(code: String) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            runCatching { authRepository.exchangeCode(code) }
                .onSuccess { _uiState.value = UiState.Success(Unit) }
                .onFailure { _uiState.value = UiState.Error("登录失败，请重试") }
        }
    }

    fun clearError() {
        _uiState.value = UiState.Empty
    }
}
