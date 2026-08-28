package com.caniko.cenix

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetHost
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.caniko.cenix.db.RestorePhase

class WidgetRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED ||
            intent.getIntExtra(AppWidgetManager.EXTRA_HOST_ID, -1) != WidgetHostController.HOST_ID
        ) return
        val oldIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS) ?: return
        val newIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: return
        if (oldIds.size != newIds.size) return
        val pending = goAsync()
        CenixExecutors.io {
            try {
                val app = context.applicationContext as CenixApplication
                if (!app.awaitReady() || app.emergency) return@io
                val dao = app.database?.dao() ?: return@io
                val restore = dao.pendingRestore()
                if (restore?.phase == RestorePhase.PLATFORM_RECONCILE) {
                    val mapped = dao.remapWidgetIds(oldIds, newIds, System.currentTimeMillis()).toSet()
                    val host = AppWidgetHost(context, WidgetHostController.HOST_ID)
                    newIds.filter { it !in mapped }.forEach(host::deleteAppWidgetId)
                    restore.committedGeneration?.let(dao::completeRestore)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
