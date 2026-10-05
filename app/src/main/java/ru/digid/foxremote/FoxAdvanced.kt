package ru.digid.foxremote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Страницы приложения — те же, что в веб-интерфейсе DigiFox */
enum class Page { MAIN, AMP, I2S, DIAG }

/** Состояние страниц «Усилитель» и «I2S», обновления и прошивки */
data class AdvState(
    val amp: AmpFull? = null,
    val ampSupported: Boolean = true,
    val i2s: I2sStatus? = null,
    val src: SrcMode? = null,
    val srcSupported: Boolean = true,
    val diag: Diag? = null,
    val diagSupported: Boolean = true,
    val busy: String? = null,          // идёт действие на странице
    val msg: String? = null,           // последнее сообщение (ошибка или "Сохранено")
    val needReboot: Boolean = false,   // тактирование изменится после перезагрузки
    val logTitle: String? = null,      // открыт журнал (обновление / прошивка)
    val log: String = "",
    val logRunning: Boolean = false,
)

/**
 * Логика страниц DigiFox. Живёт внутри FoxViewModel (тот же адрес Фокса и scope).
 * Все запросы — те же, что делает веб-интерфейс.
 */
class FoxAdvanced(private val scope: CoroutineScope, private val apiOf: () -> FoxApi?) {

    var st by mutableStateOf(AdvState())
        private set

    private var pollJob: Job? = null

    fun reset() {
        st = AdvState()
    }

    // ---------------- загрузка ----------------

    /** Опрос страницы, пока она открыта */
    fun open(page: Page) {
        pollJob?.cancel()
        if (page == Page.MAIN) return
        pollJob = scope.launch {
            while (isActive) {
                if (st.busy == null && !st.logRunning) load(page)
                delay(when (page) { Page.AMP -> 3000L; Page.DIAG -> 10_000L; else -> 5000L })
            }
        }
    }

    fun close() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun load(page: Page) {
        val a = apiOf() ?: return
        try {
            when (page) {
                Page.AMP -> if (st.ampSupported) {
                    st = try {
                        st.copy(amp = a.ampFull())
                    } catch (e: AmpNotInstalled) {
                        st.copy(ampSupported = false)
                    }
                }
                Page.I2S -> {
                    val i = a.i2s()
                    var s = st.copy(i2s = i)
                    if (st.srcSupported) {
                        val m = a.src()
                        s = if (m == null) s.copy(srcSupported = false) else s.copy(src = m)
                    }
                    st = s
                }
                Page.DIAG -> if (st.diagSupported) {
                    st = try {
                        st.copy(diag = a.diag())
                    } catch (e: FoxException) {
                        if (e.code == 404) st.copy(diagSupported = false) else throw e
                    }
                }
                Page.MAIN -> {}
            }
        } catch (e: Exception) {
            st = st.copy(msg = "Нет связи с Фоксом")
        }
    }

    private fun act(page: Page, busy: String, done: String? = "Сохранено", block: suspend (FoxApi) -> Unit) {
        val a = apiOf() ?: return
        if (st.busy != null) return
        scope.launch {
            st = st.copy(busy = busy, msg = null)
            try {
                block(a)
                st = st.copy(msg = done)
            } catch (e: Exception) {
                st = st.copy(msg = e.message ?: "Ошибка")
            } finally {
                st = st.copy(busy = null)
                delay(400)              // усилитель подтверждает настройки своим отчётом
                load(page)
            }
        }
    }

    fun clearMsg() {
        st = st.copy(msg = null)
    }

    // ---------------- усилитель ----------------

    fun ampSet(key: String, value: Int) = act(Page.AMP, "Применяю…") { it.ampSet(key, value) }

    fun sleep(min: Int) = act(Page.AMP, "Применяю…", if (min > 0) "Таймер сна: $min мин" else "Таймер отменён") {
        it.sleepTimer(min)
    }

    fun saveAlarm(a: Alarm, tz: String) = act(Page.AMP, "Сохраняю…", "Будильник сохранён") {
        if (tz != st.amp?.tz) it.setTz(tz)
        it.saveAlarm(a)
    }

    fun flashAmpBuiltin() {
        val a = apiOf() ?: return
        if (st.logRunning) return
        scope.launch {
            st = st.copy(logTitle = "Прошивка усилителя", log = "Прошивка из DigiFox…\n", logRunning = true)
            try {
                a.flashAmpBuiltin { l -> st = st.copy(log = st.log + l + "\n") }
            } catch (e: Exception) {
                st = st.copy(log = st.log + "\nОшибка: ${e.message}\n")
            } finally {
                st = st.copy(logRunning = false)
                load(Page.AMP)
            }
        }
    }

