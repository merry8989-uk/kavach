package com.kavach.caller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kavach.caller.ui.nav.KavachNav
import com.kavach.caller.ui.theme.KavachTheme
import com.kavach.caller.ui.theme.ThemePack
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KavachTheme(pack = ThemePack.AURORA) {
                KavachNav()
            }
        }
    }
}
