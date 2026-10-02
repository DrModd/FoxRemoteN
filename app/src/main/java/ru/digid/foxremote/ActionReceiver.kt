package ru.digid.foxremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Кнопки шторки уведомлений и виджета */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val ctx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                try {
                    Remote.perform(ctx, action)
                } catch (e: Exception) {
                    // нет связи — ниже покажем это в уведомлении/виджете
                }
                Remote.publish(ctx, Remote.fetch(ctx))
            } finally {
                pending.finish()
            }
        }
    }
}
