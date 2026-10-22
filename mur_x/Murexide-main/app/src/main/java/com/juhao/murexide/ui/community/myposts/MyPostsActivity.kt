package com.juhao.murexide.ui.community.myposts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.juhao.murexide.datastore.AccountStorage
import com.juhao.murexide.ui.theme.MurexideTheme

class MyPostsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val viewModel: MyPostsViewModel by viewModels {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MyPostsViewModel(
                        AccountStorage.getInstance(this@MyPostsActivity)
                    ) as T
                }
            }
        }

        setContent {
            MurexideTheme {
                MyPostsScreen(
                    onBackClick = { finish() },
                    viewModel = viewModel
                )
            }
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, MyPostsActivity::class.java).apply {
                if (context !is ComponentActivity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }
    }
}
