package com.caniko.cenix

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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
                if (app.awaitReady()) app.database?.dao()?.remapWidgetIds(oldIds, newIds, System.currentTimeMillis())
            } finally {
                pending.finish()
            }
        }
    }
}
