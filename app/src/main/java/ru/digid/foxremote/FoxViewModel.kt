package ru.digid.foxremote

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Плееры PureFox: id для handle_service.php и подпись на экране */
data class Player(val id: String, val label: String)

val PLAYERS = listOf(
    Player("qobuz", "Qobuz Connect"),
    Player("naa", "HQPlayer NAA"),
    Player("raat", "Roon Ready"),
    Player("shairport", "AirPlay"),
    Player("spotify", "Spotify Connect"),
    Player("lms", "Squeezelite"),
    Player("aprenderer", "UPnP Renderer"),
    Player("mpd", "MPD"),
    Player("aplayer", "Веб-радио"),
    Player("apscream", "APScream"),
    Player("celmusper", "Celmusper"),
    Player("tidalconnect", "Tidal Connect"),
)

fun playerLabel(id: String): String = PLAYERS.firstOrNull { it.id == id }?.label ?: id

data class UiState(
    val host: String = "",
    val connected: Boolean = false,
    val status: FoxStatus = FoxStatus(),
    val usb: Boolean? = null,          // режим USBtoI2S, null = неизвестно
    val rate: String? = null,          // уже отформатированная строка или null
    val busy: String? = null,          // текст долгого действия или null
    val error: String? = null,
    val discovering: Boolean = false,
    val localVolume: Int? = null,      // громкость, которую двигает пользователь (до ответа Фокса)
)

class FoxViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("fox", Context.MODE_PRIVATE)
    private val discovery = FoxDiscovery(app)

    var ui by mutableStateOf(UiState(host = prefs.getString("host", "") ?: ""))
        private set

    private var api: FoxApi? = ui.host.takeIf { it.isNotBlank() }?.let { FoxApi(it) }
    private var pollJob: Job? = null
    private var volumeJob: Job? = null
    private var rateSupported = true

    init {
        if (ui.host.isBlank()) discover()
    }

    // ---------------- подключение ----------------

    fun setHost(host: String) {
        val h = host.trim().removePrefix("http://").trimEnd('/')
        prefs.edit().putString("host", h).apply()
        api = if (h.isBlank()) null else FoxApi(h)
        rateSupported = true
        ui = ui.copy(host = h, connected = false, error = null, rate = null)
        viewModelScope.launch { refresh() }
    }

    fun discover() {
        if (ui.discovering) return
        viewModelScope.launch {
            ui = ui.copy(discovering = true, error = null)
            val found = discovery.find()
            ui = ui.copy(discovering = false)
            if (found != null) {
                setHost(found)
            } else if (api == null) {
                // mDNS не нашёл — пробуем имя по умолчанию
                setHost("purefox.local")
            } else {
                ui = ui.copy(error = "Фокс в сети не найден")
            }
        }
    }

    // ---------------- опрос статуса ----------------

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                if (ui.busy == null) refresh()
                delay(2000)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun refresh() {
        val a = api ?: return
        try {
            var st = a.status()
            val usb = a.usbMode()
            // В режиме USB → I2S регулировка громкости в драйвере PureFox даёт искажения,
            // поэтому держим там 100% (обработка в драйвере при 100% полностью отключена).
            if (usb && st.volumeAvailable && st.volume in 0..99) {
                a.setVolume(100)
                st = st.copy(volume = 100)
            }
            var rate: String? = null
            if (rateSupported) {
                val raw = a.rate()
                if (raw == null) rateSupported = false else rate = formatRate(raw)
            }
            if (api !== a) return  // пока ждали ответ, сменили адрес
            ui = ui.copy(connected = true, status = st, usb = usb, rate = rate, error = null)
        } catch (e: Exception) {
            if (api !== a) return
            ui = ui.copy(connected = false, error = "Нет связи с ${ui.host}")
        }
    }

    // ---------------- действия ----------------

    private fun runAction(message: String, block: suspend (FoxApi) -> Unit) {
        val a = api ?: return
        if (ui.busy != null) return
        viewModelScope.launch {
            ui = ui.copy(busy = message, error = null)
            try {
                block(a)
            } catch (e: Exception) {
                ui = ui.copy(error = e.message ?: "Ошибка")
            } finally {
                ui = ui.copy(busy = null)
                refresh()
            }
        }
    }

    fun selectPlayer(id: String) {
        if (ui.usb != true && ui.status.service == id) return
        runAction("Запуск: ${playerLabel(id)}…") { a ->
            if (ui.usb == true) a.setUsb(false)   // сначала уходим из USB, как веб-интерфейс
            a.switchService(id)
        }
    }

    fun setUsb(on: Boolean) {
        if (ui.usb == on) return
        runAction(if (on) "Включение USB → I2S…" else "Возврат в сеть…") { a ->
            a.setUsb(on)
            if (on) a.setVolume(100)   // bit-perfect: без цифровой регулировки в драйвере
        }
    }

    fun toggleMute() {
        val a = api ?: return
        viewModelScope.launch {
            try {
                val m = a.toggleMute()
                ui = ui.copy(status = ui.status.copy(muted = m))
            } catch (e: Exception) {
                ui = ui.copy(error = e.message)
            }
        }
    }

    /** Ползунок: двигаем локально, отправляем с небольшой задержкой */
    fun setVolume(v: Int) {
        if (ui.usb == true) return
        ui = ui.copy(localVolume = v.coerceIn(0, 100))
        sendVolumeDebounced()
    }

    /** Аппаратные кнопки телефона */
    fun volumeStep(delta: Int) {
        if (!ui.connected || !ui.status.volumeAvailable || ui.usb == true) return
        val cur = ui.localVolume ?: ui.status.volume.takeIf { it >= 0 } ?: return
        setVolume(cur + delta)
    }

    private fun sendVolumeDebounced() {
        volumeJob?.cancel()
        volumeJob = viewModelScope.launch {
            delay(150)
            val a = api ?: return@launch
            val v = ui.localVolume ?: return@launch
            try {
                a.setVolume(v)
                ui = ui.copy(status = ui.status.copy(volume = v))
            } catch (e: Exception) {
                ui = ui.copy(error = e.message)
            }
            delay(600)  // не даём опросу «откатить» ползунок, пока Фокс применяет значение
            if (ui.localVolume == v) ui = ui.copy(localVolume = null)
        }
    }
}
