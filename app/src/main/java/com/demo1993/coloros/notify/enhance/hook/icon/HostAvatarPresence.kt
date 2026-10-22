package com.demo1993.coloros.notify.enhance.hook.icon

import android.app.Notification
import android.app.Person
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification

/**
 * Whether ColorOS has anything to compose its "personal avatar + app-icon badge" out of.
 *
 * The host builds that composition from the notification itself, so a picked package is only worth
 * yielding to when the notification actually carries an avatar. An ongoing / foreground-service
 * notification from the same package — WeChat's "视频通话中", a media session, a download — carries
 * none, and yielding there does not preserve a photo: it just hands the row over to the host, which
 * falls back to the bare `smallIcon` and the themed icon is lost.
 *
 * Detection reads extras rather than the `Notification` API because the module only ever sees the
 * posted notification. Note that the keys are not a reliable signal on their own: WeChat's call
 * notification ships `android.largeIcon` present but null, so the value is probed, never the key.
 *
 * The heuristic is deliberately limited to the sources ColorOS actually uses — the large icon
 * (WeChat and QQ put the sender's photo there), a `Person`, or a messaging style. Anything else
 * keeps the previous behaviour, i.e. the pick still yields.
 */
internal object HostAvatarPresence {

    fun contains(sbn: StatusBarNotification?): Boolean {
        val extras = sbn?.notification?.extras ?: return false
        return hasLargeIcon(extras) || hasPeople(extras) || isMessaging(extras)
    }

    /**
     * A collapsed-group summary never carries a contact photo: ColorOS synthesises it from its
     * children, so anything composed from it is the raw monochrome `smallIcon`. Rows like that must
     * be replaced even for a picked package, otherwise the badge shows a blank block.
     *
     * Written against the public flag because `Notification.isGroupSummary()` is a hidden API and
     * would not compile against the public SDK.
     */
    fun isGroupSummary(sbn: StatusBarNotification?): Boolean {
        val flags = sbn?.notification?.flags ?: return false
        return (flags and Notification.FLAG_GROUP_SUMMARY) != 0
    }

    /**
     * Whether the host is *allowed* to compose an avatar for this notification, which is the
     * question the yield decision actually asks.
     *
     * [contains] answers a narrower one ("an avatar bit is present in the extras right now") and
     * is too strict as a yield gate: after a reboot WeChat's first message is posted from a cold
     * process and can reach SystemUI before the sender photo is attached, so the extras probe
     * misses and the row falls back to the themed icon — the very regression users see as "first
     * message after a reboot loses the avatar".
     *
     * Message-shaped notifications (category msg / email / social) are therefore trusted to have
     * an avatar even when the probe misses, while ongoing and foreground-service notifications are
     * excluded: WeChat's "视频通话中", media sessions and downloads carry no photo at all, and
     * yielding there is exactly what turned the themed icon into a bare small icon (3.5.3).
     */
    fun mayCompose(sbn: StatusBarNotification?): Boolean {
        if (sbn == null) return true
        if (contains(sbn)) return true
        val notification = sbn.notification ?: return true
        if ((notification.flags and AVATAR_LESS_FLAGS) != 0) return false
        return notification.category?.let { it in AVATAR_CATEGORIES } == true
    }

    private fun hasLargeIcon(extras: Bundle): Boolean =
        parcelable(extras, Notification.EXTRA_LARGE_ICON) != null ||
            parcelable(extras, Notification.EXTRA_LARGE_ICON_BIG) != null

    @Suppress("DEPRECATION") // BundleCompat only widens the API 33 signature for the same call.
    private fun hasPeople(extras: Bundle): Boolean =
        !extras.getParcelableArrayList<Person>(Notification.EXTRA_PEOPLE_LIST).isNullOrEmpty()

    private fun isMessaging(extras: Bundle): Boolean =
        extras.containsKey(Notification.EXTRA_MESSAGES) ||
            extras.containsKey(Notification.EXTRA_HISTORIC_MESSAGES) ||
            extras.containsKey(Notification.EXTRA_CONVERSATION_TITLE) ||
            extras.getCharSequence(Notification.EXTRA_TEMPLATE)?.toString() == MESSAGING_STYLE

    @Suppress("DEPRECATION") // Same reasoning as above: the key-based read is the portable form.
    private fun parcelable(extras: Bundle, key: String): Parcelable? =
        extras.getParcelable(key)

    private const val MESSAGING_STYLE = "android.app.Notification\$MessagingStyle"

    /** Ongoing / foreground-service rows never carry a contact photo to compose. */
    private const val AVATAR_LESS_FLAGS =
        Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE

    /** Notification categories ColorOS renders with a per-sender avatar. */
    private val AVATAR_CATEGORIES = setOf(
        Notification.CATEGORY_MESSAGE,
        Notification.CATEGORY_EMAIL,
        Notification.CATEGORY_SOCIAL,
    )
}
