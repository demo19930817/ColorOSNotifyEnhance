package com.fankes.coloros.notify.hook.icon

import android.content.Context
import android.graphics.drawable.Drawable
import java.lang.reflect.Method

/**
 * Rebuilds the same composition ColorOS draws for "显示个人头像": the contact photo clipped to a
 * round rect with the app icon as a corner badge.
 *
 * The host builds it inside a coroutine on `Dispatchers.Default`, so the icon view keeps showing the
 * plain `smallIcon` for a few frames and then jumps to the avatar. Letting the module pre-fill that
 * window with the host's own helper keeps the two frames pixel-identical — the helper is the very
 * function the coroutine calls, and whatever it renders later simply replaces this drawable.
 */
internal object HostAvatarComposition {
    private const val HELPER_CLASS =
        "com.oplus.systemui.notification.row.wrapper.LayerDrawableWithAppIconKt"
    private const val HELPER_METHOD = "createBackgroundWithAppIcon"

    @Volatile
    private var helper: Method? = null

    /**
     * `createBackgroundWithAppIcon(Context, origin: Drawable, badge: Drawable, pkg: String,
     * contentSizePx: Integer): Drawable`. Returns null whenever the helper is missing (other
     * ColorOS builds) or the composition throws, in which case callers keep the host result.
     */
    fun compose(
        context: Context,
        avatar: Drawable,
        badge: Drawable,
        packageName: String,
        contentSizePx: Int,
    ): Drawable? = try {
        resolve(context)?.invoke(null, context, avatar, badge, packageName, contentSizePx) as? Drawable
    } catch (throwable: Throwable) {
        null
    }

    private fun resolve(context: Context): Method? {
        helper?.let { return it }
        return try {
            Class.forName(HELPER_CLASS, false, context.classLoader)
                .getDeclaredMethod(
                    HELPER_METHOD,
                    Context::class.java,
                    Drawable::class.java,
                    Drawable::class.java,
                    String::class.java,
                    Integer::class.java,
                )
                .apply { isAccessible = true }
                .also { helper = it }
        } catch (throwable: Throwable) {
            null
        }
    }
}
