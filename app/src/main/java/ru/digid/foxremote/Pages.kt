package ru.digid.foxremote

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DialogBg = Color(0xFF151515)

// ============================================================ общие элементы

@Composable
internal fun MainMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAmp: () -> Unit,
    onI2s: () -> Unit,
    onUpdate: () -> Unit,
    onReboot: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onDiag: () -> Unit,
    onSettings: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(DialogBg),
    ) {
        @Composable
        fun item(text: String, onClick: () -> Unit) = DropdownMenuItem(
            text = { Text(text, color = Fg, fontSize = 15.sp) },
            onClick = onClick,
        )
        item("Усилитель, будильник, таймер", onAmp)
        item("Настройки I2S", onI2s)
        item("Обновление прошивки", onUpdate)
        item("Перезагрузить Фокс", onReboot)
        item("Сохранить настройки в файл", onBackup)
        item("Восстановить настройки из файла", onRestore)
        item("Диагностика", onDiag)
        item("Адрес Фокса и уведомления", onSettings)
    }
}

@Composable
internal fun ConfirmDialog(text: String, yes: String, onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        containerColor = DialogBg,
        textContentColor = Fg,
        text = { Text(text, fontSize = 15.sp) },
        confirmButton = { TextButton(onClick = onYes) { Text(yes, color = Fg) } },
        dismissButton = { TextButton(onClick = onNo) { Text("ОТМЕНА", color = Dim) } },
    )
}

/** Журнал обновления Фокса / прошивки усилителя */
@Composable
internal fun LogDialog(vm: FoxViewModel) {
    val st = vm.adv.st
    val title = st.logTitle ?: return
    val scroll = rememberScrollState()
    LaunchedEffect(st.log) { scroll.animateScrollTo(scroll.maxValue) }
    AlertDialog(
        onDismissRequest = { vm.adv.closeLog() },
        containerColor = DialogBg,
        titleContentColor = Fg,
        textContentColor = Fg,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (st.logRunning) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = Fg, strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, fontSize = 17.sp)
            }
        },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 380.dp)
                    .border(1.dp, Line)
                    .verticalScroll(scroll)
                    .padding(8.dp)
            ) {
                Text(st.log, fontFamily = Mono, fontSize = 11.sp, color = Fg)
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.adv.closeLog() }, enabled = !st.logRunning) {
                Text(if (st.logRunning) "ИДЁТ…" else "ЗАКРЫТЬ", color = if (st.logRunning) Faint else Fg)
            }
        },
    )
}

@Composable
private fun PageFrame(vm: FoxViewModel, title: String, content: @Composable ColumnScope.() -> Unit) {
    val st = vm.adv.st
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { vm.openPage(Page.MAIN) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹", color = Fg, fontSize = 24.sp, modifier = Modifier.padding(end = 12.dp))
            Text(title, color = Fg, fontSize = 18.sp, fontWeight = FontWeight.Light, letterSpacing = 6.sp)
        }
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            if (st.busy != null) {
                CircularProgressIndicator(Modifier.size(14.dp), color = Fg, strokeWidth = 1.5.dp)
                Spacer(Modifier.width(8.dp))
                Text(st.busy, color = Fg, fontSize = 13.sp)
            } else if (st.msg != null) {
                Text(st.msg, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Panel(caption: String, value: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .border(1.dp, Line)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(caption, style = Caption, modifier = Modifier.weight(1f))
            if (value != null) Text(value, color = Fg, fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
}

/** Ряд взаимоисключающих кнопок (как на странице веб-интерфейса) */
@Composable
private fun Choice(
    options: List<Pair<String, String>>,   // значение -> подпись
    current: String?,
    enabled: Boolean,
    disabled: Set<String> = emptySet(),
    onPick: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) ->
            HifiButton(
                text = label,
                active = current == v,
                enabled = enabled && v !in disabled,
                modifier = Modifier.weight(1f),
                onClick = { if (current != v) onPick(v) },
            )
        }
    }
}

/** Выпадающий список в стиле кнопки */
@Composable
private fun Picker(options: List<Pair<String, String>>, current: String, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        HifiButton(
            text = (options.firstOrNull { it.first == current }?.second ?: current) + "  ▾",
            active = false,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(DialogBg)) {
            options.forEach { (v, label) ->
                DropdownMenuItem(
                    text = { Text(label, color = if (v == current) Fg else Dim) },
                    onClick = { open = false; onPick(v) },
                )
            }
        }
    }
}

