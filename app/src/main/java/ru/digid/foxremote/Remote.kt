package ru.digid.foxremote

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/**
 * Управление без открытия приложения: шторка уведомлений и виджет.
 * Нажатия обрабатывает ActionReceiver, он же обновляет уведомление и виджеты.
 */
object Remote {
    const val ACTION_VOL_UP = "ru.digid.foxremote.VOL_UP"
    const val ACTION_VOL_DOWN = "ru.digid.foxremote.VOL_DOWN"
    const val ACTION_MUTE = "ru.digid.foxremote.MUTE"
    const val ACTION_POWER = "ru.digid.foxremote.POWER"

    private const val CHANNEL = "remote"
    private const val NOTIFY_ID = 1

    /** Что показать в уведомлении и виджете */
    data class Snapshot(
        val connected: Boolean,
        val line1: String,
        val line2: String,
        val muted: Boolean = false,
        val power: Boolean? = null,     // null — усилителя нет
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("fox", Context.MODE_PRIVATE)
    fun host(ctx: Context): String = prefs(ctx).getString("host", "") ?: ""
    fun notifyEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("notify", false)
    fun setNotifyEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("notify", on).apply()
        if (!on) NotificationManagerCompat.from(ctx).cancel(NOTIFY_ID)
    }

    // ---------------- состояние ----------------

    fun volumeText(amp: AmpStatus?, st: FoxStatus, usb: Boolean?): String? = when {
        amp != null && amp.muted -> "MUTE"
        amp != null && amp.db -> "${amp.pos - amp.max} dB"
        amp != null -> "${amp.pos}"
        st.muted -> "MUTE"
        usb == true -> null
        st.volume >= 0 -> "${st.volume}%"
        else -> null
    }

    fun snapshotOf(ui: UiState): Snapshot {
        if (!ui.connected) return Snapshot(false, "Fox Remote", "Нет связи с ${ui.host}")
        val amp = ui.amp
        val line1 = when {
            amp != null && !amp.power -> "Усилитель выключен"
            ui.usb == true -> "USB → I2S"
            ui.status.service.isBlank() -> "Нет плеера"
            else -> playerLabel(ui.status.service)
        }
        val line2 = ui.track?.takeIf { amp?.power != false }?.line
            ?: listOfNotNull(ui.rate, volumeText(amp, ui.status, ui.usb)).joinToString("  ·  ")
        return Snapshot(true, line1, line2, amp?.muted ?: ui.status.muted, amp?.power)
    }

    /** Состояние напрямую с Фокса (для виджета и кнопок, когда приложение закрыто) */
    suspend fun fetch(ctx: Context): Snapshot {
        val h = host(ctx)
        if (h.isBlank()) return Snapshot(false, "Fox Remote", "Откройте приложение")
        val api = FoxApi(h)
        return try {
            val st = api.status()
            val usb = api.usbMode()
            val amp = try { api.amp() } catch (e: Exception) { null }
            val rate = try { formatRate(api.rate()) } catch (e: Exception) { null }
            val track = if (usb || rate == STOPPED) null else try { api.track() } catch (e: Exception) { null }
            snapshotOf(UiState(host = h, connected = true, status = st, usb = usb, rate = rate, amp = amp, track = track))
        } catch (e: Exception) {
            Snapshot(false, "Fox Remote", "Нет связи с $h")
        }
    }

    /** Выполнить нажатие кнопки из шторки или виджета */
    suspend fun perform(ctx: Context, action: String) {
        val h = host(ctx)
        if (h.isBlank()) return
        val api = FoxApi(h)
        val amp = try { api.amp() } catch (e: Exception) { null }
        when (action) {
            ACTION_VOL_UP, ACTION_VOL_DOWN -> {
                val up = action == ACTION_VOL_UP
                if (amp != null) {
                    if (amp.power) api.setAmpVolume((amp.pos + if (up) 1 else -1).coerceIn(0, amp.max))
                } else {
                    val st = api.status()
                    if (st.volumeAvailable && !api.usbMode() && st.volume >= 0)
                        api.setVolume((st.volume + if (up) 2 else -2).coerceIn(0, 100))
                }
            }
            ACTION_MUTE -> if (amp != null) api.ampMute() else if (api.status().volumeAvailable) api.toggleMute()
            ACTION_POWER -> if (amp != null) api.ampPower()
        }
        delay(300)   // усилитель подтверждает своим отчётом через ~0,1–0,2 с
    }

    // ---------------- вывод ----------------

    private fun actionIntent(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, ActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openAppIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun publish(ctx: Context, s: Snapshot) {
        updateWidgets(ctx, s)
        if (notifyEnabled(ctx)) showNotification(ctx, s)
    }

    fun widgetViews(ctx: Context, s: Snapshot): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.fox_widget)
        v.setTextViewText(R.id.w_line1, s.line1)
        v.setTextViewText(R.id.w_line2, s.line2)
        v.setTextColor(R.id.w_mute, if (s.muted) 0xFF0B0B0B.toInt() else 0xFFEDEDED.toInt())
        v.setInt(R.id.w_mute, "setBackgroundResource",
            if (s.muted) R.drawable.w_btn_on else R.drawable.w_btn)
        if (s.power == null) {
            v.setViewVisibility(R.id.w_power, android.view.View.GONE)
        } else {
            v.setViewVisibility(R.id.w_power, android.view.View.VISIBLE)
            v.setTextColor(R.id.w_power, if (s.power) 0xFF0B0B0B.toInt() else 0xFFEDEDED.toInt())
            v.setInt(R.id.w_power, "setBackgroundResource",
                if (s.power) R.drawable.w_btn_on else R.drawable.w_btn)
        }
        v.setOnClickPendingIntent(R.id.w_info, openAppIntent(ctx))
        v.setOnClickPendingIntent(R.id.w_down, actionIntent(ctx, ACTION_VOL_DOWN, 1))
        v.setOnClickPendingIntent(R.id.w_mute, actionIntent(ctx, ACTION_MUTE, 2))
        v.setOnClickPendingIntent(R.id.w_up, actionIntent(ctx, ACTION_VOL_UP, 3))
        v.setOnClickPendingIntent(R.id.w_power, actionIntent(ctx, ACTION_POWER, 4))
        return v
    }

    fun updateWidgets(ctx: Context, s: Snapshot) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, FoxWidget::class.java))
        if (ids.isNotEmpty()) mgr.updateAppWidget(ids, widgetViews(ctx, s))
    }

    private fun showNotification(ctx: Context, s: Snapshot) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Пульт Fox", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Громкость и mute из шторки"
                    setShowBadge(false)
                }
            )
        }
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(s.line1)
            .setContentText(s.line2)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent(ctx))
            .addAction(0, "−  ТИШЕ", actionIntent(ctx, ACTION_VOL_DOWN, 1))
            .addAction(0, if (s.muted) "ЗВУК" else "MUTE", actionIntent(ctx, ACTION_MUTE, 2))
            .addAction(0, "ГРОМЧЕ  +", actionIntent(ctx, ACTION_VOL_UP, 3))
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFY_ID, b.build())
        } catch (e: SecurityException) {
            // разрешение отозвали — просто не показываем
        }
    }
}
