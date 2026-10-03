package ism.dansha.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ism.dansha.app.ui.DanshaRoot
import ism.dansha.app.ui.DanshaTheme
import ism.dansha.app.ui.isDark

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mode by vm.uiMode.collectAsState()
            val dark = isDark(mode)
            // ไอคอนแถบสถานะ/แถบนำทางตามธีมของแอพ (ไม่ใช่ของระบบ)
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            DanshaTheme(mode) { DanshaRoot(vm) }
        }
    }
}
