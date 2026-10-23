package com.demo1993.coloros.notify.enhance.hook.icon

import android.app.Notification
import android.app.Person
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import android.util.LruCache
import kotlin.math.abs

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

    /**
     * The answer to "will the host paint a contact photo here?", with the evidence it was reached
     * on, so a bug report can be diagnosed from the log alone.
     */
    internal data class Verdict(
        val renderable: Boolean,
        val reason: String,
        val difference: Double = -1.0,
    )

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

    /**
     * The question the yield decision really asks, answered against the artwork instead of the
     * metadata: will ColorOS paint a contact photo for this row?
     *
     * [mayCompose] is too generous on a cold start. WeChat's first message after a reboot is
     * posted while the app is still booting, and it can ship an icon of its own in
     * `android.largeIcon` as a placeholder until the sender photo is loaded. `contains` sees a
     * large icon, the category says "message", so the row yields to the host — and the host then
     * composes "app icon + badge", which is neither the avatar nor the themed icon. The visible
     * symptom is exactly "the first WeChat message after a reboot is not themed, later ones are".
     *
     * So the large icon is decoded and compared against the package's launcher icon. A large icon
     * that *is* the launcher icon cannot be a contact photo, and the row is replaced like any
     * other. `Person` / messaging-style notifications keep yielding without the pixel check: on
     * those the host reads the avatar from its own contact store, not from `largeIcon`.
     */
    fun canRenderAvatar(
        context: Context?,
        sbn: StatusBarNotification?,
        allowGroupSummaryAvatar: Boolean = false,
    ): Boolean = evaluate(context, sbn, allowGroupSummaryAvatar).renderable

    /**
     * @param allowGroupSummaryAvatar also ask the artwork question for a row the app flagged as a
     * group summary. Aggregated rows (WeChat's "[2条] …") are summaries by definition, yet the
     * headline account behind them can still ship its own avatar in the large icon. The panel badge
     * hook and the lock-screen capsule pass `true` here: on those rows the host never composes a
     * photo itself, so the module has to compose it rather than yield. The metadata shortcuts are
     * skipped for summaries — only the artwork can tell a photo from the app icon there.
     */
    fun evaluate(
        context: Context?,
        sbn: StatusBarNotification?,
        allowGroupSummaryAvatar: Boolean = false,
    ): Verdict {
        if (sbn == null) return Verdict(renderable = true, reason = "no-notification")
        val summary = isGroupSummary(sbn)
        if (summary && !allowGroupSummaryAvatar) return Verdict(renderable = false, reason = "group-summary")
        val notification = sbn.notification ?: return Verdict(renderable = true, reason = "no-notification")
        val extras = notification.extras
        if (!summary) {
            if (hasPeople(extras)) return Verdict(renderable = true, reason = "person")
            if (isMessaging(extras)) return Verdict(renderable = true, reason = "messaging-style")
        }
        if (context == null) {
            return if (summary) {
                Verdict(renderable = false, reason = "group-summary")
            } else {
                Verdict(mayCompose(sbn), "no-context")
            }
        }
        // No large icon at all: keep the old, metadata-driven answer. Some picked packages get
        // their photo from ColorOS' own contact store instead of the notification, and replacing
        // those rows is the 3.5.9 "avatar disappeared" regression.
        val largeIcon = largeIconOf(notification, extras)
            ?: return if (summary) {
                Verdict(renderable = false, reason = "group-summary")
            } else {
                Verdict(mayCompose(sbn), "no-large-icon")
            }
        val cacheKey = sbn.key + "#" + System.identityHashCode(largeIcon)
        avatarDecisions.get(cacheKey)?.let { return it.forRowType(summary) }
        val verdict = runCatching { judge(context, sbn.packageName, largeIcon) }
            .getOrElse {
                if (summary) {
                    Verdict(renderable = false, reason = "group-summary")
                } else {
                    Verdict(mayCompose(sbn), "probe-failed:${it.javaClass.simpleName}")
                }
            }
        avatarDecisions.put(cacheKey, verdict)
        return verdict.forRowType(summary)
    }

    /**
     * A summary row whose artwork is *not* a photo keeps reporting `group-summary`: the caller
     * treats that reason as "replace with the themed icon", and a launcher-icon placeholder on an
     * aggregated row is exactly that. Only a real photo is allowed through.
     */
    private fun Verdict.forRowType(summary: Boolean): Verdict =
        if (summary && !renderable) copy(reason = "group-summary") else this

    /**
     * Whether a drawable the host already composed is, visually, just the app's own launcher icon.
     *
     * `getHeaderIconBadgeDrawable` is a pure LruCache lookup (decompiled: it only calls
     * `headerIconBadgeCache.get(key)`), so whatever it hands back was composed earlier. A cached
     * result that pixel-matches the launcher icon is a composition from the placeholder era —
     * made before the app attached the sender photo — and must be evicted so the host recomposes
     * from the notification it has *now*. The comparison is deliberately coarse — the host rounds,
     * pads and badges the picture — so only the centre of the artwork is compared.
     */
    fun looksLikeLauncherIcon(
        context: Context?,
        packageName: String?,
        drawable: Drawable?,
    ): Boolean {
        if (context == null || packageName.isNullOrEmpty() || drawable == null) return false
        val appIcon = runCatching {
            context.packageManager.getApplicationIcon(packageName)
        }.getOrNull() ?: return false
        val difference = artworkDifference(drawable, appIcon) ?: return false
        return difference < HOSTED_PLACEHOLDER_TOLERANCE
    }

    private fun judge(
        context: Context,
        packageName: String,
        largeIcon: Icon,
    ): Verdict {
        val avatar = largeIcon.loadDrawable(context)
            ?: return Verdict(renderable = false, reason = "large-icon-undecodable")
        val appIcon = runCatching {
            context.packageManager.getApplicationIcon(packageName)
        }.getOrNull() ?: return Verdict(renderable = true, reason = "app-icon-unavailable")
        val difference = artworkDifference(avatar, appIcon)
            ?: return Verdict(renderable = true, reason = "sample-failed")
        val placeholder = difference < PLACEHOLDER_TOLERANCE
        return Verdict(
            renderable = !placeholder,
            reason = if (placeholder) REASON_LAUNCHER_PLACEHOLDER else REASON_CONTACT_PHOTO,
            difference = difference,
        )
    }

    /**
     * Mean per-channel difference over the centre of the artwork.
     *
     * Both pictures are drawn into the same 16x16 box and only the middle 10x10 is compared: that
     * drops the padding, rounding and badge the host adds around the edges while keeping the part
     * that actually identifies the image. Transparent samples are skipped so a circular avatar is
     * not penalised for its corners.
     */
    private fun artworkDifference(left: Drawable, right: Drawable): Double? {
        val leftSample = sample(left) ?: return null
        val rightSample = sample(right) ?: return null
        var total = 0L
        var count = 0
        for (y in SAMPLE_MARGIN until SAMPLE_SIZE - SAMPLE_MARGIN) {
            for (x in SAMPLE_MARGIN until SAMPLE_SIZE - SAMPLE_MARGIN) {
                val index = y * SAMPLE_SIZE + x
                val first = leftSample[index]
                val second = rightSample[index]
                if ((first ushr 24) < 32 || (second ushr 24) < 32) continue
                total += abs(((first ushr 16) and 0xFF) - ((second ushr 16) and 0xFF)).toLong()
                total += abs(((first ushr 8) and 0xFF) - ((second ushr 8) and 0xFF)).toLong()
                total += abs((first and 0xFF) - (second and 0xFF)).toLong()
                count++
            }
        }
        if (count == 0) return null
        return total.toDouble() / (count * 3.0)
    }

    private fun sample(drawable: Drawable): IntArray? = try {
        val bitmap = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888)
        val previousBounds = Rect(drawable.bounds)
        drawable.setBounds(0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        drawable.draw(Canvas(bitmap))
        drawable.bounds = previousBounds
        val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
        bitmap.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        bitmap.recycle()
        pixels
    } catch (_: Throwable) {
        null
    }

    /**
     * `Notification#getLargeIcon()` is the modern accessor, but apps that still post the legacy
     * `android.largeIcon` bitmap leave it null on some builds — hence the extras fallback.
     */
    private fun largeIconOf(notification: Notification, extras: Bundle): Icon? =
        runCatching { notification.getLargeIcon() }.getOrNull()
            ?: bitmapIcon(extras, Notification.EXTRA_LARGE_ICON)
            ?: bitmapIcon(extras, Notification.EXTRA_LARGE_ICON_BIG)

    private fun bitmapIcon(extras: Bundle, key: String): Icon? = runCatching {
        (parcelable(extras, key) as? Bitmap)?.let(Icon::createWithBitmap)
    }.getOrNull()

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

    /** Side of the square both icons are scaled to before being compared. */
    private const val SAMPLE_SIZE = 16

    /** Border pixels dropped from the comparison, which is where host rounding and badges live. */
    private const val SAMPLE_MARGIN = 3

    /** Mean per-channel difference below which the avatar slot holds the launcher icon. */
    private const val PLACEHOLDER_TOLERANCE = 22.0

    /** Same comparison, but against a drawable the host already composed (padded, rounded). */
    private const val HOSTED_PLACEHOLDER_TOLERANCE = 34.0

    /** Keyed by notification key + large-icon identity, so a re-post is re-evaluated. */
    private val avatarDecisions = LruCache<String, Verdict>(64)

    /** [Verdict.reason] meaning the avatar slot really holds a sender photo. */
    const val REASON_CONTACT_PHOTO = "contact-photo"

    /** [Verdict.reason] meaning the avatar slot holds the app's own icon. */
    const val REASON_LAUNCHER_PLACEHOLDER = "launcher-icon-placeholder"

    /** Notification categories ColorOS renders with a per-sender avatar. */
    private val AVATAR_CATEGORIES = setOf(
        Notification.CATEGORY_MESSAGE,
        Notification.CATEGORY_EMAIL,
        Notification.CATEGORY_SOCIAL,
    )
}
