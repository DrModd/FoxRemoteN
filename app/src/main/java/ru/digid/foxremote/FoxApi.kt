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
    val power: Boolean = true,   // false — усилитель в дежурном режиме
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
            power = j.optBoolean("power", true),
        )
    }

    /** Текущий трек из необязательного track.php. null — нет данных или файла нет. */
    suspend fun track(): Track? {
        val j = try {
            JSONObject(request("track.php"))
        } catch (e: FoxException) {
            if (e.code == 404) throw TrackNotInstalled() else throw e
        }
        val t = Track(j.optString("artist", ""), j.optString("title", ""), j.optString("album", ""))
        return if (t.artist.isBlank() && t.title.isBlank()) null else t
    }

    suspend fun setAmpVolume(pos: Int) {
        request("amp.php", mapOf("action" to "vol", "pos" to pos.toString()))
    }

    suspend fun ampMute() {
        request("amp.php", mapOf("action" to "mute"))
    }

    /** Включить / выключить усилитель (дежурный режим) */
    suspend fun ampPower() {
        request("amp.php", mapOf("action" to "power"))
    }

    // ---------------- DigiFox: усилитель, будильник, таймер ----------------

    /** amp.php?full=1: настройки усилителя, версия, время, таймер сна, будильник */
    suspend fun ampFull(): AmpFull {
        val j = try {
            JSONObject(request("amp.php?full=1"))
        } catch (e: FoxException) {
            if (e.code == 404) throw AmpNotInstalled() else throw e
        }
        val cfg = j.optJSONObject("cfg")?.let { c ->
            AMP_KEYS.associateWith { c.optInt(it, -1) }
        }
        val al = j.optJSONObject("alarm")
        return AmpFull(
            present = j.optBoolean("present", false),
            power = j.optBoolean("power", true),
            max = j.optInt("max", 80),
            cfg = cfg,
            ver = if (j.isNull("ver")) null else j.optString("ver", "").ifBlank { null },
            fwBuiltin = if (j.isNull("fw_builtin")) null else j.optString("fw_builtin", "").ifBlank { null },
            fwUpdate = j.optBoolean("fw_update", false),
            tz = j.optString("tz", "MSK-3"),
            now = j.optString("now", ""),
            timeOk = j.optBoolean("time_ok", true),
            sleepLeft = j.optInt("sleep_left", 0),
            alarm = Alarm(
                on = al?.optBoolean("on", false) ?: false,
                time = al?.optString("time", "07:00") ?: "07:00",
                days = al?.optString("days", "12345") ?: "12345",
                src = al?.optString("src", "keep") ?: "keep",
                vol = al?.optInt("vol", -30) ?: -30,
            ),
        )
    }

    suspend fun ampSet(key: String, value: Int) {
        request("amp.php", mapOf("action" to "set", "key" to key, "val" to value.toString()))
    }

    /** Таймер сна, минут; 0 — отменить */
    suspend fun sleepTimer(min: Int) {
        request("amp.php", mapOf("action" to "sleep", "min" to min.toString()))
    }

    suspend fun saveAlarm(a: Alarm) {
        request(
            "amp.php", mapOf(
                "action" to "alarm", "on" to if (a.on) "1" else "0", "time" to a.time,
                "days" to a.days, "src" to a.src, "vol" to a.vol.toString(),
            )
        )
    }

    suspend fun setTz(tz: String) {
        request("amp.php", mapOf("action" to "tz", "tz" to tz))
    }

    // ---------------- DigiFox: I2S и пересчёт частоты ----------------

    suspend fun i2s(): I2sStatus {
        val j = JSONObject(request("handle_i2s.php?action=getStatus"))
        return I2sStatus(
            mode = j.optString("mode", ""),
            mclk = j.optString("mclk", ""),
            submode = j.optString("submode", "std"),
            pcmSwap = j.optString("pcm_swap", "0") == "1",
            dsdSwap = j.optString("dsd_swap", "0") == "1",
            freqSwap = j.optString("freq_swap", "0") == "1",
        )
    }

    /** Одна настройка I2S, как кнопка на странице: mode/mclk/submode/pcm_swap/dsd_swap/freq_swap */
    suspend fun i2sSet(key: String, value: String) {
        try {
            request("handle_i2s.php", mapOf(key to value), readTimeoutMs = 60_000)
        } catch (e: FoxException) {
            throw when (e.code) {
                409 -> FoxException(0, "Фокс занят переключением звука, повторите через пару секунд")
                403 -> FoxException(0, "В режиме USB → I2S доступен только STD")
                else -> e
            }
        }
    }

    /** Пересчёт частоты: "ak4137" | "fox"; ak — есть ли AK4137 (null — неизвестно). 404 — старая прошивка */
    suspend fun src(): SrcMode? = try {
        val j = JSONObject(request("src.php"))
        val f = j.optJSONObject("filter")
        SrcMode(
            j.optString("mode", "ak4137"), if (j.isNull("ak")) null else j.optBoolean("ak"),
            f?.let {
                SrcFilter(
                    it.optString("phase", "lin"), it.optString("rolloff", "std"), it.optString("gain", "0"),
                    if (it.has("loudness")) it.optString("loudness", "off") else null,
                )
            },
        )
    } catch (e: FoxException) {
        if (e.code == 404) null else throw e
    }

    /** Фильтр пересчёта: phase=lin|int|min, rolloff=std|steep|slow, gain=0|-3; loudness=on|off */
    suspend fun setSrcFilter(key: String, value: String) {
        request("src.php", mapOf(key to value))
    }

    suspend fun setSrc(mode: String) {
        request("src.php", mapOf("mode" to mode), readTimeoutMs = 60_000)
    }

    // ---------------- система ----------------

    /** Резервная копия настроек (backup.php): JSON целиком */
    suspend fun backup(): ByteArray = withContext(Dispatchers.IO) {
        val conn = URL("http://$host/backup.php").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 3000
            conn.readTimeout = 30_000
            conn.useCaches = false
            val code = conn.responseCode
            if (code !in 200..299) throw FoxException(code, conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200) ?: "")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** Восстановить из копии; после этого нужна перезагрузка. Возвращает версию, из которой копия */
    suspend fun restore(json: ByteArray): String? {
        val text = json.toString(Charsets.UTF_8)
        val r = try {
            JSONObject(request("backup.php", mapOf("json" to text), readTimeoutMs = 60_000))
        } catch (e: FoxException) {
            val msg = try { JSONObject(e.message?.substringAfter(": ") ?: "").optString("error") } catch (x: Exception) { "" }
            throw if (msg.isNotBlank()) FoxException(0, msg) else e
        }
        if (!r.optBoolean("ok", false)) throw FoxException(0, r.optString("error", "Ошибка восстановления"))
        return if (r.isNull("from")) null else r.optString("from")
    }

    suspend fun diag(): Diag {
        val j = JSONObject(request("diag.php?json=1", readTimeoutMs = 15_000))
        val secs = j.optJSONArray("sections")
        val sections = (0 until (secs?.length() ?: 0)).map { i ->
            val s = secs!!.getJSONObject(i)
            val rows = s.optJSONArray("rows")
            s.optString("title") to (0 until (rows?.length() ?: 0)).map { k ->
                val r = rows!!.getJSONArray(k)
                r.optString(0) to r.optString(1)
            }
        }
        val la = j.optJSONArray("logs")
        val logs = (0 until (la?.length() ?: 0)).map { i ->
            val l = la!!.getJSONObject(i)
            l.optString("title") to l.optString("text")
        }
        return Diag(sections, logs, j.optString("text"))
    }

    suspend fun reboot() {
        request("reboot.php", mapOf("x" to "1"))
    }

    /** Обновление прошивки Фокса с GitHub: журнал построчно (run_update.php) */
    suspend fun runUpdate(onLine: (String) -> Unit) = stream("run_update.php", null, onLine)

    /** Прошивка усилителя через Фокс (amp_flash.php): журнал построчно */
    suspend fun flashAmp(name: String, data: ByteArray, onLine: (String) -> Unit) =
        stream("amp_flash.php", name to data, onLine)

    /** Прошивка усилителя, встроенная в DigiFox */
    suspend fun flashAmpBuiltin(onLine: (String) -> Unit) =
        stream("amp_flash.php", null, onLine, form = "builtin=1")

    /** Долгий запрос с построчным ответом; file — multipart-поле "fw" */
    private suspend fun stream(
        path: String,
        file: Pair<String, ByteArray>?,
        onLine: (String) -> Unit,
        form: String? = null,
    ) = withContext(Dispatchers.IO) {
        val conn = URL("http://$host/$path").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 3000
            conn.readTimeout = 600_000
            conn.useCaches = false
            if (form != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(form.toByteArray()) }
            } else if (file != null) {
                val b = "----digifox" + System.nanoTime()
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
                val head = "--$b\r\nContent-Disposition: form-data; name=\"fw\"; filename=\"${file.first}\"\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n"
                conn.outputStream.use {
                    it.write(head.toByteArray())
                    it.write(file.second)
                    it.write("\r\n--$b--\r\n".toByteArray())
                }
            }
            val code = conn.responseCode
            val st = if (code in 200..299) conn.inputStream else conn.errorStream
            st?.bufferedReader()?.use { r ->
                while (true) {
                    val l = r.readLine() ?: break
                    withContext(Dispatchers.Main) { onLine(l) }
                }
            }
            if (code !in 200..299) throw FoxException(code, "")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun parsePercent(s: String): Int = s.filter { it.isDigit() }.toIntOrNull() ?: -1
    }
}

