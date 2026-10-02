package ru.digid.foxremote

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Виджет: источник, частота, громкость и кнопки − MUTE + PWR */
class FoxWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val ctx = context.applicationContext
        // сразу — кнопки (текст обновится, когда придёт ответ Фокса)
        manager.updateAppWidget(ids, Remote.widgetViews(ctx, Remote.Snapshot(false, "Fox Remote", "…")))
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Remote.updateWidgets(ctx, Remote.fetch(ctx))
            } finally {
                pending.finish()
            }
        }
    }
}
