package com.riverrunner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.riverrunner.ui.AppViewModel
import com.riverrunner.ui.Palette
import com.riverrunner.ui.RiverScreens
import com.riverrunner.ui.RiverTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            val vm: AppViewModel = viewModel(factory = viewModelFactory { initializer { AppViewModel(AndroidPlatform(applicationContext)) } })
            RiverTheme(vm.strings, vm.deckStyle, AndroidFonts) {
                run {
                    Box(Modifier.fillMaxSize().background(Palette.Bg).windowInsetsPadding(WindowInsets.displayCutout)) {
                        RiverScreens(vm)
                    }
                }
            }
        }
    }
}
