package com.caniko.cenix

import android.app.Activity
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import java.util.concurrent.atomic.AtomicInteger

class WallpaperAppearanceController(
    private val activity: AppCompatActivity,
    private val onChanged: () -> Unit,
) : WallpaperManager.OnColorsChangedListener {
    private val manager = activity.getSystemService(WallpaperManager::class.java)
    private var started = false
    private var nightMode = mode(manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM))

    val generation: Int get() = appearanceGeneration.get()

    fun start() {
        if (started) return
        started = true
        manager.addOnColorsChangedListener(this, Handler(Looper.getMainLooper()))
        updateSystemBars(manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM))
    }

    fun stop() {
        if (!started) return
        manager.removeOnColorsChangedListener(this)
        started = false
    }

    override fun onColorsChanged(colors: WallpaperColors?, which: Int) {
        if (which and WallpaperManager.FLAG_SYSTEM == 0) return
        appearanceGeneration.incrementAndGet()
        val nextMode = mode(colors)
        if (nightMode != nextMode) {
            nightMode = nextMode
            activity.delegate.localNightMode = nextMode
        } else {
            updateSystemBars(colors)
            onChanged()
        }
    }

    private fun updateSystemBars(colors: WallpaperColors?) {
        val light = colors?.colorHints?.and(WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
        activity.window.insetsController?.setSystemBarsAppearance(
            if (light) LIGHT_BARS else 0,
            LIGHT_BARS,
        )
    }

    companion object {
        private val appearanceGeneration = AtomicInteger()
        private const val LIGHT_BARS = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS

        fun applyTheme(activity: AppCompatActivity) {
            activity.delegate.localNightMode = mode(
                activity.getSystemService(WallpaperManager::class.java)
                    .getWallpaperColors(WallpaperManager.FLAG_SYSTEM),
            )
        }

        private fun mode(colors: WallpaperColors?): Int = if (
            colors?.colorHints?.and(WallpaperColors.HINT_SUPPORTS_DARK_THEME) != 0
        ) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
    }
}

object SystemWallpaperPicker {
    fun open(activity: Activity): Boolean {
        val manager = activity.getSystemService(WallpaperManager::class.java)
        if (!manager.isWallpaperSupported || !manager.isSetWallpaperAllowed) return false
        return try {
            activity.startActivity(Intent(Intent.ACTION_SET_WALLPAPER).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

class ThemedIconRenderer(private val context: Context) {
    private val resources = context.resources
    private val packageManager = context.packageManager
    private val cache = object : LruCache<IconKey, Drawable.ConstantState>(MAX_ICONS) {}

    fun icon(app: LaunchableApp, enabled: Boolean, generation: Int): Drawable? {
        if (!enabled) return app.icon
        val source = app.baseIcon as? AdaptiveIconDrawable ?: return app.icon
        val monochrome = source.monochrome ?: return app.icon
        val key = IconKey(app.packageName, app.className, app.profileId, resources.displayMetrics.densityDpi, generation)
        cache.get(key)?.let { return it.newDrawable(resources) }
        val foreground = monochrome.constantState?.newDrawable(resources)?.mutate() ?: monochrome.mutate()
        foreground.setTint(resources.getColor(R.color.themed_icon_foreground, context.theme))
        val themed = AdaptiveIconDrawable(
            ColorDrawable(resources.getColor(R.color.themed_icon_background, context.theme)),
            foreground,
        )
        val badged = app.user?.let { packageManager.getUserBadgedIcon(themed, it) } ?: themed
        badged.constantState?.let { cache.put(key, it) }
        return badged
    }

    fun invalidate(key: PackageKey?) {
        if (key == null) return cache.evictAll()
        cache.snapshot().keys.filter { it.packageName == key.packageName && it.profileId == key.profileId }
            .forEach(cache::remove)
    }

    fun invalidateProfiles(profileIds: Set<Long>) {
        cache.snapshot().keys.filter { it.profileId in profileIds }.forEach(cache::remove)
    }

    private data class IconKey(
        val packageName: String,
        val className: String,
        val profileId: Long,
        val density: Int,
        val generation: Int,
    )

    companion object { private const val MAX_ICONS = 256 }
}
