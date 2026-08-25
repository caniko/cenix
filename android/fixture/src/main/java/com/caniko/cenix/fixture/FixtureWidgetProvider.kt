package com.caniko.cenix.fixture

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

@Suppress("DEPRECATION")
class FixtureWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, manager, ids)

    companion object {
        fun update(context: Context, manager: AppWidgetManager = AppWidgetManager.getInstance(context), ids: IntArray? = null) {
            val widgetIds = ids ?: manager.getAppWidgetIds(ComponentName(context, FixtureWidgetProvider::class.java))
            val count = context.getSharedPreferences("widget", Context.MODE_PRIVATE).getInt("count", 0)
            widgetIds.forEach { appWidgetId ->
                val service = Intent(context, FixtureWidgetService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    .setData(android.net.Uri.parse("cenix-fixture://widget/$appWidgetId/$count"))
                val open = PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    Intent(context, FixtureActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                manager.updateAppWidget(appWidgetId, RemoteViews(context.packageName, R.layout.fixture_widget).apply {
                    setTextViewText(R.id.widget_text, "Widget update $count")
                    setRemoteAdapter(R.id.widget_list, service)
                    setOnClickPendingIntent(R.id.widget_open, open)
                })
            }
            manager.notifyAppWidgetViewDataChanged(widgetIds, R.id.widget_list)
        }
    }
}
