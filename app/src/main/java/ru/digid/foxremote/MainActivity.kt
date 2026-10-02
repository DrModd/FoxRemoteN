package ru.digid.foxremote

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {

    private val vm: FoxViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.statusBarColor = BG_ARGB
        @Suppress("DEPRECATION")
        window.navigationBarColor = BG_ARGB
        setContent { FoxScreen(vm) }
    }

    override fun onStart() {
        super.onStart()
        vm.startPolling()
    }

    override fun onStop() {
        vm.stopPolling()
        super.onStop()
    }

    private fun volumeKeysActive(): Boolean =
        vm.ui.connected && vm.ui.status.volumeAvailable

    // Кнопки громкости телефона управляют громкостью Фокса, пока приложение на экране
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKeysActive()) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    vm.volumeStep(VOLUME_KEY_STEP); return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    vm.volumeStep(-VOLUME_KEY_STEP); return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKeysActive() &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        const val VOLUME_KEY_STEP = 2
        const val BG_ARGB = 0xFF0B0B0B.toInt()
    }
}
