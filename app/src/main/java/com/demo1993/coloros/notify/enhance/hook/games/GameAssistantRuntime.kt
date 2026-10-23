package com.demo1993.coloros.notify.enhance.hook.games

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import com.demo1993.coloros.notify.enhance.diagnostics.DiagnosticEvent
import com.demo1993.coloros.notify.enhance.diagnostics.DiagnosticLevel
import com.demo1993.coloros.notify.enhance.diagnostics.Diagnostics
import com.demo1993.coloros.notify.enhance.diagnostics.OccurrencePolicy
import com.demo1993.coloros.notify.enhance.hook.HookRegistrar
import com.demo1993.coloros.notify.enhance.hook.icon.OplusIconConfigurationReader
import com.demo1993.coloros.notify.enhance.hook.icon.ThemeIconProvider
import com.demo1993.coloros.notify.enhance.hook.memberMissing
import com.demo1993.coloros.notify.enhance.hook.reflect.Reflection
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Theme-icon support for the OPPO game assistant (`com.oplus.games`).
 *
 * The assistant draws three separate copies of an app's launcher icon while a game is running, and
 * none of them go through SystemUI:
 *
 * - the dock / edge panel app icons — [EDGE_MANAGER]`#u(String)`, one drawable per package;
 * - the "弹幕" chat bubble icon — [BARRAGE_MANAGER]`#loadNotificationIcon(Icon, String, Integer)`,
 *   which loads the notification's small icon;
 * - the app mark badge next to a contact avatar in the message box —
 *   [MESSAGE_BOX_MANAGER]`#Y(ImageView, MessageData)`.
 *
 * All three end up as a `Drawable` handed to an `ImageView`, so each hook replaces exactly that one
 * drawable and leaves every other decision (layout, alpha, tint, click) to the host.
 */