// ============================================================ усилитель

private val ALARM_SOURCES = listOf(
    "keep" to "Последний плеер",
    "aplayer" to "Веб-радио",
    "mpd" to "MPD",
    "lms" to "Squeezelite",
    "aprenderer" to "UPnP Renderer",
    "raat" to "Roon Ready",
    "qobuz" to "Qobuz Connect",
    "spotify" to "Spotify Connect",
)

/** Часовые пояса как в веб-интерфейсе: POSIX TZ (знак обратный) */
private val TIME_ZONES = (-12..14).map { h ->
    val v = if (h == 3) "MSK-3" else "UTC" + (if (h > 0) "-" else if (h < 0) "+" else "") + Math.abs(h)
    val label = "UTC" + (if (h > 0) "+" else if (h < 0) "−" else "±") + Math.abs(h) + (if (h == 3) " (Москва)" else "")
    v to label
}

private val DAYS = listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AmpPage(vm: FoxViewModel) {
    val st = vm.adv.st
    val amp = st.amp
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    PageFrame(vm, "DIGID D1") {
        when {
            !st.ampSupported -> {
                Text("На Фоксе нет страницы усилителя — обновите DigiFox.", color = Dim, fontSize = 14.sp); return@PageFrame
            }
            amp == null -> {
                Text("Загрузка…", color = Dim, fontSize = 14.sp); return@PageFrame
            }
        }
        amp!!
        val cfg = amp.cfg
        val can = amp.present && cfg != null && st.busy == null
        fun cur(k: String) = cfg?.get(k)?.toString()
        if (!amp.present) Text("Усилитель не на связи", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp))
        else if (cfg == null) Text("Настройки придут, когда усилитель выйдет на связь (прошивка 1.1+)", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))

        Panel("ПРЕОБРАЗОВАТЕЛЬ ЧАСТОТЫ AK4137") {
            Label("Фильтр")
            Choice(listOf("0" to "SHARP", "1" to "SLOW"), cur("filter"), can) { vm.adv.ampSet("filter", it.toInt()) }
            Label("Задержка фильтра")
            Choice(listOf("0" to "NORMAL", "1" to "SHORT"), cur("delay"), can) { vm.adv.ampSet("delay", it.toInt()) }
            Label("Уровень DSD")
            Choice(listOf("0" to "0 dB", "1" to "+6 dB"), cur("dsdgain"), can) { vm.adv.ampSet("dsdgain", it.toInt()) }
        }
        Panel("РЕЖИМ AX5689") {
            Choice(listOf("0" to "ZCM", "1" to "BD"), cur("mode"), can) { vm.adv.ampSet("mode", it.toInt()) }
        }
        Panel("ПИТАНИЕ") {
            Label("Автовыключение без сигнала")
            Choice(listOf("0" to "ВЫКЛ", "1" to "20 МИН", "2" to "60 МИН"), cur("standby"), can) { vm.adv.ampSet("standby", it.toInt()) }
            Label("Включаться, когда Фокс начал играть")
            Choice(listOf("0" to "ВЫКЛ", "1" to "ВКЛ"), cur("autoon"), can) { vm.adv.ampSet("autoon", it.toInt()) }
        }

        // ---- пределы громкости (прошивка усилителя 1.3+)
        val volOk = cfg != null && (cfg["vmax"] ?: -1) >= 0
        Panel("ГРОМКОСТЬ") {
            fun dbOf(k: String): String {
                val pos = cfg?.get(k) ?: 0
                return if (pos <= 0) "0" else (pos - amp.max).toString()
            }
            fun send(k: String, db: String) {
                val d = db.toInt()
                vm.adv.ampSet(k, if (d == 0) 0 else amp.max + d)
            }
            val vmaxOpts = listOf("0" to "Без предела") + listOf(-3, -6, -10, -15, -20, -25, -30).map { "$it" to "−${-it} dB" }
            val vonOpts = listOf("0" to "Как была") + listOf(-10, -15, -20, -25, -30, -35, -40).map { "$it" to "−${-it} dB" }
            Label("Предел громкости")
            Picker(vmaxOpts, dbOf("vmax"), volOk && can) { send("vmax", it) }
            Label("При включении не громче")
            Picker(vonOpts, dbOf("von"), volOk && can) { send("von", it) }
            Hint(
                if (cfg != null && !volOk) "Нужна прошивка усилителя 1.3 или новее (раздел «Прошивка усилителя» ниже)."
                else "Предел — громче регулятор, пульт и приложение не дадут. При включении усилитель убавит громкость до заданной, если она была выше."
            )
        }

        // ---- таймер сна
        val left = amp.sleepLeft
        Panel("ТАЙМЕР СНА", if (left > 0) "выключится через ${(left + 59) / 60} мин" else "выключен") {
            Choice(listOf("15" to "15 МИН", "30" to "30 МИН", "60" to "1 ЧАС", "90" to "1,5 ЧАСА"), null, st.busy == null) {
                vm.adv.sleep(it.toInt())
            }
            if (left > 0) {
                Spacer(Modifier.height(8.dp))
                HifiButton("ОТМЕНИТЬ ТАЙМЕР", false, st.busy == null, Modifier.fillMaxWidth()) { vm.adv.sleep(0) }
            }
        }

        // ---- будильник: правки держим локально, пока не нажали «Сохранить»
        var edit by remember { mutableStateOf<Alarm?>(null) }
        var tzEdit by remember { mutableStateOf<String?>(null) }
        var pickTime by remember { mutableStateOf(false) }
        val al = edit ?: amp.alarm
        val tz = tzEdit ?: amp.tz
        Panel("БУДИЛЬНИК", if (amp.timeOk && amp.now.isNotBlank()) "сейчас ${amp.now}" else "время не задано") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .border(1.dp, Line)
                        .clickable { pickTime = true },
                    contentAlignment = Alignment.Center,
                ) { Text(al.time, color = Fg, fontFamily = Mono, fontSize = 24.sp) }
                Spacer(Modifier.width(8.dp))
                HifiButton(if (al.on) "ВКЛ" else "ВЫКЛ", al.on, true, Modifier.width(110.dp)) {
                    edit = al.copy(on = !al.on)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DAYS.forEachIndexed { i, d ->
                    val n = (i + 1).toString()
                    val on = al.days.contains(n)
                    HifiButton(d, on, true, Modifier.weight(1f)) {
                        val days = (1..7).map { it.toString() }.filter { if (it == n) !on else al.days.contains(it) }.joinToString("")
                        edit = al.copy(days = days)
                    }
                }
            }
            Label("Источник")
            Picker(ALARM_SOURCES, if (ALARM_SOURCES.any { it.first == al.src }) al.src else "keep", true) { edit = al.copy(src = it) }
            Label("Громкость ${al.vol} dB")
            Slider(
                value = al.vol.toFloat(),
                onValueChange = { edit = al.copy(vol = Math.round(it)) },
                valueRange = -60f..0f,
                colors = SliderDefaults.colors(thumbColor = Fg, activeTrackColor = Fg, inactiveTrackColor = Line),
            )
            Hint("Усилитель включится, запустит источник и за минуту плавно поднимет громкость с −20 dB от выбранной. Qobuz и Spotify сами не заиграют — их запускает телефон.")
            Label("Часовой пояс")
            Picker(TIME_ZONES, tz, true) { tzEdit = it }
            Spacer(Modifier.height(10.dp))
            HifiButton("СОХРАНИТЬ БУДИЛЬНИК", edit != null || tzEdit != null, st.busy == null, Modifier.fillMaxWidth()) {
                vm.adv.saveAlarm(al, tz)
                edit = null; tzEdit = null
            }
        }
        if (pickTime) {
            val parts = al.time.split(":")
            val tp = rememberTimePickerState(parts[0].toIntOrNull() ?: 7, parts.getOrNull(1)?.toIntOrNull() ?: 0, true)
            AlertDialog(
                onDismissRequest = { pickTime = false },
                containerColor = DialogBg,
                text = {
                    TimePicker(
                        state = tp,
                        colors = TimePickerDefaults.colors(
                            clockDialColor = Color(0xFF222222),
                            selectorColor = Fg,
                            clockDialSelectedContentColor = Bg,
                            clockDialUnselectedContentColor = Fg,
                            timeSelectorSelectedContainerColor = Fg,
                            timeSelectorUnselectedContainerColor = Color(0xFF222222),
                            timeSelectorSelectedContentColor = Bg,
                            timeSelectorUnselectedContentColor = Fg,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        edit = al.copy(time = "%02d:%02d".format(tp.hour, tp.minute))
                        pickTime = false
                    }) { Text("ГОТОВО", color = Fg) }
                },
                dismissButton = { TextButton(onClick = { pickTime = false }) { Text("ОТМЕНА", color = Dim) } },
            )
        }

        // ---- прошивка усилителя
        var fw by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
        var askFlash by remember { mutableStateOf(false) }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                fw = withContext(Dispatchers.IO) {
                    try {
                        val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (c.moveToFirst() && i >= 0) c.getString(i) else null
                        } ?: "firmware.bin"
                        val data = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (data == null) null else name to data
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
        var askBuiltin by remember { mutableStateOf(false) }
        Panel("ПРОШИВКА УСИЛИТЕЛЯ", amp.ver?.let { "версия $it" } ?: if (amp.present) "версия до 1.1" else null) {
            if (amp.fwUpdate && amp.fwBuiltin != null) {
                HifiButton("ОБНОВИТЬ УСИЛИТЕЛЬ ДО ${amp.fwBuiltin}", true, !st.logRunning, Modifier.fillMaxWidth()) { askBuiltin = true }
                Spacer(Modifier.height(8.dp))
            }
            HifiButton(
                fw?.let { "${it.first} · ${it.second.size / 1024} КБ" } ?: "ВЫБРАТЬ ФАЙЛ .BIN…",
                false, !st.logRunning, Modifier.fillMaxWidth(),
            ) { pick.launch("*/*") }
            Spacer(Modifier.height(8.dp))
            val okSize = fw?.second?.size?.let { it in 4096..63488 } ?: false
            HifiButton("ПРОШИТЬ", false, okSize && !st.logRunning, Modifier.fillMaxWidth()) { askFlash = true }
            if (fw != null && !okSize) Hint("Это не похоже на прошивку усилителя: нужен .bin от 4 до 62 КБ.")
            Hint("Файл Test_i2c.bin из папки Debug проекта в STM32CubeIDE. Настройки и коды пульта сохранятся. Во время прошивки усилитель выключен.")
        }
        if (askBuiltin) ConfirmDialog(
            text = "Обновить прошивку усилителя до ${amp.fwBuiltin}? Около минуты усилитель будет выключен.",
            yes = "ОБНОВИТЬ",
            onYes = { askBuiltin = false; vm.adv.flashAmpBuiltin() },
            onNo = { askBuiltin = false },
        )
        if (askFlash) ConfirmDialog(
            text = "Прошить усилитель файлом ${fw?.first}? Около минуты усилитель будет выключен.",
            yes = "ПРОШИТЬ",
            onYes = { askFlash = false; fw?.let { vm.adv.flashAmp(it.first, it.second) }; fw = null },
            onNo = { askFlash = false },
        )
    }
}

