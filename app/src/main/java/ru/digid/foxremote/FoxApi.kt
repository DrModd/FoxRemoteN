package ru.digid.foxremote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Состояние PureFox по данным status_fast.php */
data class FoxStatus(
    val service: String = "",          // активный плеер: "qobuz", "naa", ...
    val alsa: String = "",             // "i2s" / "usb"
    val volume: Int = -1,              // 0..100, -1 = неизвестно
    val muted: Boolean = false,
    val volumeAvailable: Boolean = true,
)

/** Громкость усилителя DigiD D1 (amp.php на Фоксе, связь через STM32) */
data class AmpStatus(
    val pos: Int,          // положение 0..max
    val max: Int,
    val muted: Boolean,
    val db: Boolean,       // true — показывать (pos - max) дБ
)

class AmpNotInstalled : Exception("amp.php not installed")

class FoxException(val code: Int, text: String) : Exception(if (code > 0) "HTTP $code: $text" else text)

/**
 * Обёртка над PHP-обработчиками веб-интерфейса PureFox.
 * Те же запросы, что шлют кнопки на странице, поэтому прошивку менять не нужно.
 */
class FoxApi(private val host: String) {

    private suspend fun request(
        path: String,
        post: Map<String, String>? = null,
        readTimeoutMs: Int = 5000,
    ): String = withContext(Dispatchers.IO) {
        val conn = URL("http://$host/$path").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 3000
            conn.readTimeout = readTimeoutMs
            conn.useCaches = false
            if (post != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val body = post.entries.joinToString("&") { (k, v) ->
                    "$k=${URLEncoder.encode(v, "UTF-8")}"
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw FoxException(code, text.trim().take(200))
            text
        } finally {
            conn.disconnect()
        }
    }

    /** Общий статус: плеер, громкость, mute */
    suspend fun status(): FoxStatus {
        val j = JSONObject(request("status_fast.php"))
        return FoxStatus(
            service = j.optString("active_service", ""),
            alsa = j.optString("alsa_state", ""),
            volume = parsePercent(j.optString("volume", "")),
            muted = j.optBoolean("muted", false),
            volumeAvailable = j.optBoolean("volume_control_available", true),
        )
    }

    /** Режим USBtoI2S включён? */
    suspend fun usbMode(): Boolean {
        val j = JSONObject(request("usb_to_i2s.php", mapOf("action" to "status")))
        return j.optBoolean("enabled", false)
    }

    /** Включить/выключить USBtoI2S (может занимать до ~20 с) */
    suspend fun setUsb(on: Boolean) {
        val r = request(
            "usb_to_i2s.php",
            mapOf("action" to if (on) "enable" else "disable"),
            readTimeoutMs = 60_000,
        )
        if (!r.contains("successfully")) throw FoxException(0, r.trim().take(200))
    }

    /** Переключить плеер (Qobuz Connect стартует до ~15 с) */
    suspend fun switchService(id: String) {
        val r = request("handle_service.php", mapOf("service" to id), readTimeoutMs = 60_000)
        val ok = try {
            JSONObject(r).optString("status") == "success"
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            val msg = try {
                JSONObject(r).optString("message", r)
            } catch (e: Exception) {
                r
            }
            throw FoxException(0, msg.trim().take(200))
        }
    }

    suspend fun setVolume(v: Int) {
        request("volume.php", mapOf("action" to "set_volume", "volume" to v.coerceIn(0, 100).toString()))
    }

    /** Переключить mute, вернуть новое состояние */
    suspend fun toggleMute(): Boolean {
        val j = JSONObject(request("volume.php", mapOf("action" to "toggle_mute")))
        return j.optBoolean("muted", false)
    }

    /**
     * Частота потока из необязательного rate.php ("192000 S32_LE", "DSD128", "STOP").
     * null — файла на Фоксе нет.
     */
    suspend fun rate(): String? = try {
        request("rate.php").trim()
    } catch (e: FoxException) {
        if (e.code == 404) null else throw e
    }

    /**
     * Громкость усилителя. null — amp.php на Фоксе нет или усилитель ещё не на связи.
     * Отличаем: notInstalled = true, если файла нет совсем (404).
     */
    suspend fun amp(): AmpStatus? {
        val j = try {
            JSONObject(request("amp.php"))
        } catch (e: FoxException) {
            if (e.code == 404) throw AmpNotInstalled() else throw e
        }
        if (!j.optBoolean("present", false)) return null
        return AmpStatus(
            pos = j.optInt("pos", 0),
            max = j.optInt("max", 100).coerceAtLeast(1),
            muted = j.optBoolean("mute", false),
            db = j.optBoolean("db", false),
        )
    }

    suspend fun setAmpVolume(pos: Int) {
        request("amp.php", mapOf("action" to "vol", "pos" to pos.toString()))
    }

    suspend fun ampMute() {
        request("amp.php", mapOf("action" to "mute"))
    }

    companion object {
        fun parsePercent(s: String): Int = s.filter { it.isDigit() }.toIntOrNull() ?: -1
    }
}

/** "192000 S32_LE" -> "192 kHz · 32 bit", "DSD128" -> "DSD128", "STOP" -> "нет сигнала" */
fun formatRate(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    if (raw == "STOP") return "нет сигнала"
    if (raw.startsWith("DSD")) return raw
    val parts = raw.split(' ')
    val hz = parts[0].toIntOrNull() ?: return raw
    val khz = if (hz % 1000 == 0) "${hz / 1000}" else String.format(java.util.Locale.US, "%.1f", hz / 1000.0)
    val bits = parts.getOrNull(1)?.let { fmt ->
        Regex("""[SU](\d+)""").find(fmt)?.groupValues?.get(1)
    }
    return if (bits != null) "$khz kHz · $bits bit" else "$khz kHz"
}
