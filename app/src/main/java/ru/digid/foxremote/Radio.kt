package ru.digid.foxremote

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Веб-радио (APlayer на Фоксе): разделы -> станции, нажатие включает станцию,
 * избранное для играющей станции, переход к редактору radio_edit.php в браузере.
 */
@Composable
internal fun RadioPage(vm: FoxViewModel) {
    val ui = vm.ui
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var section by rememberSaveable { mutableStateOf<String?>(null) }
    var sections by remember { mutableStateOf<List<RadioSection>?>(null) }
    var stations by remember { mutableStateOf<List<RadioStation>?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var unsupported by remember { mutableStateOf(false) }

    BackHandler(enabled = section != null) { section = null; filter = "" }

    fun loadSections() = scope.launch {
        val a = vm.foxApi() ?: return@launch
        try {
            sections = a.radioSections()
        } catch (e: FoxException) {
            if (e.code == 404) unsupported = true else msg = "Нет связи с Фоксом"
        } catch (e: Exception) {
            msg = "Нет связи с Фоксом"
        }
    }

    LaunchedEffect(Unit) { loadSections() }
    LaunchedEffect(section) {
        stations = null
        val s = section ?: return@LaunchedEffect
        val a = vm.foxApi() ?: return@LaunchedEffect
        try { stations = a.radioStations(s) } catch (e: Exception) { msg = "Нет связи с Фоксом" }
    }

    fun act(text: String, done: String, block: suspend (FoxApi) -> String?) {
        val a = vm.foxApi() ?: return
        if (busy != null) return
        scope.launch {
            busy = text; msg = null
            msg = try { block(a)?.let { "Не получилось: $it" } ?: done } catch (e: Exception) { "Нет связи с Фоксом" }
            busy = null
        }
    }

    val radioOn = ui.usb == false && ui.status.service == "aplayer"
    val listState = rememberLazyListState()
    LaunchedEffect(section, filter) { listState.scrollToItem(0) }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 20.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { if (section != null) { section = null; filter = "" } else vm.openPage(Page.MAIN) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("‹", color = Fg, fontSize = 24.sp, modifier = Modifier.padding(end = 12.dp))
                Text(
                    section ?: "РАДИО", color = Fg, fontSize = 18.sp, fontWeight = FontWeight.Light,
                    letterSpacing = if (section == null) 6.sp else 0.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy != null) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = Fg, strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(busy!!, color = Fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else if (msg != null) {
                    Text(msg!!, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (unsupported) {
            item { Text("Прошивка Фокса без списка станций — обновите DigiFox.", color = Dim, fontSize = 14.sp) }
            return@LazyColumn
        }

        if (section == null) {
            // ---- что играет и избранное ----
            item {
                val t = ui.track
                if (radioOn && t != null) {
                    Text("СЕЙЧАС", style = Caption)
                    Spacer(Modifier.height(6.dp))
                    Text(t.artist, color = Fg, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (t.title.isNotBlank() && t.title != t.artist)
                        Text(t.title, color = Dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HifiButton("★ В избранное", false, radioOn, Modifier.weight(1f)) {
                        act("Добавляю в избранное…", "Добавлено в !Favorites") { it.radioFavorite(true).also { loadSections() } }
                    }
                    HifiButton("Убрать из избранного", false, radioOn, Modifier.weight(1f)) {
                        act("Убираю из избранного…", "Убрано из !Favorites") { it.radioFavorite(false).also { loadSections() } }
                    }
                }
                Spacer(Modifier.height(8.dp))
                HifiButton("Редактировать список ↗", false, ui.host.isNotBlank(), Modifier.fillMaxWidth()) {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${ui.host}/radio_edit.php")))
                    } catch (_: Exception) {}
                }
                Spacer(Modifier.height(20.dp))
                Text("РАЗДЕЛЫ", style = Caption)
                Spacer(Modifier.height(4.dp))
            }
            val list = sections
            if (list == null) {
                item { Text("Загрузка…", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp)) }
            } else {
                items(list, key = { it.name }) { s ->
                    ListRow(s.name, s.count.toString()) { section = s.name; filter = "" }
                }
            }
        } else {
            // ---- станции раздела ----
            item {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    placeholder = { Text("Поиск", color = Dim) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Fg, unfocusedTextColor = Fg,
                        focusedBorderColor = Fg, unfocusedBorderColor = Line, cursorColor = Fg,
                    ),
                )
                Spacer(Modifier.height(6.dp))
            }
            val list = stations
            if (list == null) {
                item { Text("Загрузка…", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp)) }
            } else {
                val f = filter.trim()
                val shown = if (f.isEmpty()) list else list.filter { it.name.contains(f, ignoreCase = true) }
                if (shown.isEmpty()) item {
                    Text(if (list.isEmpty()) "В разделе нет станций" else "Ничего не найдено",
                        color = Dim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp))
                }
                items(shown, key = { it.name + "\u0000" + it.url }) { st ->
                    val playing = radioOn && ui.track?.artist?.let { a ->
                        a.isNotBlank() && (st.name.startsWith(a, ignoreCase = true) || a.startsWith(st.name, ignoreCase = true))
                    } == true
                    ListRow(st.name, if (st.fromPlaylist) "плейлист" else null, mark = playing) {
                        val sec = section ?: return@ListRow
                        act("Включаю: ${st.name}…", "Играет: ${st.name}") { it.radioPlay(sec, st.name) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ListRow(title: String, right: String?, mark: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (if (mark) "▶  " else "") + title,
            color = Fg, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (mark) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (right != null) Text(right, color = Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
    }
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(Line))
}