// ============================================================ I2S

@Composable
internal fun I2sPage(vm: FoxViewModel) {
    val st = vm.adv.st
    val i = st.i2s
    var askReboot by remember { mutableStateOf(false) }

    // DigiD D1: нужен только стерео-выход. Если остался 8CH / L/R / ±L/±R — вернуть STD один раз
    var fixedSub by remember { mutableStateOf(false) }
    LaunchedEffect(i?.submode) {
        if (i != null && i.submode.isNotBlank() && i.submode != "std" && !fixedSub) {
            fixedSub = true
            vm.adv.i2sSet("submode", "std")
        }
    }

    PageFrame(vm, "I2S") {
        if (st.needReboot) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp)
                    .border(1.dp, Fg)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Тактирование изменится после перезагрузки Фокса", color = Fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
                HifiButton("ПЕРЕЗАГРУЗИТЬ", true, true, Modifier.width(140.dp)) { askReboot = true }
            }
        }
        if (i == null) {
            Text("Загрузка…", color = Dim, fontSize = 14.sp); return@PageFrame
        }
        val can = st.busy == null

        if (st.srcSupported) {
            val src = st.src
            val name = when {
                src?.ak == false -> "AK4137 в усилителе не найдена"
                src?.mode == "fox" -> "на Фоксе, 192 кГц"
                src != null -> "в усилителе"
                else -> null
            }
            Panel("ПЕРЕСЧЁТ ЧАСТОТЫ", name) {
                Choice(
                    listOf("ak4137" to "AK4137", "fox" to "ФОКС"), src?.mode, can && src != null,
                    disabled = if (src?.ak == false) setOf("ak4137") else emptySet(),
                ) { vm.adv.setSrc(it) }
                val f = src?.filter
                if (src?.mode == "fox" && f != null) {
                    Label("Фаза фильтра")
                    Choice(listOf("lin" to "ЛИНЕЙНАЯ", "int" to "ПРОМЕЖ.", "min" to "МИНИМАЛ."), f.phase, can) { vm.adv.setSrcFilter("phase", it) }
                    Label("Срез")
                    Choice(listOf("steep" to "КРУТОЙ", "std" to "ОБЫЧНЫЙ", "slow" to "ПОЛОГИЙ"), f.rolloff, can) { vm.adv.setSrcFilter("rolloff", it) }
                    Label("Запас по уровню")
                    Choice(listOf("0" to "0 dB", "-3" to "−3 dB"), f.gain, can) { vm.adv.setSrcFilter("gain", it) }
                    if (f.loudness != null) {
                        Label("Тонкомпенсация")
                        Choice(listOf("off" to "ВЫКЛ", "on" to "ВКЛ"), f.loudness, can) { vm.adv.setSrcFilter("loudness", it) }
                        Hint("На тихой громкости ухо хуже слышит низ. Тонкомпенсация мягко поднимает самый низ (ниже 80 Гц) тем сильнее, чем тише громкость усилителя: в верхних 20 дБ шкалы звук не меняется, на −40 дБ подъём около 4 дБ, не больше 8 дБ. Середина и верх не трогаются.")
                    }
                    Hint("Слышно примерно через секунду. Минимальная фаза — без «звона» перед атакой (как SHORT у AK4137). Пологий срез — мягче на самом верху (как SLOW). Запас −3 dB убирает перегрузку пиков между отсчётами; громкость добирается усилителем.")
                }
                Hint("AK4137 — Фокс отдаёт звук как есть, частоту пересчитывает AK4137 в усилителе. ФОКС — Фокс сам переводит всё в PCM 192 кГц / 32 бит: PCM через soxr, DSD64–DSD256 через дециматор; DSD512 в этом режиме не поддерживается. Переключение перезапускает плеер.")
            }
        }
        Panel("ТАКТИРОВАНИЕ", if (i.mode == "ext") "EXT — внешний генератор" else "PLL — встроенный") {
            Choice(listOf("pll" to "PLL", "ext" to "EXT"), i.mode, can) { vm.adv.i2sSet("mode", it) }
            Hint("PLL — встроенный синтезатор RV1106, вывод MCLK работает как выход. EXT — внешний генератор, MCLK — вход. Применяется после перезагрузки.")
        }
        Panel("MCLK", "${i.mclk} × FS") {
            Choice(listOf("512" to "512 × FS", "1024" to "1024 × FS"), i.mclk, can) { vm.adv.i2sSet("mclk", it) }
            Hint("В режиме PLL меняется сразу, в режиме EXT — после перезагрузки.")
        }
        Panel("ПЕРЕСТАНОВКИ") {
            SwitchRow("Каналы PCM", "Поменять местами левый и правый", i.pcmSwap, can) { vm.adv.i2sSet("pcm_swap", if (it) "1" else "0") }
            SwitchRow("Каналы DSD", "Поменять физические линии DSD", i.dsdSwap, can) { vm.adv.i2sSet("dsd_swap", if (it) "1" else "0") }
            SwitchRow("Частоты 44,1 / 48", "Поменять местами частотные домены 44,1 и 48 кГц", i.freqSwap, can) { vm.adv.i2sSet("freq_swap", if (it) "1" else "0") }
        }
    }
    if (askReboot) ConfirmDialog(
        text = "Перезагрузить Фокс?",
        yes = "ПЕРЕЗАГРУЗИТЬ",
        onYes = { askReboot = false; vm.rebootFox(); vm.openPage(Page.MAIN) },
        onNo = { askReboot = false },
    )
}

