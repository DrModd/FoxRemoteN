package ru.digid.foxremote

import android.content.Intent
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Яндекс Музыка на Фоксе (yandex.php + служба ymusicd, играет MPD):
 * вход по токену, «Моя волна» и станции, «Мне нравится», плейлисты, поиск,
 * альбомы и исполнители, что играет — с управлением, ♥ и ✕, качество.
 */

/** Что открыто внутри экрана: главная, результаты поиска, список треков */
private sealed class YmView {
    object Home : YmView()
    data class Search(val q: String) : YmView()
    data class Tracks(val api: String, val title: String) : YmView()
}

/** Карточка: альбом, исполнитель, плейлист или станция */
private data class YmCard(val title: String, val sub: String, val cover: String, val api: String?, val seed: String?)

/** Адрес картинки Яндекса: "avatars.yandex.net/…/%%" -> https://…/200x200 */
private fun ymImg(uri: String, size: String = "100x100"): String {
    if (uri.isBlank()) return ""
    val u = uri.replace("%%", size)
    return if (u.startsWith("http")) u else "https://$u"
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun albumCard(o: JSONObject) = YmCard(
    o.optString("title"), listOf(o.optString("artist"), o.optString("year")).filter { it.isNotBlank() && it != "0" }.joinToString(" · "),
    o.optString("cover"), "album&id=" + o.optString("id"), null,
)
private fun artistCard(o: JSONObject) = YmCard(o.optString("name"), "исполнитель", o.optString("cover"), "artist&id=" + o.optString("id"), null)
private fun playlistCard(o: JSONObject) = YmCard(
    o.optString("title"), listOf(o.optString("owner"), o.optInt("n").takeIf { it > 0 }?.let { "$it тр." } ?: "")
        .filter { it.isNotBlank() }.joinToString(" · "),
    o.optString("cover"), "playlist&uid=" + Uri.encode(o.optString("uid")) + "&kind=" + o.optString("kind"), null,
)

private fun mmss(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
internal fun YandexPage(vm: FoxViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    var status by remember { mutableStateOf<JSONObject?>(null) }
    var unsupported by remember { mutableStateOf(false) }
    var home by remember { mutableStateOf<JSONObject?>(null) }
    var search by remember { mutableStateOf<JSONObject?>(null) }
    var list by remember { mutableStateOf<JSONObject?>(null) }
    var now by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    val stack = remember { mutableStateListOf<YmView>(YmView.Home) }
    val view = stack.last()

    fun back() { if (stack.size > 1) stack.removeAt(stack.size - 1) else vm.openPage(Page.MAIN) }
    BackHandler(enabled = stack.size > 1) { back() }

    fun loadStatus() = scope.launch {
        val a = vm.foxApi() ?: return@launch
        try {
            status = a.ymGet("status")
        } catch (e: FoxException) {
            if (e.code == 404) unsupported = true else msg = "Нет связи с Фоксом"
        } catch (e: Exception) {
            msg = "Нет связи с Фоксом"
        }
    }

    /** Действие с ответом {"ok","msg"} — строка состояния под заголовком */
    fun act(text: String, params: Map<String, String>, after: () -> Unit = {}) {
        val a = vm.foxApi() ?: return
        if (busy != null) return
        scope.launch {
            busy = text; msg = null
            msg = try {
                val r = a.ymPost(params)
                if (r.optBoolean("ok")) after()
                r.optString("msg").ifBlank { null }
            } catch (e: Exception) {
                "Нет связи с Фоксом"
            }
            busy = null
        }
    }

    fun play(tracks: JSONArray, start: Int) =
        act("Ставлю в очередь…", mapOf("op" to "play", "tracks" to tracks.toString(), "start" to start.toString()))

    fun wave(seed: String, name: String) = act("Запускаю: $name…", mapOf("op" to "wave", "seed" to seed))

    fun ctl(cmd: String) {
        val a = vm.foxApi() ?: return
        scope.launch { try { a.ymPost(mapOf("op" to "ctl", "cmd" to cmd)) } catch (_: Exception) {} }
    }

    val loggedIn = status?.let { it.optBoolean("token") && !it.optBoolean("bad_token") && it.optBoolean("daemon") } == true

    // Яндекс не ответил (сеть, часы после загрузки Фокса) — вход цел, повторить позже
    val offline = status?.optBoolean("offline") == true

    LaunchedEffect(Unit) { loadStatus() }
    LaunchedEffect(status) {
        if (status?.optBoolean("offline") != true) return@LaunchedEffect
        msg = "Нет связи с Яндексом — повторяю…"
        delay(10_000)
        loadStatus()
    }

    // главная — после входа
    LaunchedEffect(loggedIn, offline) {
        if (!loggedIn || offline) return@LaunchedEffect
        if (msg == "Нет связи с Яндексом — повторяю…") msg = null
        val a = vm.foxApi() ?: return@LaunchedEffect
        try { home = a.ymGet("home") } catch (_: Exception) { msg = "Не удалось загрузить главную" }
    }

    // что играет — пока экран открыт
    LaunchedEffect(loggedIn) {
        if (!loggedIn) return@LaunchedEffect
        while (isActive) {
            vm.foxApi()?.let { a -> try { now = a.ymGet("now") } catch (_: Exception) {} }
            delay(2000)
        }
    }

    // поиск и списки
    LaunchedEffect(view) {
        val a = vm.foxApi() ?: return@LaunchedEffect
        when (val v = view) {
            is YmView.Search -> {
                search = null
                try { search = a.ymGet("search&q=" + Uri.encode(v.q)) } catch (_: Exception) { msg = "Поиск не удался" }
            }
            is YmView.Tracks -> {
                list = null
                try {
                    val r = a.ymGet(v.api)
                    if (r.has("ok") && !r.optBoolean("ok", true)) msg = r.optString("msg")
                    list = r
                } catch (_: Exception) { msg = "Не удалось загрузить" }
            }
            YmView.Home -> {}
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(view) { listState.scrollToItem(0) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(Bg).padding(horizontal = 20.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().clickable { back() }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("‹", color = Fg, fontSize = 24.sp, modifier = Modifier.padding(end = 12.dp))
                val title = when (val v = view) {
                    YmView.Home -> "ЯНДЕКС МУЗЫКА"
                    is YmView.Search -> "ПОИСК: ${v.q}"
                    is YmView.Tracks -> v.title
                }
                Text(
                    title, color = Fg, fontSize = 18.sp, fontWeight = FontWeight.Light,
                    letterSpacing = if (view == YmView.Home) 4.sp else 0.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
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

        // ---------- нет поддержки / нет службы / вход ----------
        if (unsupported) {
            item { Text("Прошивка Фокса без Яндекс Музыки — обновите DigiFox.", color = Dim, fontSize = 14.sp) }
            return@LazyColumn
        }
        val st = status
        if (st == null) {
            item { Text("Загрузка…", color = Dim, fontSize = 14.sp) }
            return@LazyColumn
        }
        if (!st.optBoolean("daemon")) {
            item {
                Text("Служба Яндекс Музыки на Фоксе не отвечает. Перезагрузите Фокс; если не поможет — журнал в «Диагностике».",
                    color = Dim, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                HifiButton("Повторить", false, true, Modifier.fillMaxWidth()) { loadStatus() }
            }
            return@LazyColumn
        }
        if (!loggedIn) {
            item {
                if (st.optBoolean("bad_token"))
                    Text("Яндекс не принял сохранённый токен: ${st.optString("msg")}. Войдите заново.", color = Fg, fontSize = 14.sp)
                Text("ВХОД", style = Caption, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(6.dp))
                Text(
                    "1. Нажмите «Войти в Яндекс» — откроется страница Яндекса.\n" +
                        "2. Войдите и разрешите доступ.\n" +
                        "3. Скопируйте адрес страницы, на которую вас перенаправит (в нём есть access_token=…), и вставьте ниже.",
                    color = Dim, fontSize = 13.sp, lineHeight = 19.sp,
                )
                Spacer(Modifier.height(10.dp))
                HifiButton("Войти в Яндекс ↗", false, st.optString("oauth").isNotBlank(), Modifier.fillMaxWidth()) {
                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(st.optString("oauth")))) } catch (_: Exception) {}
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = token, onValueChange = { token = it }, singleLine = true,
                    placeholder = { Text("Адрес страницы или токен", color = Dim) },
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
                )
                Spacer(Modifier.height(8.dp))
                HifiButton("Сохранить токен", false, token.isNotBlank() && busy == null, Modifier.fillMaxWidth()) {
                    act("Проверяю токен…", mapOf("op" to "token", "value" to token.trim())) { token = ""; loadStatus() }
                }
            }
            return@LazyColumn
        }

        when (val v = view) {
            YmView.Home -> {
                item {
                    NowPlaying(now, onCtl = ::ctl,
                        onLike = { id, al -> act("…", mapOf("op" to "like", "id" to id, "a" to al)) },
                        onDislike = { id, al -> act("…", mapOf("op" to "dislike", "id" to id, "a" to al)) })
                    Spacer(Modifier.height(14.dp))
                    HifiButton("Моя волна", false, busy == null, Modifier.fillMaxWidth()) { wave("user:onyourwave", "Моя волна") }
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Трек, альбом, исполнитель", color = Dim) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            if (query.isNotBlank()) { focus.clearFocus(); stack.add(YmView.Search(query.trim())) }
                        }),
                        modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
                    )
                    Spacer(Modifier.height(8.dp))
                    CardRow(YmCard("Мне нравится", "ваши треки", "", "likes", null)) { stack.add(YmView.Tracks("likes", "Мне нравится")) }
                }
                val h = home
                if (h == null) {
                    item { Text("Загрузка…", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp)) }
                } else {
                    cards("ДЛЯ ВАС", h.optJSONArray("personal").objects().map(::playlistCard)) { c -> stack.add(YmView.Tracks(c.api!!, c.title)) }
                    cards("МОИ ПЛЕЙЛИСТЫ", h.optJSONArray("playlists").objects().map(::playlistCard)) { c -> stack.add(YmView.Tracks(c.api!!, c.title)) }
                    cards("СТАНЦИИ", h.optJSONArray("stations").objects().map {
                        YmCard(it.optString("name"), "волна", it.optString("cover"), null, it.optString("seed"))
                    }) { c -> wave(c.seed!!, c.title) }
                }
                item { Settings(st, busy == null, onQuality = { q ->
                    act("Сохраняю…", mapOf("op" to "quality", "q" to q)) { loadStatus() }
                }, onLogout = {
                    act("Выхожу…", mapOf("op" to "logout")) { home = null; loadStatus() }
                }) }
            }

            is YmView.Search -> {
                val r = search
                if (r == null) {
                    item { Text("Ищу…", color = Dim, fontSize = 14.sp) }
                } else {
                    val tr = r.optJSONArray("tracks") ?: JSONArray()
                    if (tr.length() > 0) {
                        item { Text("ТРЕКИ", style = Caption, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) }
                        tracks(tr, ::play)
                    }
                    cards("АЛЬБОМЫ", r.optJSONArray("albums").objects().map(::albumCard)) { c -> stack.add(YmView.Tracks(c.api!!, c.title)) }
                    cards("ИСПОЛНИТЕЛИ", r.optJSONArray("artists").objects().map(::artistCard)) { c -> stack.add(YmView.Tracks(c.api!!, c.title)) }
                    cards("ПЛЕЙЛИСТЫ", r.optJSONArray("playlists").objects().map(::playlistCard)) { c -> stack.add(YmView.Tracks(c.api!!, c.title)) }
                    if (tr.length() == 0 && r.optJSONArray("albums").objects().isEmpty() && r.optJSONArray("artists").objects().isEmpty())
                        item { Text("Ничего не найдено", color = Dim, fontSize = 14.sp) }
                }
            }

            is YmView.Tracks -> {
                val r = list
                if (r == null) {
                    item { Text("Загрузка…", color = Dim, fontSize = 14.sp) }
                } else {
                    val tr = r.optJSONArray("tracks") ?: JSONArray()
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Cover(r.optString("cover"), 72.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.optString("title"), color = Fg, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val sub = r.optString("sub")
                                if (sub.isNotBlank()) Text(sub, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${tr.length()} тр.", color = Dim, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HifiButton("▶ Играть", false, tr.length() > 0 && busy == null, Modifier.weight(1f)) { play(tr, 0) }
                            val seed = r.optString("seed")
                            if (seed.isNotBlank())
                                HifiButton("Волна", false, busy == null, Modifier.weight(1f)) { wave(seed, r.optString("title")) }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    tracks(tr, ::play)
                    cards("АЛЬБОМЫ", r.optJSONArray("albums").objects().map(::albumCard)) { c -> stack.add(YmView.Tracks(c.api!!, c.title)) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Fg, unfocusedTextColor = Fg,
    focusedBorderColor = Fg, unfocusedBorderColor = Line, cursorColor = Fg,
)

/** Список треков: нажатие — играть весь список с этого трека */
private fun LazyListScope.tracks(tr: JSONArray, onPlay: (JSONArray, Int) -> Unit) {
    val n = tr.length()
    items(n) { i ->
        val t = tr.optJSONObject(i) ?: return@items
        val ok = t.optBoolean("ok", true)
        Row(
            Modifier.fillMaxWidth().clickable(enabled = ok) { onPlay(tr, i) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(ymImg(t.optString("cover")), 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.optString("title"), color = if (ok) Fg else Faint, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.optString("artist"), color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val d = t.optLong("dur")
            if (d > 0) Text(mmss(d), color = Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Раздел карточек (альбомы, плейлисты, станции…) */
private fun LazyListScope.cards(caption: String, list: List<YmCard>, onClick: (YmCard) -> Unit) {
    if (list.isEmpty()) return
    item { Text(caption, style = Caption, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp)) }
    items(list.size) { i -> CardRow(list[i]) { onClick(list[i]) } }
}

@Composable
private fun CardRow(c: YmCard, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(c.cover, 44.dp, if (c.api == "likes") "♥" else null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, color = Fg, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.sub.isNotBlank()) Text(c.sub, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (c.seed != null) "▶" else "›", color = Dim, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Что играет MPD (трек Яндекса — с ♥ и ✕) */
@Composable
private fun NowPlaying(
    now: JSONObject?,
    onCtl: (String) -> Unit,
    onLike: (String, String) -> Unit,
    onDislike: (String, String) -> Unit,
) {
    val st = now?.optString("state") ?: "off"
    if (now == null || st == "off" || now.optString("svc") != "mpd" || now.optString("title").isBlank()) return
    Text("СЕЙЧАС", style = Caption)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Cover(now.optString("cover"), 64.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(now.optString("title"), color = Fg, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(now.optString("artist"), color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val dur = (now.optDouble("dur", 0.0) * 1000).toLong()
            if (dur > 0) Text(mmss((now.optDouble("pos", 0.0) * 1000).toLong()) + " / " + mmss(dur) +
                (if (now.optBoolean("wave")) "  · волна" else ""), color = Dim, fontSize = 12.sp)
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HifiButton("⏮", false, true, Modifier.weight(1f)) { onCtl("prev") }
        HifiButton(if (st == "play") "❚❚" else "▶", false, true, Modifier.weight(1f)) { onCtl("toggle") }
        HifiButton("⏭", false, true, Modifier.weight(1f)) { onCtl("next") }
        if (now.optBoolean("ym")) {
            HifiButton("♥", false, true, Modifier.weight(1f)) { onLike(now.optString("id"), now.optString("a")) }
            HifiButton("✕", false, true, Modifier.weight(1f)) { onDislike(now.optString("id"), now.optString("a")) }
        }
    }
}

/** Аккаунт, качество, выход */
@Composable
private fun Settings(st: JSONObject, enabled: Boolean, onQuality: (String) -> Unit, onLogout: () -> Unit) {
    Spacer(Modifier.height(22.dp))
    Text("АККАУНТ", style = Caption)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(st.optString("login").ifBlank { "Яндекс" } + if (st.optBoolean("plus")) "  · ПЛЮС" else "  · без Плюса",
            color = Fg, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        HifiButton("Выйти", false, enabled, Modifier.width(96.dp)) { onLogout() }
    }
    Spacer(Modifier.height(14.dp))
    Text("КАЧЕСТВО", style = Caption)
    Spacer(Modifier.height(6.dp))
    val q = st.optString("quality", "lossless")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("lossless" to "FLAC", "hq" to "MP3 320", "nq" to "MP3 192").forEach { (k, name) ->
            HifiButton(name, q == k, enabled, Modifier.weight(1f)) { if (q != k) onQuality(k) }
        }
    }
    Text("Для следующих треков. FLAC — без потерь, если трек есть в таком качестве.", color = Dim, fontSize = 12.sp,
        modifier = Modifier.padding(top = 6.dp))
}

// ---------------- обложки: небольшой кэш в памяти ----------------

private object YmCovers {
    val cache = LruCache<String, ImageBitmap>(80)
}

@Composable
private fun Cover(url: String, size: Dp, placeholder: String? = null) {
    val bmp by produceState<ImageBitmap?>(YmCovers.cache.get(url), url) {
        if (url.isBlank()) { value = null; return@produceState }
        YmCovers.cache.get(url)?.let { value = it; return@produceState }
        value = withContext(Dispatchers.IO) {
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 8000
                try {
                    c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                        ?.let { android.graphics.Bitmap.createScaledBitmap(it, 160, 160, true) }
                } finally { c.disconnect() }
            } catch (e: Exception) { null }
        }?.asImageBitmap()?.also { YmCovers.cache.put(url, it) }
    }
    Box(Modifier.size(size).background(Line), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, contentDescription = null, modifier = Modifier.size(size))
        else if (placeholder != null) Text(placeholder, color = Fg, fontSize = 18.sp)
    }
}
