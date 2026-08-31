package com.caniko.cenix

import android.app.Notification
import android.app.NotificationChannel
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.caniko.cenix.uniffi.ProfileAccess
import java.util.concurrent.CopyOnWriteArraySet

data class NotificationDot(
    val count: Int,
    val shortcutIds: Set<String>,
)

object NotificationDotStore {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var dots = emptyMap<PackageKey, NotificationDot>()
    @Volatile private var enabled = true
    @Volatile private var refresher: (() -> Unit)? = null

    fun dot(key: PackageKey): NotificationDot? = dots[key]

    fun dot(shortcut: LauncherShortcut): NotificationDot? = dot(
        PackageKey(shortcut.id.`package`, shortcut.id.profileId.toLong()),
    )?.takeIf { dot ->
        shortcut.id.shortcutId in dot.shortcutIds
    }

    fun setEnabled(value: Boolean) {
        val changed = enabled != value
        enabled = value
        if (!value) replace(emptyMap())
        else if (changed) refresher?.invoke()
    }

    fun replace(value: Map<PackageKey, NotificationDot>) {
        val next = if (enabled) value else emptyMap()
        if (dots == next) return
        dots = next
        listeners.forEach { it() }
    }

    fun removeProfiles(profileIds: Set<Long>) {
        if (profileIds.isNotEmpty()) replace(dots.filterKeys { it.profileId !in profileIds })
    }

    fun remove(key: PackageKey) = replace(dots - key)

    fun subscribe(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun setRefresher(value: (() -> Unit)?) { refresher = value }
}

class CenixNotificationListener : NotificationListenerService() {
    private val profiles by lazy { ProfileController(this) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationDotStore.setRefresher { mainExecutor.execute(::refresh) }
        refresh()
    }

    override fun onListenerDisconnected() {
        NotificationDotStore.setRefresher(null)
        NotificationDotStore.replace(emptyMap())
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = refresh()

    private fun refresh() {
        val notifications = try {
            activeNotifications.orEmpty().take(MAX_ACTIVE_NOTIFICATIONS)
        } catch (_: SecurityException) {
            emptyList()
        }
        val availableProfiles = try {
            profiles.refresh().profiles
                .filter { it.descriptor.access == ProfileAccess.AVAILABLE }
                .map { it.descriptor.profileId.toLong() }
                .toSet()
        } catch (_: RuntimeException) {
            emptySet()
        }
        val userManager = getSystemService(android.os.UserManager::class.java)
        val aggregate = mutableMapOf<PackageKey, MutableDot>()
        notifications.forEach { sbn ->
            val profileId = userManager.getSerialNumberForUser(sbn.user)
            if (profileId !in availableProfiles) return@forEach
            val ranking = Ranking()
            if (!currentRanking.getRanking(sbn.key, ranking) || !eligible(sbn, ranking)) return@forEach
            val notification = sbn.notification
            val dot = aggregate.getOrPut(PackageKey(sbn.packageName, profileId)) { MutableDot() }
            dot.count = (dot.count + maxOf(1, notification.number)).coerceAtMost(MAX_COUNT)
            notification.shortcutId?.takeIf(String::isNotBlank)?.let(dot.shortcutIds::add)
        }
        NotificationDotStore.replace(aggregate.mapValues { (_, value) -> value.freeze() })
    }

    private fun eligible(sbn: StatusBarNotification, ranking: Ranking): Boolean {
        val notification = sbn.notification
        if (!ranking.canShowBadge() || notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (
            ranking.channel?.id == NotificationChannel.DEFAULT_CHANNEL_ID &&
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        ) return false
        return !notification.extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrEmpty() ||
            !notification.extras.getCharSequence(Notification.EXTRA_TEXT).isNullOrEmpty()
    }

    private class MutableDot {
        var count = 0
        val shortcutIds = linkedSetOf<String>()
        fun freeze() = NotificationDot(count, shortcutIds.take(MAX_SHORTCUT_IDS).toSet())
    }

    companion object {
        private const val MAX_ACTIVE_NOTIFICATIONS = 4096
        private const val MAX_COUNT = 999
        private const val MAX_SHORTCUT_IDS = 64
    }
}

class NotificationDotDrawable(
    base: Drawable,
    private val color: Int,
) : Drawable() {
    private val icon = base.constantState?.newDrawable()?.mutate() ?: base.mutate()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@NotificationDotDrawable.color }

    override fun draw(canvas: Canvas) {
        icon.bounds = bounds
        icon.draw(canvas)
        val radius = minOf(bounds.width(), bounds.height()) * 0.12f
        val x = if (layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) bounds.left + radius else bounds.right - radius
        canvas.drawCircle(x, bounds.top + radius, radius, paint)
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) { icon.bounds = bounds }
    override fun setAlpha(alpha: Int) { icon.alpha = alpha; paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { icon.colorFilter = colorFilter; paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = icon.intrinsicWidth
    override fun getIntrinsicHeight(): Int = icon.intrinsicHeight
}
