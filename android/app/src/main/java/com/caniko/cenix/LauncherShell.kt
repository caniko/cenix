package com.caniko.cenix

import kotlin.math.abs

enum class LauncherSurface { HOME, ALL_APPS, EMERGENCY }

object LauncherShell {
    fun swipeTarget(surface: LauncherSurface, deltaY: Float, velocityY: Float, height: Int): LauncherSurface {
        val committed = abs(deltaY) >= height / 8f || abs(velocityY) >= 800f
        if (!committed) return surface
        return when {
            surface == LauncherSurface.HOME && deltaY < 0 -> LauncherSurface.ALL_APPS
            surface == LauncherSurface.ALL_APPS && deltaY > 0 -> LauncherSurface.HOME
            else -> surface
        }
    }

    fun backTarget(surface: LauncherSurface, imeVisible: Boolean): LauncherSurface = when {
        surface == LauncherSurface.ALL_APPS && !imeVisible -> LauncherSurface.HOME
        else -> surface
    }

    fun homeIntent(emergency: Boolean) = if (emergency) LauncherSurface.EMERGENCY else LauncherSurface.HOME

    fun pageDelta(swipeLeft: Boolean, rtl: Boolean): Int = if (swipeLeft.xor(rtl)) 1 else -1
}
