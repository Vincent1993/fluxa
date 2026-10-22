package com.fluxa.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxa.app.data.repository.AuthRepository
import com.fluxa.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow<UiState<Unit>>(UiState.Empty)
    val uiState = _state.asStateFlow()
    fun signIn(username: String, password: String) {
        if (_state.value == UiState.Loading) return
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                auth.signIn(username, password)
                _state.value = UiState.Success(Unit)
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalStateException) { _state.value = UiState.Error(e.message ?: "登录失败") }
            catch (_: Exception) { _state.value = UiState.Error("登录失败，请检查用户名、密码和网络") }
        }
    }
}
