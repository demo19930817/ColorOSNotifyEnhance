package com.fankes.coloros.notify.hook.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.fankes.coloros.notify.core.SystemPackages
import com.fankes.coloros.notify.diagnostics.DiagnosticEvent
import com.fankes.coloros.notify.diagnostics.DiagnosticLevel
import com.fankes.coloros.notify.diagnostics.Diagnostics
import com.fankes.coloros.notify.diagnostics.OccurrencePolicy
import com.fankes.coloros.notify.hook.HookRegistrar
import com.fankes.coloros.notify.hook.icon.NotificationIconResolver
import com.fankes.coloros.notify.hook.runtimeFailure
import java.util.concurrent.ConcurrentHashMap

internal class NotificationPanelHooks(
    private val hooks: HookRegistrar,
    private val diagnostics: Diagnostics,
    private val configuration: SystemUiConfiguration,
    private val members: PanelMembers,
) {
    private val systemUiIdCache = ConcurrentHashMap<String, Int>()
    private val headerIconClaims = HeaderIconClaimRegistry<ImageView, Drawable>()

    fun install() {
        installOplusHeaderHooks()
        installGroupIconManagerHooks()
        installPackageIconResolverHooks()
        members.headerOnContentUpdated?.let { method ->
            hooks.install(method, "systemui.panel.header.onContentUpdated") { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { wrapper ->
                    applyPanelIcon(wrapper, rowCandidate = chain.args.firstOrNull())
                }
                result
            }
        }
        members.headerResolveHeaderViews?.let { method ->
            hooks.install(method, "systemui.panel.header.resolveHeaderViews") { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { wrapper -> applyPanelIcon(wrapper) }
                result
            }
        }
        members.headerSetIsChildInGroup?.let { method ->
            hooks.install(method, "systemui.panel.header.setIsChildInGroup") { chain ->
                val result = chain.proceed()
                if (chain.args.firstOrNull() == false) {
                    chain.thisObject?.let(::applyPanelIcon)
                }
                result
            }
        }
        members.oplusGroupInitIcon?.let { method ->
            hooks.install(method, "systemui.panel.group.initIcon") { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { wrapper ->
                    applyPanelIcon(wrapper, target = PanelIconTarget.OplusGroupSummary)
                }
                result
            }
        }
        members.oplusGroupResolveHeaderViews?.let { method ->
            hooks.install(method, "systemui.panel.group.resolveHeaderViews") { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { wrapper ->
                    applyPanelIcon(wrapper, target = PanelIconTarget.OplusGroupSummary)
                }
                result
            }
        }
        // ColorOS 17 host (OplusNotificationGroupExtImpl) repaints the collapsed group badge in
        // flushIcon(NotificationChildrenContainer) instead of the removed template wrapper.
        members.oplusGroupFlushIcon?.let { method ->
            hooks.install(method, "systemui.panel.group.flushIcon") { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { wrapper ->
                    applyPanelIcon(wrapper, target = PanelIconTarget.OplusGroupSummary)
                }
                result
            }
        }
        // ColorOS 17 hands the badge icon view over directly in
        // attachCollapsedGroupBadge(NotificationHeaderView, CachingIconView, NotificationChildrenContainer),
        // which is the only group-summary entry that still exposes the icon view and its row.
        members.oplusGroupAttachBadge?.let { method ->
            hooks.install(method, "systemui.panel.group.attachBadge") { chain ->
                val result = chain.proceed()
                val snapshot = configuration.snapshot
                if (!snapshot.config.panelIconReplacementEnabled) return@install result
                val iconView = chain.args.getOrNull(1) as? ImageView
                val container = chain.args.getOrNull(2)
                if (iconView == null || container == null) return@install result
                val row = runCatching {
                    members.childrenContainerContainingNotification?.invoke(container)
                }.getOrNull()
                injectGroupSummaryIcon(iconView, row, snapshot)
                result
            }
        }
    }

    /**
     * Replaces the app icon ColorOS composes itself: the contact-avatar badge ("显示个人头像"), the
     * icon next to an expanded group row and the lock-screen notification icon list.
     *
     * Those rows ask `NotificationResolverExImpl.getPackageIconForNotification(...)` for their icon
     * and never read `Notification#smallIcon`, so neither writing into that field nor patching the
     * status bar descriptor ever reached the badge (the previous attempts failed silently exactly
     * because of this). Handing the desktop theme icon back from the resolver itself is the only
     * entry point that covers every one of those surfaces at once, and it deliberately does not
     * yield to the host avatar: the composition keeps its own contact photo, only the small
     * app-icon badge changes.
     *
     * The helper is a suspend function on ColorOS 15+, i.e. erasing to `(sbn, continuation) →
     * Object`. Returning the Drawable directly instead of proceeding is what makes it work there:
     * the caller's continuation either receives it right away or reads it as the suspension
     * result, while a host that does suspend internally would leak nothing because its own
     * continuation is simply dropped.
     */
    private fun installPackageIconResolverHooks() {
        val resolver = members.packageIconResolver ?: return
        hooks.install(
            resolver.getPackageIconForNotification,
            "systemui.panel.packageIconResolver",
        ) { chain ->
            val snapshot = configuration.snapshot
            if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
            if (!snapshot.resolver.panelThemeIconsEnabled()) return@install chain.proceed()
            val sbn = chain.args.firstOrNull { it is StatusBarNotification } as? StatusBarNotification
                ?: return@install chain.proceed()
            val context = resolverContext(resolver, chain.thisObject) ?: return@install chain.proceed()
            val packageName = sbn.packageName
            val replacement = try {
                snapshot.resolver.resolvePanelThemeDrawable(context, sbn)
            } catch (throwable: Throwable) {
                diagnostics.runtimeFailure(
                    scope = "panel:package_icon_resolver",
                    message = "角标诊断：$packageName 宿主应用图标读取失败，保留 ColorOS 原图标",
                    cause = throwable,
                    revision = snapshot.revision,
                )
                null
            }
            if (replacement == null) {
                diagnostics.report(
                    level = DiagnosticLevel.Warning,
                    event = DiagnosticEvent.PackageIconResolved,
                    message = "角标诊断：$packageName 主题图标解析失败，角标保持原版",
                    attributes = mapOf("package" to packageName),
                    occurrence = OccurrencePolicy.Once("package_icon_missing:$packageName"),
                )
                return@install chain.proceed()
            }
            val suspendSignature =
                resolver.getPackageIconForNotification.returnType == java.lang.Object::class.java
            diagnostics.report(
                level = DiagnosticLevel.Info,
                event = DiagnosticEvent.PackageIconResolved,
                message = "角标诊断：$packageName 已替换为主题图标" +
                    "（返回值=${replacement.javaClass.simpleName}，suspend=$suspendSignature）",
                attributes = mapOf("package" to packageName),
                occurrence = OccurrencePolicy.Once("package_icon_replaced:$packageName"),
            )
            replacement
        }
    }

    /**
     * The resolver owns the SystemUI context, so asking the hooked instance is both cheaper and
     * safer than poking at ActivityThread from a hook running before the host finished booting.
     */
    private fun resolverContext(
        resolver: PackageIconResolverMembers,
        host: Any?,
    ): Context? {
        if (host != null) {
            runCatching { resolver.getContext?.invoke(host) as? Context }
                .getOrNull()
                ?.let { return it }
        }
        return systemUiContext()
    }

    private fun systemUiContext(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }

    private fun injectGroupSummaryIcon(
        iconView: ImageView,
        row: Any?,
        snapshot: RuntimeSnapshot,
    ) {
        try {
            val sbn = row?.let(::statusBarNotificationFromRow) ?: return
            val plan = snapshot.resolver.resolveGroupSummaryIconPlan(
                context = iconView.context,
                sbn = sbn,
                originalSmallIcon = sbn.originalSmallIcon(diagnostics, snapshot.revision),
            ) ?: return
            if (!configuration.isCurrent(snapshot)) return
            iconView.clearColorFilter()
            iconView.imageTintList = null
            iconView.setImageDrawable(plan.drawable)
            plan.tintColor?.let { tint ->
                iconView.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
            }
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "panel:group_attach_badge",
                message = "聚合摘要徽标图标注入失败，保留 ColorOS 原结果",
                cause = exception,
                revision = snapshot.revision,
            )
        }
    }

    /**
     * ColorOS collapsed-group UI extracts WallpaperColors from the app/small icon and paints a
     * solid capsule ("色块"). When we already have a rule/theme replacement, take over icon + pill.
     */
    private fun installGroupIconManagerHooks() {
        val groupColorAnchor = members.groupIconInitIconViewColor
        if (groupColorAnchor != null) {
            hooks.install(groupColorAnchor, "systemui.panel.group.initIconViewColor") { chain ->
                val snapshot = configuration.snapshot
                if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
                val entry = chain.args.getOrNull(2) ?: return@install chain.proceed()
                val iconView = chain.args.getOrNull(1) as? ImageView
                    ?: return@install chain.proceed()
                try {
                    val sbn = members.notificationEntryGetSbn.invoke(entry) as? StatusBarNotification
                        ?: return@install chain.proceed()
                    val plan = snapshot.resolver.resolveGroupSummaryIconPlan(
                        context = iconView.context,
                        sbn = sbn,
                        originalSmallIcon = sbn.originalSmallIcon(diagnostics, snapshot.revision),
                    ) ?: return@install chain.proceed()
                    if (!configuration.isCurrent(snapshot)) return@install chain.proceed()

                    iconView.clearColorFilter()
                    iconView.imageTintList = null
                    iconView.setImageDrawable(plan.drawable)
                    plan.tintColor?.let { tint ->
                        iconView.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
                    }
                    null
                } catch (exception: Exception) {
                    diagnostics.runtimeFailure(
                        scope = "panel:group_icon_color",
                        message = "折叠分组图标着色覆盖失败，交回 ColorOS 原实现",
                        cause = exception,
                        revision = snapshot.revision,
                    )
                    chain.proceed()
                }
            }
        } else {
            // ColorOS 17 removed initIconViewColor(): it was folded into
            // initEntryIconDrawable(). That method also fills the pill and the count, so it can
            // never be swallowed — colour the group icon right after the host returns instead.
            members.groupIconInitEntryIconDrawable?.let { method ->
                hooks.install(method, "systemui.panel.group.initEntryIconDrawable") { chain ->
                    // args[4] == true marks the capsule path, owned by LockScreenCapsuleHooks.
                    if (chain.args.getOrNull(4) as? Boolean == true) return@install chain.proceed()
                    val snapshot = configuration.snapshot
                    if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
                    val entry = chain.args.getOrNull(0) ?: return@install chain.proceed()
                    val iconView = chain.args.getOrNull(1) as? ImageView
                        ?: return@install chain.proceed()
                    // The host paints a round background onto the icon view from this callback, so
                    // wrap it and re-apply our plan once the host's own painting has run.
                    val originalCallback = chain.args.getOrNull(5)
                    val result = if (originalCallback == null) {
                        chain.proceed()
                    } else {
                        // Chain.getArgs() is immutable; hand the wrapped callback over through
                        // proceed(). wrapHostCallback proxies the host's own Function1 class,
                        // which the module cannot implement — SystemUI ships its own stdlib.
                        chain.proceed(
                            chain.args.toTypedArray().also {
                                it[5] = wrapHostCallback(method.parameterTypes[5], originalCallback) {
                                    applyGroupSummaryIcon(iconView, entry, snapshot)
                                }
                            },
                        )
                    }
                    applyGroupSummaryIcon(iconView, entry, snapshot)
                    result
                }
            }
        }

        members.groupIconInitPillBgAndNumberColor?.let { method ->
            hooks.install(method, "systemui.panel.group.initPillBgAndNumberColor") { chain ->
                val snapshot = configuration.snapshot
                if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
                val iconInfo = chain.args.getOrNull(1) ?: return@install chain.proceed()
                val frame = chain.args.getOrNull(0) as? android.widget.FrameLayout
                    ?: return@install chain.proceed()
                try {
                    val isGrays = iconInfo.javaClass.methods
                        .firstOrNull { it.name == "isGraysIcon" && it.parameterTypes.isEmpty() }
                        ?.invoke(iconInfo) as? Boolean
                    // Colorful app-icon path paints a vibrant capsule; neutralize it.
                    if (isGrays == true) return@install chain.proceed()
                    val result = chain.proceed()
                    val night = (frame.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                    val muted = if (night) 0x33FFFFFF.toInt() else 0x33000000
                    val pill = frame.background as? android.graphics.drawable.GradientDrawable
                    if (pill != null) {
                        pill.setColor(muted)
                        // ColorOS pairs its own pill colour with a contrasting digit colour
                        // (light pill + black digit by day, dark pill + white digit at night). Only
                        // the pill is repainted here, so the digit has to be re-picked for the muted
                        // pill: the darkened day pill would otherwise keep a black, unreadable digit.
                        // The muted pill is dark in light mode and light in dark mode, hence the flip.
                        val number = chain.args.getOrNull(2) as? android.widget.TextView
                        number?.setTextColor(if (night) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                        number?.alpha = 1f
                    }
                    result
                } catch (exception: Exception) {
                    diagnostics.runtimeFailure(
                        scope = "panel:group_pill_color",
                        message = "折叠分组胶囊去色失败，交回 ColorOS 原实现",
                        cause = exception,
                        revision = snapshot.revision,
                    )
                    chain.proceed()
                }
            }
        }
    }

    private fun installOplusHeaderHooks() {
        val oplusHeader = members.oplusHeader ?: return
        installOplusHeaderColorGuard(oplusHeader)
        installOplusHeaderRoundnessReapply(oplusHeader)
    }

    private fun installOplusHeaderColorGuard(oplusHeader: OplusHeaderMembers) {
        val method = oplusHeader.updateIconColor ?: return
        val getIcon = members.headerGetIcon ?: return
        hooks.install(method, "systemui.panel.header.updateIconColor") { chain ->
            val snapshot = configuration.snapshot
            if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
            val extension = chain.thisObject ?: return@install chain.proceed()
            val shouldBlockColorUpdate = try {
                val wrapper = oplusHeader.getBase.invoke(extension)
                val icon = wrapper?.let { getIcon.invoke(it) as? ImageView }
                val row = wrapper?.let { members.notificationViewWrapperRow.get(it) }
                val sbn = row?.let(::statusBarNotificationFromRow)
                val drawable = icon?.drawable
                icon != null && sbn != null && drawable != null &&
                    headerIconClaims.isCurrentClaim(icon, sbn.key, drawable)
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "panel:header_color_guard",
                    message = "Oplus Header 二次着色保护失败，交回原实现",
                    cause = exception,
                    revision = snapshot.revision,
                )
                false
            }
            if (shouldBlockColorUpdate) false else chain.proceed()
        }
    }

    private fun installOplusHeaderRoundnessReapply(oplusHeader: OplusHeaderMembers) {
        val method = oplusHeader.updateIconRoundness ?: return
        hooks.install(method, "systemui.panel.header.updateIconRoundness") { chain ->
            val result = chain.proceed()
            chain.thisObject?.let { extension ->
                try {
                    oplusHeader.getBase.invoke(extension)?.let { wrapper ->
                        applyPanelIcon(wrapper, target = wrapper.iconTarget)
                    }
                } catch (exception: Exception) {
                    diagnostics.runtimeFailure(
                        scope = "panel:header_roundness_reapply",
                        message = "Oplus Header 圆角处理后恢复图标失败",
                        cause = exception,
                        revision = configuration.snapshot.revision,
                    )
                }
            }
            result
        }
    }

    private fun applyPanelIcon(
        wrapper: Any,
        rowCandidate: Any? = null,
        target: PanelIconTarget = PanelIconTarget.Header,
        expectedSnapshot: RuntimeSnapshot? = null,
        allowDeferredLookup: Boolean = true,
    ) {
        val snapshot = expectedSnapshot ?: configuration.snapshot
        if (expectedSnapshot != null && !configuration.isCurrent(snapshot)) return
        if (!snapshot.config.panelIconReplacementEnabled) return
        try {
            val row = rowCandidate
                ?: runCatching { members.notificationViewWrapperRow.get(wrapper) }.getOrNull()
                ?: return
            val rowView = row as? View
            val icon = when (target) {
                PanelIconTarget.Header -> members.headerGetIcon?.invoke(wrapper) as? ImageView
                // GroupIconManager colors NotificationHeaderViewWrapper.mIcon (CachingIconView).
                PanelIconTarget.OplusGroupSummary ->
                    (members.headerGetIcon?.invoke(wrapper) as? ImageView)
                        ?: rowView?.findOplusGroupSummaryIcon()
            } ?: run {
                if (target == PanelIconTarget.OplusGroupSummary && allowDeferredLookup && rowView != null) {
                    rowView.post {
                        if (configuration.isCurrent(snapshot)) {
                            applyPanelIcon(
                                wrapper = wrapper,
                                rowCandidate = row,
                                target = target,
                                expectedSnapshot = snapshot,
                                allowDeferredLookup = false,
                            )
                        }
                    }
                }
                return
            }
            if (target == PanelIconTarget.Header) headerIconClaims.release(icon)
            val sbn = statusBarNotificationFromRow(row) ?: return
            // The collapsed group row is the app's own summary and never carries a contact avatar,
            // so it keeps the configured icon source instead of yielding to the host.
            val plan = if (target == PanelIconTarget.OplusGroupSummary) {
                snapshot.resolver.resolveGroupSummaryIconPlan(
                    context = icon.context,
                    sbn = sbn,
                    originalSmallIcon = sbn.originalSmallIcon(diagnostics, snapshot.revision),
                )
            } else {
                snapshot.resolver.resolvePanelIconPlan(
                    context = icon.context,
                    sbn = sbn,
                    originalSmallIcon = sbn.originalSmallIcon(diagnostics, snapshot.revision),
                )
            } ?: return

            icon.applyRenderPlan(plan, target)
            if (target == PanelIconTarget.Header) {
                icon.drawable?.let { drawable -> headerIconClaims.claim(icon, sbn.key, drawable) }
            }
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "panel:replace_icon:${target.diagnosticName}",
                message = "通知面板规则图标注入失败，保留 ColorOS 原结果",
                cause = exception,
                revision = snapshot.revision,
            )
        }
    }

    private fun applyGroupSummaryIcon(iconView: ImageView, entry: Any, snapshot: RuntimeSnapshot) {
        if (!configuration.isCurrent(snapshot)) return
        try {
            val sbn = members.notificationEntryGetSbn.invoke(entry) as? StatusBarNotification ?: return
            val plan = snapshot.resolver.resolveGroupSummaryIconPlan(
                context = iconView.context,
                sbn = sbn,
                originalSmallIcon = sbn.originalSmallIcon(diagnostics, snapshot.revision),
            ) ?: return
            iconView.applyRenderPlan(plan, PanelIconTarget.OplusGroupSummary)
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "panel:replace_icon:${PanelIconTarget.OplusGroupSummary.diagnosticName}",
                message = "通知面板规则图标注入失败，保留 ColorOS 原结果",
                cause = exception,
                revision = snapshot.revision,
            )
        }
    }

    private fun statusBarNotificationFromRow(row: Any): StatusBarNotification? {
        val entry = members.expandableRowGetEntry.invoke(row) ?: return null
        return members.notificationEntryGetSbn.invoke(entry) as? StatusBarNotification
    }

    private val Any.iconTarget: PanelIconTarget
        get() = if (members.oplusGroupWrapper?.isInstance(this) == true) {
            PanelIconTarget.OplusGroupSummary
        } else {
            PanelIconTarget.Header
        }

    private fun View.findOplusGroupSummaryIcon(): ImageView? {
        val headerId = systemUiId("oplus_notification_collapsed_group_header")
        val containerId = systemUiId("icon_container")
        val iconId = systemUiId("icon")
        if (headerId == 0 || containerId == 0 || iconId == 0) return null
        val header = findViewById<View>(headerId) as? ViewGroup ?: return null
        val container = header.findViewById<View>(containerId) as? ViewGroup ?: header
        return container.findViewById<View>(iconId) as? ImageView
    }

    @SuppressLint("DiscouragedApi") // These resources belong to the hooked SystemUI APK, not this module.
    private fun View.systemUiId(name: String): Int =
        systemUiIdCache[name] ?: resources.getIdentifier(name, "id", SystemPackages.SYSTEM_UI)
            .also { systemUiIdCache[name] = it }

    private fun ImageView.applyRenderPlan(
        plan: NotificationIconResolver.PanelIconRenderPlan,
        target: PanelIconTarget,
    ) {
        clearHostDecoration(target)
        if (target == PanelIconTarget.OplusGroupSummary) {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
        }
        setImageDrawable(plan.drawable)
        plan.tintColor?.let { tint ->
            colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }
    }

    private fun ImageView.clearHostDecoration(target: PanelIconTarget) {
        background = null
        foreground = null
        clipToOutline = false
        imageTintList = null
        clearColorFilter()

        if (target != PanelIconTarget.OplusGroupSummary) return
        val container = parent as? View
        if (container?.id == systemUiId("icon_container")) {
            container.background = null
            container.foreground = null
            container.clipToOutline = false
            if (container is ViewGroup) {
                container.clipChildren = false
                container.clipToPadding = false
            }
        }
    }

    private enum class PanelIconTarget(val diagnosticName: String) {
        Header("header"),
        OplusGroupSummary("oplus_group_summary"),
    }
}