internal class GameAssistantRuntime(
    private val hooks: HookRegistrar,
    private val diagnostics: Diagnostics,
    private val processName: String,
) {

    fun install(classLoader: ClassLoader) {
        // The theme icon comes from a framework-only API that Android lists as hidden; the module
        // already does the same exemption for its SystemUI runtime.
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(THEME_ICON_FRAMEWORK_CLASS)
        }
        val iconConfiguration = OplusIconConfigurationReader(diagnostics)
        val replacer = GameIconReplacer(
            diagnostics = diagnostics,
            themeIcons = ThemeIconProvider(diagnostics, iconConfiguration),
            application = GameProcessApplication::current,
        )

        installEdgePanelIcons(classLoader, replacer)
        installBarrageIcon(classLoader, replacer)
        installMessageBadge(classLoader, replacer)
        probeThemeIconApi(replacer)

        diagnostics.report(
            level = DiagnosticLevel.Debug,
            event = DiagnosticEvent.HookInstalled,
            message = "游戏助手主题图标钩子已就绪",
            attributes = mapOf("process" to processName),
            occurrence = OccurrencePolicy.Once("games:ready:$processName"),
        )
    }

    /**
     * The theme API is documented for system processes only, so whether this app's uid may use it is
     * decided at runtime. One probe shortly after start records the answer in the release log
     * (`games.icon_replaced` / `games.icon_skipped`) instead of leaving a silent no-op.
     */
    private fun probeThemeIconApi(replacer: GameIconReplacer) {
        runCatching {
            Handler(Looper.getMainLooper()).postDelayed(
                { runCatching { replacer.drawableFor(SITE_PROBE, PROBE_PACKAGE) } },
                PROBE_DELAY_MILLIS,
            )
        }
    }

    /** Dock / edge-panel app icons: `ExternalApplicationManager.u(String): Drawable`. */
    private fun installEdgePanelIcons(classLoader: ClassLoader, replacer: GameIconReplacer) {
        val manager = Reflection.loadClassOrNull(EDGE_MANAGER, classLoader) { cause ->
            diagnostics.memberMissing(
                scope = "games:edge_panel_class",
                message = "未找到游戏助手应用图标管理器，跳过应用栏图标替换",
                cause = cause,
            )
        } ?: return
        val method = Reflection.findMethod(manager, "u", String::class.java) ?: run {
            diagnostics.memberMissing(
                scope = "games:edge_panel_method",
                message = "未找到 ExternalApplicationManager.u(String)，跳过应用栏图标替换",
            )
            return
        }
        hooks.install(method, "games.edge_panel.app_icon") { chain ->
            val packageName = chain.args.getOrNull(0) as? String
            replacer.drawableFor(SITE_EDGE_PANEL, packageName) ?: chain.proceed()
        }
    }

    /**
     * "弹幕" bubble icon: the host loads the notification's small icon and falls back to a bundled
     * resource id. Replacing the result keeps the bubble's text/avatar layout untouched.
     */
    private fun installBarrageIcon(classLoader: ClassLoader, replacer: GameIconReplacer) {
        val manager = Reflection.loadClassOrNull(BARRAGE_MANAGER, classLoader) { cause ->
            diagnostics.memberMissing(
                scope = "games:barrage_class",
                message = "未找到游戏助手弹幕管理器，跳过弹幕图标替换",
                cause = cause,
            )
        } ?: return
        val method = Reflection.findMethod(
            manager,
            "loadNotificationIcon",
            Icon::class.java,
            String::class.java,
            Integer::class.java,
        ) ?: run {
            diagnostics.memberMissing(
                scope = "games:barrage_method",
                message = "未找到 BarrageManager.loadNotificationIcon，跳过弹幕图标替换",
            )
            return
        }
        hooks.install(method, "games.barrage.notification_icon") { chain ->
            val packageName = chain.args.getOrNull(1) as? String
            replacer.drawableFor(SITE_BARRAGE, packageName) ?: chain.proceed()
        }
    }

    /**
     * App mark next to the contact avatar in the message box: the host sets a bundled icon resource
     * looked up from the package name, so the original call is skipped once a theme icon exists.
     */
    private fun installMessageBadge(classLoader: ClassLoader, replacer: GameIconReplacer) {
        val manager = Reflection.loadClassOrNull(MESSAGE_BOX_MANAGER, classLoader) { cause ->
            diagnostics.memberMissing(
                scope = "games:message_box_class",
                message = "未找到游戏助手消息盒子，跳过消息角标替换",
                cause = cause,
            )
        } ?: return
        val messageData = Reflection.loadClassOrNull(MESSAGE_DATA, classLoader) { cause ->
            diagnostics.memberMissing(
                scope = "games:message_data_class",
                message = "未找到游戏助手消息数据模型，跳过消息角标替换",
                cause = cause,
            )
        } ?: return
        val method = Reflection.findMethod(manager, "Y", ImageView::class.java, messageData) ?: run {
            diagnostics.memberMissing(
                scope = "games:message_badge_method",
                message = "未找到 MessageNotificationBoxManager.Y，跳过消息角标替换",
            )
            return
        }
        hooks.install(method, "games.message_box.app_mark") { chain ->
            val view = chain.args.getOrNull(0) as? ImageView
            val packageName = notificationPackage(chain.args.getOrNull(1))
            val themed = view?.let { replacer.drawableFor(SITE_MESSAGE_BADGE, packageName) }
            // The host also hides the mark for group conversations, so its own call must still run.
            // It loads the bundled icon through Glide, hence the pending request is cancelled before
            // the theme icon is written — otherwise Glide would overwrite it a frame later.
            val result = chain.proceed()
            if (view != null && themed != null) {
                clearGlideRequest(view, classLoader)
                view.setImageDrawable(themed)
            }
            result
        }
    }

    /**
     * `Glide.with(view).clear(view)`: cancels the asynchronous load the host had just started.
     * Reflection keeps the module free of a compile-time Glide dependency; a failure only means the
     * host's icon may be painted over once, which a later rebind corrects.
     */
    private fun clearGlideRequest(view: ImageView, classLoader: ClassLoader) {
        runCatching {
            val glide = Class.forName("com.bumptech.glide.Glide", false, classLoader)
            val manager = glide.getMethod("with", View::class.java).invoke(null, view)
            manager?.javaClass?.getMethod("clear", View::class.java)?.invoke(manager, view)
        }
    }

    private fun notificationPackage(messageData: Any?): String? {
        val data = messageData ?: return null
        return runCatching {
            val sbn = data.javaClass.getMethod("getSub").invoke(data) ?: return@runCatching null
            sbn.javaClass.getMethod("getPackageName").invoke(sbn) as? String
        }.getOrNull()
    }

    private companion object {
        const val EDGE_MANAGER = "business.secondarypanel.manager.ExternalApplicationManager"
        const val BARRAGE_MANAGER = "com.oplus.feature.barragenotification.BarrageManager"
        const val MESSAGE_BOX_MANAGER = "business.mainpanel.welfare.MessageNotificationBoxManager"
        const val MESSAGE_DATA = "business.mainpanel.welfare.MessageData"
        const val THEME_ICON_FRAMEWORK_CLASS = "Landroid/app/UxIconPackageManagerExt;"

        const val SITE_EDGE_PANEL = "edge_panel"
        const val SITE_BARRAGE = "barrage"
        const val SITE_MESSAGE_BADGE = "message_badge"
        const val SITE_PROBE = "probe"

        /** Always installed and always has a themed icon, so a miss means "theme API unavailable". */
        const val PROBE_PACKAGE = "com.android.settings"
        const val PROBE_DELAY_MILLIS = 3_000L
    }
}

/** The game assistant has no injectable context, so its [android.app.Application] is looked up once. */
internal object GameProcessApplication {

    @Volatile
    private var cached: Context? = null

    fun current(): Context? = cached ?: runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val application = activityThread.getMethod("currentApplication").invoke(null) as? Context
        application?.also { cached = it }
    }.getOrNull()
}