    fun flashAmp(name: String, data: ByteArray) {
        val a = apiOf() ?: return
        if (st.logRunning) return
        scope.launch {
            st = st.copy(logTitle = "Прошивка усилителя", log = "Передаю $name (${data.size / 1024} КБ)…\n", logRunning = true)
            try {
                a.flashAmp(name, data) { l -> st = st.copy(log = st.log + l + "\n") }
            } catch (e: Exception) {
                st = st.copy(log = st.log + "\nОшибка: ${e.message}\n")
            } finally {
                st = st.copy(logRunning = false)
                load(Page.AMP)
            }
        }
    }

    // ---------------- I2S ----------------

    fun i2sSet(key: String, value: String) {
        val cur = st.i2s ?: return
        val reboot = key == "mode" || (key == "mclk" && cur.mode == "ext")
        act(Page.I2S, "Применяю…") {
            it.i2sSet(key, value)
            if (reboot) st = st.copy(needReboot = true)
        }
    }

    fun setSrc(mode: String) = act(Page.I2S, "Переключаю, плеер перезапускается…") { it.setSrc(mode) }

    fun setSrcFilter(key: String, value: String) = act(Page.I2S, "Применяю…", "Слышно через секунду") {
        it.setSrcFilter(key, value)
    }

    // ---------------- система ----------------

    fun reboot(after: () -> Unit) {
        val a = apiOf() ?: return
        scope.launch {
            try { a.reboot() } catch (_: Exception) {}
            st = st.copy(needReboot = false, msg = "Фокс перезагружается…")
            after()
        }
    }

    fun update(after: () -> Unit) {
        val a = apiOf() ?: return
        if (st.logRunning) return
        scope.launch {
            st = st.copy(logTitle = "Обновление прошивки", log = "Проверяю обновление…\n", logRunning = true)
            var reboot = false
            try {
                a.runUpdate { l ->
                    if (Regex("Перезагрузка через|reboot", RegexOption.IGNORE_CASE).containsMatchIn(l)) reboot = true
                    st = st.copy(log = st.log + l + "\n")
                }
            } catch (e: Exception) {
                // Фокс уходит в перезагрузку — связь рвётся, это нормально
                st = st.copy(log = st.log + if (reboot) "" else "\n[связь прервалась]\n")
            } finally {
                if (reboot) st = st.copy(log = st.log + "\nФокс перезагружается, приложение переподключится само.\n")
                st = st.copy(logRunning = false)
                after()
            }
        }
    }

    // ---------------- резервная копия ----------------

    /** Скачать копию настроек с Фокса и отдать её на запись в файл (write — в потоке IO) */
    fun saveBackup(write: (ByteArray) -> Unit) {
        val a = apiOf() ?: return
        if (st.logRunning) return
        scope.launch {
            st = st.copy(logTitle = "Резервная копия", log = "Получаю настройки с Фокса…\n", logRunning = true)
            try {
                val b = a.backup()
                withContext(Dispatchers.IO) { write(b) }
                st = st.copy(
                    log = st.log + "Сохранено (${(b.size + 1023) / 1024} КБ).\n\nВ файле: тактирование I2S и MCLK, перестановки, " +
                            "активный плеер, пересчёт частоты и его фильтр, тонкомпенсация, будильник, часовой пояс, " +
                            "список веб-радио, настройки UPnP-рендерера.\n"
                )
            } catch (e: Exception) {
                st = st.copy(log = st.log + "\nОшибка: ${e.message}\n")
            } finally {
                st = st.copy(logRunning = false)
            }
        }
    }

    /** Восстановить настройки из файла и перезагрузить Фокс */
    fun restore(data: ByteArray, after: () -> Unit) {
        val a = apiOf() ?: return
        if (st.logRunning) return
        scope.launch {
            st = st.copy(logTitle = "Восстановление настроек", log = "Передаю файл (${(data.size + 1023) / 1024} КБ)…\n", logRunning = true)
            var ok = false
            try {
                val from = a.restore(data)
                st = st.copy(log = st.log + "Настройки восстановлены" + (from?.let { " (копия DigiFox $it)" } ?: "") +
                        ".\nФокс перезагружается, приложение переподключится само.\n")
                ok = true
                try { a.reboot() } catch (_: Exception) {}
            } catch (e: Exception) {
                st = st.copy(log = st.log + "\nОшибка: ${e.message}\n")
            } finally {
                st = st.copy(logRunning = false)
                if (ok) after()
            }
        }
    }

    fun closeLog() {
        if (!st.logRunning) st = st.copy(logTitle = null, log = "")
    }
}
