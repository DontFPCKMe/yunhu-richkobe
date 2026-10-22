package com.juhao.murexide.ui.community.ba

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.juhao.murexide.datastore.AccountStorage
import com.juhao.murexide.ui.theme.MurexideTheme

class BaDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val baId = resolveBaId()
        if (baId <= 0) {
            Toast.makeText(this, "无效的分区ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val viewModel: BaDetailViewModel by viewModels {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return BaDetailViewModel(
                        accountStorage = AccountStorage.getInstance(this@BaDetailActivity),
                        baId = baId
                    ) as T
                }
            }
        }

        setContent {
            MurexideTheme {
                BaDetailScreen(
                    onBackClick = { finish() },
                    viewModel = viewModel
                )
            }
        }
    }

    private fun resolveBaId(): Int {
        val extraId = intent.getIntExtra(EXTRA_BA_ID, -1)
        if (extraId > 0) return extraId

        // yunhu://alley-detail?id=xxx
        intent.data?.getQueryParameter("id")?.toIntOrNull()?.let { return it }

        return -1
    }

    companion object {
        const val EXTRA_BA_ID = "ba_id"

        fun start(context: Context, baId: Int) {
            val intent = Intent(context, BaDetailActivity::class.java).apply {
                putExtra(EXTRA_BA_ID, baId)
                if (context !is ComponentActivity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }
    }
}