@Composable
private fun SwitchRow(title: String, desc: String, on: Boolean, enabled: Boolean, onSet: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Fg, fontSize = 14.sp)
            Text(desc, color = Dim, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        HifiButton(if (on) "ВКЛ" else "ВЫКЛ", on, enabled, Modifier.width(84.dp)) { onSet(!on) }
    }
}

// ============================================================ резервная копия

/** Сохранение / восстановление настроек: request = "save" | "restore" запускает выбор файла */
@Composable
internal fun BackupActions(vm: FoxViewModel, request: String?, onHandled: () -> Unit) {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) vm.adv.saveBackup { b ->
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(b) } ?: throw Exception("Не удалось открыть файл")
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val data = withContext(Dispatchers.IO) {
                try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }
            }
            pending = data
        }
    }
    LaunchedEffect(request) {
        when (request) {
            "save" -> save.launch("digifox-settings-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()) + ".json")
            "restore" -> open.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*"))
        }
        if (request != null) onHandled()
    }
    pending?.let { data ->
        ConfirmDialog(
            text = "Восстановить настройки из файла? Текущие настройки Фокса заменятся, затем он перезагрузится.",
            yes = "ВОССТАНОВИТЬ",
            onYes = { pending = null; vm.restoreFox(data) },
            onNo = { pending = null },
        )
    }
}