val AMP_KEYS = listOf("filter", "delay", "dsdgain", "standby", "autoon", "mode", "vmax", "von")

/** Будильник (amp.php: /etc/digifox/alarm.conf). days — "12345" (1 = пн). vol — дБ */
data class Alarm(val on: Boolean, val time: String, val days: String, val src: String, val vol: Int)

data class AmpFull(
    val present: Boolean,
    val power: Boolean,
    val max: Int,                   // шкала громкости усилителя (положение max = 0 dB)
    val cfg: Map<String, Int>?,     // vmax/von = -1 — прошивка усилителя до 1.3     // null — усилитель ещё не прислал настройки
    val ver: String?,
    val fwBuiltin: String? = null,  // версия прошивки усилителя внутри DigiFox
    val fwUpdate: Boolean = false,  // она новее установленной
    val tz: String,
    val now: String,
    val timeOk: Boolean,
    val sleepLeft: Int,             // секунд до выключения, 0 — таймер не взведён
    val alarm: Alarm,
)

data class I2sStatus(
    val mode: String, val mclk: String, val submode: String,
    val pcmSwap: Boolean, val dsdSwap: Boolean, val freqSwap: Boolean,
)

data class SrcMode(val mode: String, val ak: Boolean?, val filter: SrcFilter? = null)

