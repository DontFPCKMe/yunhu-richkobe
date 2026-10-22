package com.juhao.murexide.ui.settings.switchAccount

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.juhao.murexide.ui.theme.MurexideTheme

class SwitchAccountActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isChooseMode = intent.getBooleanExtra("chooseMode", false)

        enableEdgeToEdge()
        setContent {
            MurexideTheme {
                Greeting(
                    onBack = { finish() },
                    isChooseMode = isChooseMode,
                )
            }
        }
    }
}