// ============================================================ диагностика

@Composable
internal fun DiagPage(vm: FoxViewModel) {
    val st = vm.adv.st
    val ctx = LocalContext.current
    PageFrame(vm, "ДИАГНОСТИКА") {
        val d = st.diag
        if (!st.diagSupported) {
            Text("Прошивка Фокса без страницы диагностики — обновите DigiFox.", color = Dim, fontSize = 14.sp); return@PageFrame
        }
        if (d == null) {
            Text("Загрузка…", color = Dim, fontSize = 14.sp); return@PageFrame
        }
        d.sections.forEach { (title, rows) ->
            Panel(title) {
                rows.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(k, color = Dim, fontSize = 13.sp, modifier = Modifier.weight(0.42f))
                        Text(v, color = Fg, fontSize = 13.sp, modifier = Modifier.weight(0.58f))
                    }
                }
            }
        }
        d.logs.forEach { (title, text) ->
            Panel(title) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .border(1.dp, Line)
                        .verticalScroll(rememberScrollState(Int.MAX_VALUE))
                        .padding(8.dp)
                ) {
                    Text(text, fontFamily = Mono, fontSize = 10.sp, color = Fg)
                }
            }
        }
        HifiButton("ПОДЕЛИТЬСЯ ОТЧЁТОМ", false, true, Modifier.fillMaxWidth()) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "DigiFox — диагностика")
                putExtra(Intent.EXTRA_TEXT, d.text)
            }
            ctx.startActivity(Intent.createChooser(send, "Отчёт диагностики"))
        }
        Hint("Обновляется каждые 10 секунд. Отчёт — весь текст этой страницы, его можно приложить к вопросу о неисправности.")
    }
}
