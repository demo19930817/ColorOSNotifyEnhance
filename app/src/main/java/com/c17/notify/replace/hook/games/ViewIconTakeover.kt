package com.c17.notify.replace.hook.games

import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import java.util.WeakHashMap

/**
 * Keeps one themed icon on an [ImageView] the game assistant paints over.
 *
 * Two host habits defeat a single `setImageDrawable`:
 *
 * - the app icons are loaded through Glide (`Lx1/d` → `ExternalApplicationManager.u`), so the
 *   request's result lands a few frames after the bind and replaces whatever was written first —
 *   and Glide's own disk cache replays the pre-module picture without ever calling the loader;
 * - the message-box mark is written by the host *after* the row is bound, from a coroutine.
 *
 * Re-checking once per frame until the picture sticks handles both without a timing guess: the
 * guard only writes when the host actually changed the drawable, and it gives up on its own once
 * the window closes, the view detaches, or the row is rebound for another package.
 */
internal class ViewIconTakeover(
    private val icon: ImageView,
    private val expected: Drawable,
    private val windowMillis: Long,
) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {

    private var observer: ViewTreeObserver? = null
    private var deadline = 0L
    private var applied = 0
    private var stopped = false

    fun start() {
        icon.setImageDrawable(expected)
        deadline = SystemClock.elapsedRealtime() + windowMillis
        observer = icon.viewTreeObserver
        runCatching { observer?.addOnPreDrawListener(this) }
        runCatching { icon.addOnAttachStateChangeListener(this) }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this) }
        runCatching { icon.removeOnAttachStateChangeListener(this) }
    }

    override fun onPreDraw(): Boolean {
        if (stopped) return true
        if (!icon.isAttachedToWindow || SystemClock.elapsedRealtime() > deadline) {
            stop()
            return true
        }
        if (icon.drawable === expected) return true
        if (applied >= MAX_REAPPLIES) {
            stop()
            return true
        }
        applied++
        icon.setImageDrawable(expected)
        return true
    }

    override fun onViewAttachedToWindow(view: View) = Unit

    override fun onViewDetachedFromWindow(view: View) = stop()

    private companion object {
        /** Covers Glide's async result plus the host's own coroutine, at one compare per frame. */
        const val MAX_REAPPLIES = 24
    }
}

/**
 * One guard per [ImageView]. Rebinding a recycled row replaces the previous guard instead of
 * stacking a second one on the same view.
 */
internal object GameIconTakeovers {

    private val active = WeakHashMap<ImageView, ViewIconTakeover>()

    fun take(icon: ImageView, drawable: Drawable, windowMillis: Long = DEFAULT_WINDOW_MILLIS) {
        synchronized(active) { active.remove(icon) }?.stop()
        val takeover = ViewIconTakeover(icon, drawable, windowMillis)
        synchronized(active) { active[icon] = takeover }
        takeover.start()
    }

    private const val DEFAULT_WINDOW_MILLIS = 5_000L
}
