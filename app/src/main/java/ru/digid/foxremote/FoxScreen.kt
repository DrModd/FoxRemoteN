package ru.digid.foxremote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Строгий монохром в духе классической hi-fi техники
private val Bg = Color(0xFF0B0B0B)
private val Fg = Color(0xFFEDEDED)
private val Dim = Color(0xFF7A7A7A)
private val Line = Color(0xFF2A2A2A)
private val Faint = Color(0xFF444444)

private val Caption = TextStyle(color = Dim, fontSize = 11.sp, letterSpacing = 2.sp)
private val Mono = FontFamily.Monospace

@Composable
fun FoxScreen(vm: FoxViewModel) {
    val ui = vm.ui
    var showSettings by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Header(ui, onClick = { showSettings = true })
        Spacer(Modifier.height(16.dp))
        Display(ui)
        Spacer(Modifier.height(8.dp))
        StatusLine(ui)
        Spacer(Modifier.height(20.dp))
        if (ui.amp != null) {
            PowerRow(ui, onPower = vm::togglePower)
            Spacer(Modifier.height(16.dp))
        }
        VolumeBlock(ui, onVolume = vm::setVolume, onMute = vm::toggleMute)
        Spacer(Modifier.height(24.dp))
        Text("РЕЖИМ", style = Caption)
        Spacer(Modifier.height(8.dp))
        ModeSwitch(ui, onUsb = vm::setUsb)
        Spacer(Modifier.height(24.dp))
        Text("ПЛЕЕРЫ", style = Caption)
        Spacer(Modifier.height(8.dp))
        PlayerGrid(ui, onSelect = vm::selectPlayer)
        Spacer(Modifier.height(20.dp))
        Text(
            "Кнопки громкости телефона управляют громкостью Фокса",
            style = Caption.copy(letterSpacing = 0.sp, fontSize = 12.sp),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }

    if (showSettings) {
        SettingsDialog(
            ui = ui,
            onDismiss = { showSettings = false },
            onSave = { vm.setHost(it); showSettings = false },
            onDiscover = { vm.discover(); showSettings = false },
            onNotify = vm::setNotify,
        )
    }
}

@Composable
private fun Header(ui: UiState, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "PUREFOX",
            color = Fg,
            fontSize = 18.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 6.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            when {
                ui.discovering -> "поиск…"
                ui.host.isBlank() -> "нет адреса"
                else -> ui.host
            },
            color = Dim,
            fontSize = 12.sp,
            fontFamily = Mono,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (ui.connected) Fg else Bg)
                .border(1.dp, if (ui.connected) Fg else Faint, CircleShape),
        )
    }
}

@Composable
private fun Display(ui: UiState) {
    val source = when {
        !ui.connected -> "—"
        ui.usb == true -> "USB → I2S"
        ui.status.service.isBlank() -> "Нет плеера"
        else -> playerLabel(ui.status.service)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Line)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text("ИСТОЧНИК", style = Caption)
        Spacer(Modifier.height(4.dp))
        Text(
            source,
            color = if (ui.connected) Fg else Dim,
            fontSize = 28.sp,
            fontWeight = FontWeight.Light,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ui.rate ?: " ",
                color = Fg,
                fontFamily = Mono,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                when (ui.usb) {
                    true -> "USB"
                    false -> "СЕТЬ"
                    null -> ""
                },
                style = Caption,
            )
        }
    }
}

