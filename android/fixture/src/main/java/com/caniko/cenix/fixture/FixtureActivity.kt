package com.caniko.cenix.fixture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import android.widget.Button
import android.widget.LinearLayout
import android.content.pm.ShortcutManager
import android.appwidget.AppWidgetManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import androidx.appcompat.app.AppCompatActivity

class FixtureActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(ShortcutManager::class.java)
        intent.getStringExtra(EXTRA_NOTIFICATION_COMMAND)?.let { command ->
            controlNotification(command)
            finish()
            return
        }
        intent.getStringExtra(EXTRA_WALLPAPER_COMMAND)?.let { command ->
            setFixtureWallpaper(command)
            finish()
            return
        }
        val fixture = application as FixtureApplication
        val admin = ComponentName(this, FixtureAdminReceiver::class.java)
        getSystemService(DevicePolicyManager::class.java).let { policy ->
            if (policy.isProfileOwnerApp(packageName)) policy.addCrossProfileWidgetProvider(admin, packageName)
        }
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

    private fun setFixtureWallpaper(command: String) {
        val color = when (command) {
            "light" -> Color.WHITE
            "dark" -> Color.BLACK
            else -> return
        }
        val bitmap = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        getSystemService(WallpaperManager::class.java).setBitmap(bitmap)
        bitmap.recycle()
    }

    private fun controlNotification(command: String) {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Fixture", NotificationManager.IMPORTANCE_DEFAULT))
        when (command) {
            "cancel" -> notifications.cancelAll()
            "post", "shortcut", "ongoing", "summary" -> notifications.notify(
                if (command == "shortcut") 2 else 1,
                Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Fixture notification")
                    .setContentText("Conformance only")
                    .setOngoing(command == "ongoing")
                    .setGroup("fixture-group")
                    .setGroupSummary(command == "summary")
                    .setShortcutId(if (command == "shortcut") FixtureApplication.DYNAMIC_ID else null)
                    .build(),
            )
        }
    }

    companion object {
        private const val CHANNEL = "fixture"
        private const val EXTRA_NOTIFICATION_COMMAND = "notification-command"
        private const val EXTRA_WALLPAPER_COMMAND = "wallpaper-command"
    }
}
