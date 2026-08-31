package com.caniko.cenix.fixture

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

class ConfigurableWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.configurable_widget).apply {
                setTextViewText(R.id.configured_widget_text, "Configured widget $id")
            })
        }
    }
}
