package com.caniko.cenix.fixture

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class FixtureWidgetConfigActivity : AppCompatActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Button(this@FixtureWidgetConfigActivity).apply {
                id = R.id.complete_widget_config
                text = getString(R.string.complete_widget_config)
                setOnClickListener {
                    ConfigurableWidgetProvider().onUpdate(
                        this@FixtureWidgetConfigActivity,
                        getSystemService(AppWidgetManager::class.java),
                        intArrayOf(appWidgetId),
                    )
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                    finish()
                }
            })
        })
    }
}
