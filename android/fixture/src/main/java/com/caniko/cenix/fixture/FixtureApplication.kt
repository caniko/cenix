package com.caniko.cenix.fixture

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

class FixtureApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(ShortcutManager::class.java).dynamicShortcuts = listOf(dynamicShortcut())
    }

    fun dynamicShortcut(label: Int = R.string.dynamic_shortcut, rank: Int = 1) = ShortcutInfo.Builder(this, DYNAMIC_ID)
        .setShortLabel(getString(label))
        .setLongLabel(getString(label))
        .setRank(rank)
        .setIcon(Icon.createWithResource(this, android.R.drawable.ic_menu_send))
        .setIntent(Intent(Intent.ACTION_VIEW).setComponent(ComponentName(this, DynamicShortcutActivity::class.java)))
        .build()

    companion object {
        const val DYNAMIC_ID = "dynamic"
    }
}
