package com.caniko.cenix.fixture

import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class FixtureWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent) = object : RemoteViewsFactory {
        private var count = 0
        override fun onCreate() = Unit
        override fun onDataSetChanged() {
            count = getSharedPreferences("widget", MODE_PRIVATE).getInt("count", 0)
        }
        override fun onDestroy() = Unit
        override fun getCount() = 2
        override fun getViewAt(position: Int) = RemoteViews(packageName, R.layout.fixture_widget_row).apply {
            setTextViewText(R.id.widget_row, "Row ${position + 1}, update $count")
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = true
    }
}
