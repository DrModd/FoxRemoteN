package ru.digid.foxremote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обновление самого приложения из релиза «latest» на GitHub:
 * version.txt ("<versionCode> <versionName>") рядом с FoxRemote.apk,
 * скачать APK в кэш и отдать системному установщику.
 */
object AppUpdate {
    private const val BASE = "https://github.com/DrModd/FoxRemoteN/releases/download/latest/"

    /** Версия новее установленной, если нашлась при проверке */
    var available by mutableStateOf<String?>(null)
        private set

    private var checked = false

    fun current(ctx: Context): Pair<Long, String> {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(pi) to (pi.versionName ?: "")
    }

    private fun open(url: String, timeout: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = timeout
        c.useCaches = false
        c.instanceFollowRedirects = true
        return c
    }

    /** Версия в релизе: (код, имя); null — не удалось узнать */
    suspend fun remote(): Pair<Long, String>? = withContext(Dispatchers.IO) {
        try {
            val c = open(BASE + "version.txt?t=" + System.currentTimeMillis(), 10_000)
            try {
                if (c.responseCode !in 200..299) return@withContext null
                val p = c.inputStream.bufferedReader().use { it.readText() }.trim().split(Regex("\\s+"))
                val code = p.getOrNull(0)?.toLongOrNull() ?: return@withContext null
                code to (p.getOrNull(1) ?: code.toString())
            } finally { c.disconnect() }
        } catch (e: Exception) { null }
    }

    /** Тихая проверка при запуске (раз за сеанс): пункт меню покажет новую версию */
    suspend fun checkQuietly(ctx: Context) {
        if (checked) return
        checked = true
        val r = remote() ?: return
        if (r.first > current(ctx).first) available = r.second
    }

    /** Скачать APK в кэш; progress 0..1 (или -1, если размер неизвестен) */
    suspend fun download(ctx: Context, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        val f = File(dir, "FoxRemote.apk")
        val tmp = File(dir, "FoxRemote.part")
        val c = open(BASE + "FoxRemote.apk", 30_000)
        try {
            if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var done = 0L
            c.inputStream.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
            if (done < 100_000) throw Exception("файл слишком мал")
            f.delete()
            if (!tmp.renameTo(f)) throw Exception("не удалось сохранить файл")
            f
        } finally { c.disconnect() }
    }

    /** Разрешено ли приложению ставить APK (Android 8+ спрашивает один раз) */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun askPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private val UpdBg = Color(0xFF151515)

/** Окно «Обновление приложения»: проверить, скачать, установить */
@Composable
internal fun AppUpdateDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cur = remember { AppUpdate.current(ctx) }
    var remote by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var checking by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf<Float?>(null) }   // идёт скачивание
    var file by remember { mutableStateOf<File?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        remote = AppUpdate.remote()
        checking = false
    }

    fun installNow(f: File) {
        if (!AppUpdate.canInstall(ctx)) {
            err = "Разрешите установку из этого приложения и нажмите «Установить» ещё раз."
            try { AppUpdate.askPermission(ctx) } catch (_: Exception) {}
            return
        }
        try { AppUpdate.install(ctx, f) } catch (e: Exception) { err = "Установщик не открылся: ${e.message}" }
    }

    val r = remote
    val newer = r != null && r.first > cur.first
    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        containerColor = UpdBg,
        titleContentColor = Fg,
        textContentColor = Fg,
        title = { Text("Обновление приложения", fontSize = 18.sp) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("Установлена версия ${cur.second}", color = Dim, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                when {
                    checking -> Text("Проверяю GitHub…", fontSize = 15.sp)
                    r == null -> Text("Не удалось узнать последнюю версию — нет интернета?", fontSize = 15.sp)
                    newer -> Text("Доступна версия ${r.second}", fontSize = 15.sp)
                    else -> Text("Это последняя версия", fontSize = 15.sp)
                }
                val p = progress
                if (p != null) {
                    Spacer(Modifier.height(12.dp))
                    if (p >= 0f) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = Fg, trackColor = Line)
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Fg, trackColor = Line)
                    Text("Скачиваю…", color = Dim, fontSize = 13.sp)
                }
                err?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Dim, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            val f = file
            when {
                f != null -> TextButton(onClick = { installNow(f) }) { Text("УСТАНОВИТЬ", color = Fg) }
                newer && progress == null -> TextButton(onClick = {
                    err = null
                    progress = 0f
                    scope.launch {
                        try {
                            val d = AppUpdate.download(ctx) { v -> progress = v }
                            file = d
                            progress = null
                            installNow(d)
                        } catch (e: Exception) {
                            progress = null
                            err = "Не удалось скачать: ${e.message}"
                        }
                    }
                }) { Text("СКАЧАТЬ И УСТАНОВИТЬ", color = Fg) }
                else -> {}
            }
        },
        dismissButton = {
            if (progress == null) TextButton(onClick = onDismiss) { Text("ЗАКРЫТЬ", color = Dim) }
        },
    )
}
