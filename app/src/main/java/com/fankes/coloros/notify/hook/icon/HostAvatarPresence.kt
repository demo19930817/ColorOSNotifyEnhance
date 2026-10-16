package com.fankes.coloros.notify.hook.icon

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
}
