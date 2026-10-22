package com.juhao.murexide.ui.login

import com.juhao.murexide.ui.icons.AppIcons

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import com.juhao.murexide.MainActivity
import com.juhao.murexide.datastore.AccountStorage
import com.juhao.murexide.datastore.UserAccount
import com.juhao.murexide.repository.AuthRepository
import com.juhao.murexide.ui.theme.MurexideTheme
import com.juhao.murexide.utils.DeviceIdProvider
import kotlinx.coroutines.launch

class LoginActivity : ComponentActivity() {
    private val accountStorage by lazy { AccountStorage.getInstance(this) }
    private val authRepository by lazy { AuthRepository() }
    private val deviceId by lazy { DeviceIdProvider.get(this) }
    private lateinit var loginViewModel: LoginViewModel
    private var isCompletingLogin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isAddMode = intent.getBooleanExtra("addMode", false)
        loginViewModel = ViewModelProvider(
            this,
            LoginViewModelFactory(deviceId)
        )[LoginViewModel::class.java]

        setContent {
            var showTokenDialog by remember { mutableStateOf(false) }

            MurexideTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LoginScreen(
                        onLoginSuccess = { token ->
                            completeLogin(token, isAddMode, "登录成功")
                        },
                        onTokenLogin = {
                            showTokenDialog = true
                        },
                        viewModel = loginViewModel
                    )

                    if (showTokenDialog) {
                        TokenInputDialog(
                            onDismiss = { showTokenDialog = false },
                            onConfirm = { token ->
                                showTokenDialog = false
                                completeLogin(token.trim(), isAddMode, "Token登录成功")
                            }
                        )
                    }
                }
            }
        }
    }

    private fun completeLogin(token: String, isAddMode: Boolean, successMessage: String) {
        if (isCompletingLogin) return
        isCompletingLogin = true

        lifecycleScope.launch {
            val userInfoResult = authRepository.getUserInfo(token)
            if (userInfoResult.isFailure) {
                isCompletingLogin = false
                loginViewModel.resetLoginResult()
                Toast.makeText(
                    this@LoginActivity,
                    userInfoResult.exceptionOrNull()?.message ?: "登录凭据无效",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val userInfo = userInfoResult.getOrThrow()
            try {
                accountStorage.upsertAccount(
                    account = UserAccount(
                        username = userInfo.name,
                        avatar = userInfo.avatarUrl,
                        id = userInfo.id,
                        token = token,
                        isValidated = true
                    ),
                    makeCurrent = !isAddMode
                )
            } catch (error: Exception) {
                isCompletingLogin = false
                loginViewModel.resetLoginResult()
                Toast.makeText(
                    this@LoginActivity,
                    error.message ?: "保存账号失败",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            Toast.makeText(
                this@LoginActivity,
                if (isAddMode) "账号已添加" else successMessage,
                Toast.LENGTH_SHORT
            ).show()
            if (!isAddMode) {
                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
            }
            finish()
        }
    }

    companion object {
        fun start(context: Context, isAddMode: Boolean = false) {
            val intent = Intent(context, LoginActivity::class.java).apply {
                putExtra("addMode", isAddMode)
            }
            context.startActivity(intent)
        }
    }
}

@Composable
fun TokenInputDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                AppIcons.Key,
                contentDescription = null
            )
        },
        title = { Text("Token 登录") },
        text = {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                placeholder = { Text("请输入 Token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(token) },
                enabled = token.isNotBlank()
            ) {
                Text("登录")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
