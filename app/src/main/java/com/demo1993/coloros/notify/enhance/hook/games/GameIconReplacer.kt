package com.demo1993.coloros.notify.enhance.hook.games

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.demo1993.coloros.notify.enhance.diagnostics.DiagnosticEvent
import com.demo1993.coloros.notify.enhance.diagnostics.DiagnosticLevel
import com.demo1993.coloros.notify.enhance.diagnostics.Diagnostics
import com.demo1993.coloros.notify.enhance.diagnostics.OccurrencePolicy
import com.demo1993.coloros.notify.enhance.hook.icon.ThemeIconProvider

/**
 * Turns "this package's launcher icon" into "this package's theme icon" inside the game
 * assistant's own process.
 *
 * The game assistant is a normal app: it never goes through SystemUI's notification icon code, so
 * the theme icon has to be resolved again here. [ThemeIconProvider] is reused unchanged — it only
 * needs a [Context] and a package name, and it already caches per theme generation.
 */
internal class GameIconReplacer(
    private val diagnostics: Diagnostics,
    private val themeIcons: ThemeIconProvider,
    private val application: () -> Context?,
) {

    fun drawableFor(site: String, packageName: String?): Drawable? {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty() || pkg == SELF_PACKAGE) return null
        val context = application() ?: return null
        val bitmap = themeIcons.resolveByPackage(context, pkg) ?: run {
            traceMiss(site, pkg)
            return null
        }
        traceReplace(site, pkg)
        return BitmapDrawable(context.resources, bitmap).mutate()
    }

    private fun traceReplace(site: String, pkg: String) {
        diagnostics.report(
            level = DiagnosticLevel.Info,
            event = DiagnosticEvent.GameAssistantIconReplaced,
            message = "游戏助手图标已替换为主题图标",
            attributes = mapOf("site" to site, "pkg" to pkg),
            occurrence = OccurrencePolicy.RateLimited("games:replace:$site", TRACE_INTERVAL_MILLIS),
        )
    }

    /**
     * A miss is normal (a package without a themed icon), but it is also what a blocked theme API
     * looks like, so the first miss per site is kept in the release log.
     */
    private fun traceMiss(site: String, pkg: String) {
        diagnostics.report(
            level = DiagnosticLevel.Info,
            event = DiagnosticEvent.GameAssistantIconSkipped,
            message = "游戏助手图标未解析到主题图标，保留原图标",
            attributes = mapOf("site" to site, "pkg" to pkg),
            occurrence = OccurrencePolicy.Once("games:skip:$site"),
        )
    }

    private companion object {
        const val SELF_PACKAGE = "com.oplus.games"
        const val TRACE_INTERVAL_MILLIS = 2_000L
    }
}
