package com.caniko.cenix

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import android.util.LruCache
import android.util.TypedValue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class IconPackInfo(val packageName: String, val label: String)

data class PackIconRef(val packPackage: String, val drawableName: String)

// Static icons only. Masks, calendars and clocks are deliberately not emulated.
class IconPackManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("cenix_icons", Context.MODE_PRIVATE)
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private var mappingCache: Pair<String, Map<String, String>>? = null

    companion object {
        // A hung pack drawable must stall at most one background render, never
        // HOME, search, restore, or invalidation. Full process isolation needs a
        // secondary :icons process plus an Application-start guard (isolated
        // processes cannot reach pack Resources); tracked as follow-up.
        const val RENDER_TIMEOUT_SECONDS = 8L
    }

    // Dedicated bounded pool: rendering never occupies the shared IO executor
    // and never runs under this manager's monitor (see drawable below).
    private val renderExecutor = ThreadPoolExecutor(
        0, 2, 30L, TimeUnit.SECONDS,
        LinkedBlockingQueue(64),
        { task -> Thread(task, "cenix-icons").apply { isDaemon = true } },
    )

    @Synchronized fun invalidate() {
        mappingCache = null
        cache.evictAll()
    }

    fun selectedPack(): String? = prefs.getString("pack", null)?.takeIf { it.isNotBlank() }

    fun setPack(packageName: String?) {
        prefs.edit().apply { if (packageName.isNullOrBlank()) remove("pack") else putString("pack", packageName) }.apply()
        mappingCache = null
        cache.evictAll()
    }

    fun overrideFor(app: LaunchableApp): PackIconRef? {
        if (!app.supportsCustomization) return null
        val raw = prefs.getString("override|${app.packageName}|${app.className}|${app.profileId}", null) ?: return null
        val pack = raw.substringBefore("|", "")
        val name = raw.substringAfter("|", "")
        if (pack.isBlank() || name.isBlank()) return null
        return PackIconRef(pack, name)
    }

    fun setOverride(app: LaunchableApp, ref: PackIconRef?) {
        if (!app.supportsCustomization) return
        val key = "override|${app.packageName}|${app.className}|${app.profileId}"
        prefs.edit().apply { if (ref == null) remove(key) else putString(key, "${ref.packPackage}|${ref.drawableName}") }.apply()
        cache.evictAll()
    }

    data class RawOverride(
        val `package`: String,
        val `class`: String,
        val profileId: ULong,
        val packPackage: String,
        val drawable: String,
    )

    fun rawOverrides(): List<RawOverride> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith("override|") || value !is String) return@mapNotNull null
        val parts = key.removePrefix("override|").split("|")
        if (parts.size != 3) return@mapNotNull null
        val profile = parts[2].toULongOrNull() ?: return@mapNotNull null
        val pack = value.substringBefore("|")
        val name = value.substringAfter("|")
        if (pack.isBlank() || name.isBlank()) return@mapNotNull null
        RawOverride(parts[0], parts[1], profile, pack, name)
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        mappingCache = null
        cache.evictAll()
    }

    internal fun replace(pack: String, overrides: List<com.caniko.cenix.uniffi.IconOverride>) {
        val edit = prefs.edit()
        edit.clear()
        if (pack.isNotBlank()) edit.putString("pack", pack)
        overrides.forEach {
            edit.putString("override|${it.`package`}|${it.`class`}|${it.profileId}", "${it.packPackage}|${it.drawable}")
        }
        check(edit.commit()) { "icon restore write failed" }
        invalidate()
    }

    // See CategoryStore.knownSerials: defers cold-start cleanup of rows for
    // profiles not yet observed this boot.
    fun knownSerials(): Set<Long> = prefs.all.keys.mapNotNull { k ->
        if (k.startsWith("override|")) k.substringAfterLast("|").toLongOrNull() else null
    }.toSet()

    fun retainProfiles(allowed: Set<Long>, preserve: Set<Long> = emptySet()) {
        val keep = allowed + preserve
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("override|") }.forEach { k ->
            if (k.substringAfterLast("|").toLongOrNull() !in keep) edit.remove(k)
        }
        edit.apply()
        cache.evictAll()
    }

    fun discover(): List<IconPackInfo> {
        val pm = context.packageManager
        val intents = listOf(
            "com.fede.launcher.THEME_ICONPACK",
            "org.adw.launcher.THEMES",
            "com.anddoes.launcher.THEME",
        ).map { Intent(it) }
        val found = linkedMapOf<String, String>()
        for (intent in intents) {
            try {
                for (ri in pm.queryIntentActivities(intent, 0)) {
                    val pkg = ri.activityInfo.packageName
                    found.getOrPut(pkg) {
                        try {
                            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
                        } catch (_: Exception) { pkg }
                    }
                }
            } catch (_: Exception) { }
        }
        return found.map { IconPackInfo(it.key, it.value) }.sortedBy { it.label.lowercase() }
    }

    @Synchronized fun mapping(packPackage: String): Map<String, String> {
        mappingCache?.let { if (it.first == packPackage) return it.second }
        val map = index(packPackage, "appfilter").mappings
        mappingCache = packPackage to map
        return map
    }

    fun catalog(packPackage: String): List<String> {
        // No display truncation: all entries of a supported bounded catalog are searchable.
        return (index(packPackage, "appfilter").drawables + index(packPackage, "drawable").drawables).sorted()
    }

    fun drawable(packPackage: String, drawableName: String): Drawable? {
        // Deliberately unsynchronized: the cache is thread-safe and rendering
        // runs on the bounded pool below, so a slow drawable cannot block
        // invalidate() or anything else holding this manager's monitor.
        if (IconPackXml.resourceName(drawableName) == null) return null
        // Rasterization never runs on the UI thread; callers fall back to the
        // themed/default icon instead.
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val size = (64 * context.resources.displayMetrics.density).toInt().coerceIn(48, 256)
        val key = "$packPackage/$drawableName@$size:${context.resources.configuration.uiMode}"
        cache.get(key)?.let { return BitmapDrawable(context.resources, it) }
        val future = try {
            renderExecutor.submit<Drawable?> { renderDrawable(packPackage, drawableName, size, key) }
        } catch (_: RejectedExecutionException) {
            return null
        }
        return try {
            future.get(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            // Bounded stall only: drop the hung render and fall back.
            future.cancel(true)
            cache.evictAll()
            null
        } catch (e: java.util.concurrent.ExecutionException) {
            if (e.cause is OutOfMemoryError) cache.evictAll()
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: OutOfMemoryError) {
            cache.evictAll()
            null
        }
    }

    private fun renderDrawable(packPackage: String, drawableName: String, size: Int, key: String): Drawable? {
        return try {
            val resources = context.packageManager.getResourcesForApplication(packPackage)
            val id = resources.getIdentifier(drawableName, "drawable", packPackage)
            if (id == 0) return null
            val value = TypedValue().also { resources.getValue(id, it, true) }
            val source = if (value.string?.toString()?.endsWith(".xml") == false) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
                BitmapFactory.decodeResource(resources, id, options)
                if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth.toLong() * options.outHeight > 16_777_216) return null
                options.inJustDecodeBounds = false
                options.inSampleSize = (maxOf(options.outWidth, options.outHeight) / size).coerceAtLeast(1)
                BitmapFactory.decodeResource(resources, id, options)?.let { BitmapDrawable(resources, it) } ?: return null
            } else resources.getDrawable(id, null).mutate()
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            source.setBounds(0, 0, size, size)
            source.draw(Canvas(bitmap))
            cache.put(key, bitmap)
            BitmapDrawable(context.resources, bitmap)
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) {
            // A hostile nested drawable can inflate past the raster guard; drop the
            // cached preview and fall back to the ordinary app icon instead of dying.
            cache.evictAll()
            null
        }
    }

    fun resolve(app: LaunchableApp): Drawable? {
        overrideFor(app)?.let { ref ->
            if (packAvailable(ref.packPackage)) drawable(ref.packPackage, ref.drawableName)?.let { return badged(it, app) }
        }
        selectedPack()?.let { pack ->
            val name = mapping(pack)["${app.packageName}/${app.className}"]
            if (name != null) drawable(pack, name)?.let { return badged(it, app) }
        }
        return null
    }

    fun packAvailable(packPackage: String): Boolean = try {
        context.packageManager.getApplicationInfo(packPackage, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) { false }

    private fun badged(source: Drawable, app: LaunchableApp): Drawable {
        return try { app.user?.let { context.packageManager.getUserBadgedIcon(source, it) } ?: source }
        catch (_: Exception) { source }
    }

    private fun index(packPackage: String, name: String): IconPackIndex = try {
        val resources = context.packageManager.getResourcesForApplication(packPackage)
        val xml = resources.getIdentifier(name, "xml", packPackage)
        if (xml != 0) resources.getXml(xml).use { IconPackXml.parse(it) }
        else resources.assets.open("$name.xml").use { stream ->
            val bytes = stream.readNBytes(IconPackXml.MAX_BYTES + 1)
            IconPackXml.parseBytes(bytes)
        }
    } catch (_: Exception) {
        IconPackIndex(emptyMap(), emptySet())
    }
}