/** loudness: "on" / "off", null — прошивка без тонкомпенсации */
data class SrcFilter(val phase: String, val rolloff: String, val gain: String, val loudness: String? = null)

/** Страница диагностики (diag.php?json=1) */
data class Diag(val sections: List<Pair<String, List<Pair<String, String>>>>, val logs: List<Pair<String, String>>, val text: String)

/** Трек: исполнитель, название, альбом */
data class Track(val artist: String, val title: String, val album: String) {
    /** "Исполнитель — Название" */
    val line: String get() = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" — ")
}

class TrackNotInstalled : Exception("track.php не установлен")

const val STOPPED = "нет сигнала"

/** "192000 S32_LE" -> "192 kHz · 32 bit", "DSD128" -> "DSD128", "STOP" -> "нет сигнала" */
fun formatRate(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    if (raw == "STOP") return STOPPED
    if (raw.startsWith("DSD")) return raw
    val parts = raw.split(' ')
    val hz = parts[0].toIntOrNull() ?: return raw
    val khz = if (hz % 1000 == 0) "${hz / 1000}" else String.format(java.util.Locale.US, "%.1f", hz / 1000.0)
    val bits = parts.getOrNull(1)?.let { fmt ->
        Regex("""[SU](\d+)""").find(fmt)?.groupValues?.get(1)
    }
    return if (bits != null) "$khz kHz · $bits bit" else "$khz kHz"
}