@Composable
private fun StatusLine(ui: UiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ui.busy != null || ui.discovering) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = Fg,
                strokeWidth = 1.5.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(ui.busy ?: "Поиск Фокса в сети…", color = Fg, fontSize = 13.sp)
        } else if (ui.error != null) {
            Text(ui.error, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun VolumeBlock(ui: UiState, onVolume: (Int) -> Unit, onMute: () -> Unit) {
    val amp = ui.amp
    val usbFixed = amp == null && ui.usb == true
    val enabled = ui.connected && (if (amp != null) amp.power else ui.status.volumeAvailable && !usbFixed)
    val max = amp?.max ?: 100
    val vol = ui.localVolume ?: amp?.pos ?: ui.status.volume
    val muted = amp?.muted ?: ui.status.muted

    // Число и единица: у усилителя в дБ (pos - max), иначе проценты Фокса
    val (number, unit) = when {
        !ui.connected || vol < 0 -> "--" to ""
        amp != null && amp.db -> (vol - amp.max).toString() to "dB"
        amp != null -> vol.toString() to ""
        else -> vol.toString() to "%"
    }

    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(if (amp != null) "ГРОМКОСТЬ УСИЛИТЕЛЯ" else "ГРОМКОСТЬ", style = Caption)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    number,
                    color = if (enabled && !muted) Fg else Dim,
                    fontFamily = Mono,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Light,
                )
                Text(
                    unit,
                    color = Dim,
                    fontFamily = Mono,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
                )
            }
        }
        HifiButton(
            text = "MUTE",
            active = muted,
            enabled = ui.connected && (if (amp != null) amp.power else ui.status.volumeAvailable),
            modifier = Modifier
                .width(96.dp)
                .padding(bottom = 12.dp),
            onClick = onMute,
        )
    }
    Slider(
        value = vol.coerceIn(0, max).toFloat(),
        onValueChange = { onVolume(Math.round(it)) },
        valueRange = 0f..max.toFloat(),
        enabled = enabled,
        colors = SliderDefaults.colors(
            thumbColor = Fg,
            activeTrackColor = Fg,
            inactiveTrackColor = Line,
            disabledThumbColor = Faint,
            disabledActiveTrackColor = Faint,
            disabledInactiveTrackColor = Line,
        ),
    )
    when {
        amp != null && !amp.power -> Text(
            "Усилитель в дежурном режиме",
            color = Dim,
            fontSize = 12.sp,
        )
        amp != null -> Text(
            "Фокс на 100% (bit-perfect), громкость регулирует AX5689",
            color = Dim,
            fontSize = 12.sp,
        )
        ui.connected && usbFixed -> Text(
            "USB → I2S: громкость фиксирована на 100% (bit-perfect). Регулируйте на ПК или в усилителе.",
            color = Dim,
            fontSize = 12.sp,
        )
        ui.connected && !ui.status.volumeAvailable ->
            Text("Регулировка громкости в этом режиме недоступна", color = Dim, fontSize = 12.sp)
    }
}

@Composable
private fun PowerRow(ui: UiState, onPower: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("УСИЛИТЕЛЬ DIGID D1", style = Caption)
            Text(
                if (ui.ampOn) "Включён" else "Дежурный режим",
                color = if (ui.ampOn) Fg else Dim,
                fontSize = 16.sp,
            )
        }
        HifiButton(
            text = "POWER",
            active = ui.ampOn,
            enabled = ui.connected && ui.busy == null,
            modifier = Modifier.width(110.dp),
            onClick = onPower,
        )
    }
}

@Composable
private fun ModeSwitch(ui: UiState, onUsb: (Boolean) -> Unit) {
    val enabled = ui.connected && ui.busy == null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HifiButton(
            text = "СЕТЬ",
            active = ui.usb == false,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = { onUsb(false) },
        )
        HifiButton(
            text = "USB → I2S",
            active = ui.usb == true,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = { onUsb(true) },
        )
    }
}

@Composable
private fun PlayerGrid(ui: UiState, onSelect: (String) -> Unit) {
    val enabled = ui.connected && ui.busy == null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PLAYERS.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { p ->
                    HifiButton(
                        text = p.label,
                        active = ui.usb == false && ui.status.service == p.id,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(p.id) },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Кнопка в стиле передней панели: рамка, активная — инверсная */
@Composable
private fun HifiButton(
    text: String,
    active: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = if (active) Fg else Bg
    val fg = when {
        active -> Bg
        enabled -> Fg
        else -> Faint
    }
    Box(
        modifier = modifier
            .height(48.dp)
            .background(bg)
            .border(1.dp, if (active) Fg else Line)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            color = fg,
            fontSize = 12.sp,
            letterSpacing = 1.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

@Composable
private fun SettingsDialog(
    ui: UiState,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDiscover: () -> Unit,
    onNotify: (Boolean) -> Unit,
) {
    var host by remember { mutableStateOf(ui.host) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onNotify(granted)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF151515),
        titleContentColor = Fg,
        textContentColor = Fg,
        title = { Text("Адрес Фокса") },
        text = {
            Column {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    singleLine = true,
                    placeholder = { Text("192.168.1.50 или purefox.local", color = Dim) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Fg,
                        unfocusedTextColor = Fg,
                        focusedBorderColor = Fg,
                        unfocusedBorderColor = Line,
                        cursorColor = Fg,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Найти: поиск по сети (mDNS). Телефон должен быть в той же сети, что и Фокс.",
                    color = Dim,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = ui.notify,
                        onCheckedChange = { on ->
                            if (on && Build.VERSION.SDK_INT >= 33) {
                                askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                onNotify(on)
                            }
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Fg,
                            uncheckedColor = Dim,
                            checkmarkColor = Bg,
                        ),
                    )
                    Text("Кнопки в шторке уведомлений", color = Fg, fontSize = 14.sp)
                }
                Text(
                    "Виджет: долгое нажатие на рабочем столе → Виджеты → Fox Remote.",
                    color = Dim,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(host) }) { Text("СОХРАНИТЬ", color = Fg) }
        },
        dismissButton = {
            TextButton(onClick = onDiscover) { Text("НАЙТИ", color = Dim) }
        },
    )
}
