package com.caniko.cenix.fixture

import android.os.Bundle
import android.widget.TextView
import android.widget.Button
import android.widget.LinearLayout
import android.content.pm.ShortcutManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.appcompat.app.AppCompatActivity

class FixtureActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(ShortcutManager::class.java)
        val fixture = application as FixtureApplication
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@FixtureActivity).apply {
                text = getString(R.string.app_name)
                contentDescription = getString(R.string.app_name)
            })
            addView(action(R.id.pin_dynamic, R.string.pin_dynamic) {
                manager.requestPinShortcut(fixture.dynamicShortcut(), null)
            })
            addView(action(R.id.update_dynamic, R.string.update_dynamic) {
                manager.updateShortcuts(listOf(fixture.dynamicShortcut(R.string.dynamic_shortcut_updated, 0)))
            })
            addView(action(R.id.disable_dynamic, R.string.disable_dynamic) {
                manager.disableShortcuts(listOf(FixtureApplication.DYNAMIC_ID), "Disabled for conformance")
            })
            addView(action(R.id.remove_dynamic, R.string.remove_dynamic) {
                manager.removeDynamicShortcuts(listOf(FixtureApplication.DYNAMIC_ID))
            })
            addView(action(R.id.update_widget, R.string.update_widget) {
                getSharedPreferences("widget", MODE_PRIVATE).edit()
                    .putInt("count", getSharedPreferences("widget", MODE_PRIVATE).getInt("count", 0) + 1)
                    .apply()
                FixtureWidgetProvider.update(this@FixtureActivity)
            })
            addView(action(R.id.pin_widget, R.string.pin_widget) {
                getSystemService(AppWidgetManager::class.java).requestPinAppWidget(
                    ComponentName(this@FixtureActivity, FixtureWidgetProvider::class.java),
                    null,
                    null,
                )
            })
        })
    }

    private fun action(id: Int, label: Int, block: () -> Unit) = Button(this).apply {
        this.id = id
        text = getString(label)
        setOnClickListener { block() }
    }
}
