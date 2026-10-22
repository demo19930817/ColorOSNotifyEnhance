package com.demo1993.coloros.notifyenhance.hook.icon

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * ColorOS 17 decides which packages get the "personal avatar + app-icon badge" treatment from a
 * system setting. Reading the host's own list keeps the module in sync with ColorOS OTA changes
 * without hardcoding package names, and it is what [NotificationIconPolicy.shouldYieldToHostAvatar]
 * gates on.
 *
 * The setting is a comma separated package list, empty or absent when the feature is unavailable
 * (for example on overseas builds, where SystemUI itself returns false from its own whitelist).
 */
internal object HostAvatarPackages {

    private const val SETTING_KEY = "systemui_icon_badge_packages"
    private const val CACHE_TTL_MS = 30_000L

    private val lock = Any()

    @Volatile
    private var cached: Set<String> = emptySet()

    @Volatile
    private var cachedAtMs = 0L

    fun contains(context: Context, packageName: String?): Boolean =
        !packageName.isNullOrEmpty() && packages(context).contains(packageName)

    fun packages(context: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        val snapshot = cached
        if (cachedAtMs != 0L && now - cachedAtMs < CACHE_TTL_MS) return snapshot
        synchronized(lock) {
            val refreshedAt = SystemClock.elapsedRealtime()
            if (cachedAtMs != 0L && refreshedAt - cachedAtMs < CACHE_TTL_MS) return cached
            val read = readFromSettings(context) ?: snapshot
            cached = read
            cachedAtMs = refreshedAt
            return read
        }
    }

    /** Keeps the previous snapshot when the provider read fails, so a transient error cannot
     *  silently turn the passthrough off in the middle of a burst of notifications. */
    private fun readFromSettings(context: Context): Set<String>? = try {
        Settings.Global.getString(context.contentResolver, SETTING_KEY)
            ?.split(',')
            ?.asSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
    } catch (_: Exception) {
        null
    }
}
