package com.demo1993.coloros.notify.enhance.hook.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.demo1993.coloros.notify.enhance.core.SystemPackages
import com.demo1993.coloros.notify.enhance.diagnostics.DiagnosticEvent
import com.demo1993.coloros.notify.enhance.diagnostics.DiagnosticLevel
import com.demo1993.coloros.notify.enhance.diagnostics.Diagnostics
import com.demo1993.coloros.notify.enhance.diagnostics.OccurrencePolicy
import com.demo1993.coloros.notify.enhance.hook.HookRegistrar
import com.demo1993.coloros.notify.enhance.hook.icon.HostAvatarPresence
import com.demo1993.coloros.notify.enhance.hook.icon.NotificationIconResolver
import com.demo1993.coloros.notify.enhance.hook.runtimeFailure
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal class NotificationPanelHooks(
    private val hooks: HookRegistrar,
    private val diagnostics: Diagnostics,
    private val configuration: SystemUiConfiguration,
    private val members: PanelMembers,
) {
    private val systemUiIdCache = ConcurrentHashMap<String, Int>()
    private val headerIconClaims = HeaderIconClaimRegistry<ImageView, Drawable>()

    /**
     * Neutral `IconInfo` palette (light, dark) for the grouped-row count pill, as produced by the
     * host for monochrome small icons. Filled from the first grayscale row it sees, or derived from
     * `getExpandedPillBgColor()` with a white drawable before that.
     */
    @Volatile
    private var cachedNeutralPillPalette: IntArray? = null

    /**
     * The notification whose row `updateIconRoundness` is currently rounding. That method is the
     * only caller of `getHeaderIconBadgeDrawable`, and it calls it synchronously, so this is what
     * tells the badge hook which of the two forms it is answering for (see
     * [installHeaderBadgeDrawableHook]).
     */
    private val headerBadgeRow = ThreadLocal<StatusBarNotification?>()

    /** `OplusNotificationHeaderViewWrapperExImp.resolveHeaderIconContentSizePx()`, resolved once. */
    @Volatile
    private var contentSizeMethod: Method? = null
    private var contentSizeMethodResolved = false

    fun install() {
        installOplusHeaderHooks()
        installGroupIconManagerHooks()
        installPackageIconResolverHooks()
        installPackageIconByNameHooks()
        installHiddenContentHooks()
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
                // Both directions repaint the header. Leaving the group (false) needs the outline
                // restored; entering the group (true) — the moment a second notification of the
                // same app arrives — hands the row to the host's native small-icon composition, and
                // the previous row kept that raw look forever when no content update followed (the
                // weather "special focus" alert grouped under a newer weather post). Re-apply now
                // and once more after a short delay, because the host can compose asynchronously.
                chain.thisObject?.let { wrapper ->
                    applyPanelIcon(wrapper)
                    if (chain.args.firstOrNull() == true) {
                        runCatching { members.notificationViewWrapperRow.get(wrapper) }.getOrNull()
                            ?.let { row ->
                                val snapshot = configuration.snapshot
                                (row as? View)?.postDelayed(
                                    { if (configuration.isCurrent(snapshot)) applyPanelIcon(wrapper) },
                                    PANEL_RETRY_DELAY_MILLIS,
                                )
                            }
                    }
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
     * With "hide notification content" the row renders its **public** (redacted) layout, where
     * ColorOS never composes a contact avatar — the icon is the app's own small icon. The header
     * path above deliberately yields to the host avatar, so in this state nothing at all was
     * replaced and the lock-screen hero kept the stock icon. These hooks cover exactly that form;
     * they only ever run while `shouldShowPublic()` is true, so the normal avatar composition in
     * the notification shade can never be touched by them.
     */
    private fun installHiddenContentHooks() {
        val setHideSensitive = members.rowSetHideSensitive ?: return
        hooks.install(setHideSensitive, "systemui.panel.row.setHideSensitive") { chain ->
            val result = chain.proceed()
            chain.thisObject?.let { row ->
                // The public layout is (re)inflated right after this call, so run once now and
                // again after the next frames — the icon may not exist on the first pass yet.
                applyHiddenContentIcon(row)
                (row as? View)?.let { view ->
                    val snapshot = configuration.snapshot
                    view.post {
                        if (configuration.isCurrent(snapshot)) applyHiddenContentIcon(row)
                    }
                    view.postDelayed(
                        {
                            if (configuration.isCurrent(snapshot)) applyHiddenContentIcon(row)
                        },
                        PANEL_RETRY_DELAY_MILLIS,
                    )
                }
            }
            result
        }
    }

    /**
     * @return true when the row is showing its public layout and the theme icon was applied there.
     */
    private fun applyHiddenContentIcon(row: Any): Boolean {
        val snapshot = configuration.snapshot
        if (!snapshot.config.panelIconReplacementEnabled) return false
        if (!snapshot.resolver.panelThemeIconsEnabled()) return false
        if (members.rowShouldShowPublic?.invoke(row) as? Boolean != true) return false
        val layout = members.rowGetPublicLayout?.invoke(row) as? View ?: return false
        val sbn = statusBarNotificationFromRow(row) ?: return false
        val packageName = sbn.packageName
        try {
            val iconView = layout.findPublicIcon() ?: run {
                diagnostics.report(
                    level = DiagnosticLevel.Warning,
                    event = DiagnosticEvent.PackageIconResolved,
                    message = "角标诊断：$packageName 隐藏通知内容形态未找到公开视图图标，保留 ColorOS 原图标",
                    attributes = mapOf("package" to packageName),
                    occurrence = OccurrencePolicy.Once("hidden_content_icon_missing:$packageName"),
                )
                return false
            }
            val drawable = snapshot.resolver.resolvePanelThemeDrawable(iconView.context, sbn) ?: return false
            if (!configuration.isCurrent(snapshot)) return false
            iconView.background = null
            iconView.foreground = null
            iconView.imageTintList = null
            iconView.clearColorFilter()
            iconView.setImageDrawable(drawable)
            diagnostics.report(
                level = DiagnosticLevel.Info,
                event = DiagnosticEvent.PackageIconResolved,
                message = "角标诊断：$packageName 隐藏通知内容形态（公开视图）已替换为主题图标",
                attributes = mapOf("package" to packageName),
                occurrence = OccurrencePolicy.Once("hidden_content_replaced:$packageName"),
            )
            return true
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "panel:hidden_content_icon",
                message = "隐藏通知内容形态图标注入失败，保留 ColorOS 原图标",
                cause = exception,
                revision = snapshot.revision,
            )
            return false
        }
    }

    private fun View.findPublicIcon(): ImageView? {
        findViewById<ImageView>(android.R.id.icon)?.let { return it }
        val id = systemUiId("icon")
        return if (id != 0) findViewById(id) else null
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
     * With "hide notification content" on, the lock-screen hero asks for the app icon **by package**
     * (`getPackageIcon`) instead of through the notification-scoped entry above. That is why the
     * capsule at the bottom was already themed while the notification above it still showed the
     * stock icon: only the notification-scoped path was covered.
     *
     * `getHeaderIconBadgeDrawable` is deliberately *not* part of this pair — see
     * [installHeaderBadgeDrawableHook].
     */
    private fun installPackageIconByNameHooks() {
        val resolver = members.packageIconResolver ?: return
        installPackageIconByNameHook(
            resolver = resolver,
            method = resolver.getPackageIcon,
            tag = "systemui.panel.packageIconByName",
        )
        resolver.getHeaderIconBadgeDrawable?.let { method ->
            installHeaderBadgeDrawableHook(resolver, method)
        }
    }

    private fun installPackageIconByNameHook(
        resolver: PackageIconResolverMembers,
        method: Method?,
        tag: String,
    ) {
        val target = method ?: return
        hooks.install(target, tag) { chain ->
            val snapshot = configuration.snapshot
            if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
            if (!snapshot.resolver.panelThemeIconsEnabled()) return@install chain.proceed()
            val rawArgument = chain.args.firstOrNull { it is String } as? String
                ?: return@install chain.proceed()
            val packageName = packageNameFromArgument(rawArgument)
            val context = resolverContext(resolver, chain.thisObject) ?: return@install chain.proceed()
            val replacement = snapshot.resolver.resolveThemeDrawableByPackage(context, packageName)
            if (replacement == null) {
                diagnostics.report(
                    level = DiagnosticLevel.Warning,
                    event = DiagnosticEvent.PackageIconResolved,
                    message = "角标诊断：$packageName 按包名解析主题图标失败，保留 ColorOS 原图标",
                    attributes = mapOf("package" to packageName),
                    occurrence = OccurrencePolicy.Once("package_icon_by_name_missing:$packageName"),
                )
                return@install chain.proceed()
            }
            diagnostics.report(
                level = DiagnosticLevel.Info,
                event = DiagnosticEvent.PackageIconResolved,
                message = "角标诊断：$packageName 已按包名替换为主题图标（$tag）",
                attributes = mapOf("package" to packageName),
                occurrence = OccurrencePolicy.Once("package_icon_by_name_replaced:$packageName"),
            )
            replacement
        }
    }

    /**
     * `getHeaderIconBadgeDrawable(key)` is asked **only** by
     * `OplusNotificationHeaderViewWrapperExImp.updateIconRoundness(boolean)`, and it is a pure
     * `headerIconBadgeCache.get(key)` lookup (decompiled) — the composition itself runs in a
     * coroutine (`scheduleApplyIconWithBadge` → `createBackgroundWithAppIcon`) whose result is
     * applied through `applyExpandedCanvasIconWithBadge`, which *writes it back into the same
     * cache*. The host only evicts on row dismissal (`setRemoved()`), never on notification
     * updates. That protocol drives everything below:
     *
     * - **null is a signal, not a failure**: it tells `updateIconRoundness` "nothing composed
     *   yet", which is exactly what makes the host schedule its async "avatar + badge"
     *   composition. A non-null substitute (the 4.0.1–4.0.4 theme icon) is treated as a finished
     *   composition — applied, re-cached, and the avatar composition never runs. That is how the
     *   first WeChat message after a reboot got pinned to the theme icon;
     * - a notification that carries a contact avatar must therefore be handed back untouched,
     *   with stale cache entries (placeholder-era composition, or a theme icon pinned here
     *   earlier) evicted so the host recomposes from the notification it has *now*;
     * - a collapsed group summary or a placeholder-only row has no avatar to lose, so the
     *   configured theme icon replaces the "app icon + badge" the host would compose (3.5.8,
     *   3.5.9). If the app attaches the photo later and re-posts, the renderable branch detects
     *   the new avatar slot and evicts the pinned theme icon, and the row turns into
     *   "avatar + badge" like every later message.
     *
     * The row being rounded is recorded by [installOplusHeaderRoundnessReapply] right before the
     * host calls in, which is what makes the two cases distinguishable.
     */
    private fun installHeaderBadgeDrawableHook(
        resolver: PackageIconResolverMembers,
        method: Method,
    ) {
        hooks.install(method, "systemui.panel.headerBadgeDrawable") { chain ->
            val snapshot = configuration.snapshot
            if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
            if (!snapshot.resolver.panelThemeIconsEnabled()) return@install chain.proceed()
            val rowSbn = headerBadgeRow.get()
            val rowContext = resolverContext(resolver, chain.thisObject)
            val rawArgument = chain.args.firstOrNull { it is String } as? String
            val packageName = rowSbn?.packageName
                ?: rawArgument?.let(::packageNameFromArgument)
                ?: return@install chain.proceed()
            val context = rowContext ?: return@install chain.proceed()

            if (rowSbn != null) {
                val verdict = HostAvatarPresence.evaluate(context, rowSbn)
                if (verdict.renderable) {
                    // The notification really carries a sender photo: the "avatar + badge"
                    // composition must be left to the host. `getHeaderIconBadgeDrawable` is a
                    // pure LruCache lookup (decompiled: `headerIconBadgeCache.get(key)`), so a
                    // null return means "not composed yet" — that is the very signal which makes
                    // the host schedule its async avatar composition and cache the result via
                    // `putHeaderIconBadgeDrawable`. Handing back any non-null substitute (the
                    // 4.0.1–4.0.4 theme icon) makes the host treat it as a finished composition,
                    // apply *and re-cache* it, and the avatar composition never runs — the first
                    // WeChat message after a reboot was pinned to the theme icon exactly this way.
                    // So never substitute here; only evict stale cache entries so the host
                    // recomposes from the notification it has now:
                    //  1) a placeholder-era composition (host composed "app icon + badge" before
                    //     the app attached the photo);
                    //  2) a theme icon an earlier pass of this hook pinned into the cache.
                    val themedHere = themedBadgeRows.get(rowSbn.key) != null
                    val hosted = chain.proceed()
                    val hostedDrawable = hosted as? Drawable
                    val stalePlaceholder = hostedDrawable != null &&
                        HostAvatarPresence.looksLikeLauncherIcon(context, packageName, hostedDrawable)
                    if (themedHere || stalePlaceholder) {
                        evictHeaderIconBadge(resolver, chain.thisObject, rawArgument)
                        themedBadgeRows.remove(rowSbn.key)
                        diagnostics.report(
                            level = DiagnosticLevel.Info,
                            event = DiagnosticEvent.HostAvatarYield,
                            message = "角标诊断：$packageName 通知已有头像，清除过期角标缓存，由宿主重新合成头像+角标",
                            attributes = mapOf(
                                "package" to packageName,
                                "key" to rowSbn.key,
                                "verdict" to verdict.reason,
                                "stale" to if (stalePlaceholder) "host-placeholder" else "module-themed",
                            ),
                            occurrence = OccurrencePolicy.Once("header_badge_evict:${rowSbn.key}"),
                        )
                        return@install null
                    }
                    return@install hosted
                }
                // The avatar slot holds the app's own icon (or the row has no avatar at all, e.g.
                // a group summary or an ongoing "视频通话中"): the host would compose "app icon +
                // badge", so the configured theme icon replaces it. The host caches the theme
                // icon via `putHeaderIconBadgeDrawable`; when the app later attaches the photo and
                // re-posts, the renderable branch above detects it and evicts that cache entry,
                // letting the host recompose "avatar + badge" for the same notification.
                val replacement = snapshot.resolver.resolveThemeDrawableByPackage(context, packageName)
                if (replacement != null) {
                    themedBadgeRows.put(rowSbn.key, true)
                    diagnostics.report(
                        level = DiagnosticLevel.Info,
                        event = DiagnosticEvent.HostAvatarReplace,
                        message = "角标诊断：$packageName 头像位是应用图标占位（${verdict.reason}），按主题图标替换",
                        attributes = mapOf(
                            "package" to packageName,
                            "key" to rowSbn.key,
                            "verdict" to verdict.reason,
                            "difference" to verdict.difference.toString(),
                        ),
                        occurrence = OccurrencePolicy.Once("header_badge_placeholder:${rowSbn.key}"),
                    )
                    return@install replacement
                }
                return@install chain.proceed()
            }

            val replacement = snapshot.resolver.resolveThemeDrawableByPackage(context, packageName)
                ?: return@install chain.proceed()
            diagnostics.report(
                level = DiagnosticLevel.Info,
                event = DiagnosticEvent.PackageIconResolved,
                message = "角标诊断：$packageName 无头像行（聚合摘要）已替换为主题图标",
                attributes = mapOf("package" to packageName, "key" to (rowSbn?.key ?: rawArgument)),
                occurrence = OccurrencePolicy.Once("header_badge_replaced:$packageName"),
            )
            replacement
        }
    }

    /**
     * Notification keys whose badge cache entry this hook pinned to the theme icon. The entry is
     * consulted on the avatar-bearing passes above: once the app attaches the sender photo and
     * re-posts, the cached theme icon is stale and gets evicted so the host recomposes.
     */
    private val themedBadgeRows = LruCache<String, Boolean>(64)

    /**
     * `NotificationResolverExImpl.removeHeaderIconBadgeDrawable(String)` — the host only calls it
     * itself from `setRemoved()` (row dismissed), never on notification updates, so stale badge
     * compositions live as long as the notification does unless something evicts them.
     */
    private fun evictHeaderIconBadge(
        resolver: PackageIconResolverMembers,
        host: Any?,
        cacheKey: String?,
    ) {
        val method = resolver.removeHeaderIconBadgeDrawable ?: return
        if (host == null || cacheKey == null) return
        runCatching { method.invoke(host, cacheKey) }
    }

    /**
     * `getHeaderIconBadgeDrawable` takes the notification key, not a package name:
     * `0|com.android.mms|0|com.android.mms:sms::384|10119|com.android.mms`. Feeding that whole
     * string to the theme resolver always failed, so the host composed the collapsed-group badge
     * out of the raw small icon (SMS: a white glyph) and the row showed a blank block. The key's
     * second segment is the package name; anything without separators is already a package.
     */
    private fun packageNameFromArgument(raw: String): String {
        val value = raw.trim()
        val parts = value.split('|')
        if (parts.size >= 2 && parts[1].contains('.')) return parts[1]
        return value
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
        verifyAfterWrite: Boolean = true,
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
            if (verifyAfterWrite) verifyGroupSummaryIconAfterWrite(iconView, row, sbn, snapshot)
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "panel:group_attach_badge",
                message = "聚合摘要徽标图标注入失败，保留 ColorOS 原结果",
                cause = exception,
                revision = snapshot.revision,
            )
        }
    }

    /** Same race as [verifyIconAfterWrite], for the badge handed over by `attachCollapsedGroupBadge`. */
    private fun verifyGroupSummaryIconAfterWrite(
        iconView: ImageView,
        row: Any,
        sbn: StatusBarNotification,
        snapshot: RuntimeSnapshot,
        pass: Int = 0,
    ) {
        if (pass >= MAX_ICON_VERIFY_PASSES) return
        val written = iconView.drawable ?: return
        iconView.postDelayed({
            try {
                if (!configuration.isCurrent(snapshot) || !iconView.isAttachedToWindow) return@postDelayed
                if (statusBarNotificationFromRow(row)?.key != sbn.key) return@postDelayed
                if (iconView.drawable === written) {
                    verifyGroupSummaryIconAfterWrite(iconView, row, sbn, snapshot, pass + 1)
                    return@postDelayed
                }
                diagnostics.report(
                    level = DiagnosticLevel.Debug,
                    event = DiagnosticEvent.IconReapplied,
                    message = "宿主协程重绘了聚合摘要图标，重铺一次",
                    attributes = mapOf("package" to sbn.packageName, "key" to sbn.key),
                    occurrence = OccurrencePolicy.RateLimited("panel:group_icon_reapply", 60_000L),
                )
                injectGroupSummaryIcon(iconView, row, snapshot, verifyAfterWrite = false)
                verifyGroupSummaryIconAfterWrite(iconView, row, sbn, snapshot, pass + 1)
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "panel:group_icon_reapply",
                    message = "重铺聚合摘要图标失败",
                    cause = exception,
                    revision = snapshot.revision,
                )
            }
        }, PANEL_RETRY_DELAY_MILLIS shl pass)
    }

    /**
     * ColorOS collapsed-group UI extracts WallpaperColors from the app/small icon and paints a
     * solid capsule ("色块"). When we already have a rule/theme replacement we take over the icon
     * only — the count pill stays on the host palette, otherwise rows split into two different
     * badge colours depending on which icon path they took.
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

        // The count pill ("上标") is colour-matched to the notification's own small icon by the
        // host: `GroupIconManager$createSmallIconColorTask$2` runs
        // `WallpaperColors.fromDrawable(smallIcon)` → Material3 content surface. Mono small icons
        // therefore land on the neutral surface pill, while a colourful one keeps its hue and the
        // row shows a coloured badge next to neutral neighbours ("颜色不一样"). Nothing here is
        // about our replacement icon — the host reads `Notification.smallIcon`, not the view.
        installGroupPillPaletteAlignment()
    }

    /**
     * Pins every grouped row to the neutral pill palette the host itself produces for monochrome
     * small icons.
     *
     * The neutral pair is taken from the host, never guessed: first from any grayscale row that has
     * already been painted (`IconInfo.isGraysIcon == true`, i.e. the very rows the neighbours use),
     * and otherwise from `GroupIconManager.getExpandedPillBgColor()` fed a plain white drawable —
     * the same function the host runs on a mono small icon. The recolour is done by handing the host
     * a neutral `IconInfo`, so night mode, blur mode, digit colour, alpha and the pill shadow all
     * stay its own business.
     */
    private fun installGroupPillPaletteAlignment() {
        val method = members.groupIconInitPillBgAndNumberColor ?: return
        val pill = members.groupPill ?: return
        hooks.install(method, "systemui.panel.group.pillPalette") { chain ->
            val snapshot = configuration.snapshot
            if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
            val iconInfo = chain.args.getOrNull(1) ?: return@install chain.proceed()
            try {
                val grayscale = pill.isGrayscaleIconInfo.invoke(iconInfo) as? Boolean
                    ?: return@install chain.proceed()
                if (grayscale) {
                    // Remember the palette the host just used for a mono small icon. This is the
                    // reference every coloured row should end up on.
                    val light = pill.iconInfoLightColor.invoke(iconInfo) as? Int
                    val dark = pill.iconInfoDarkColor.invoke(iconInfo) as? Int
                    if (light != null && dark != null) {
                        cachedNeutralPillPalette = intArrayOf(light, dark)
                    }
                    return@install chain.proceed()
                }
                val context = (chain.args.getOrNull(0) as? View)?.context
                    ?: return@install chain.proceed()
                val neutral = neutralPillPalette(context) ?: return@install chain.proceed()
                val replacement = pill.iconInfoConstructor.newInstance(
                    neutral[0],
                    neutral[1],
                    neutral[0],
                    java.lang.Boolean.FALSE,
                )
                chain.proceed(chain.args.toTypedArray().also { it[1] = replacement })
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "panel:group_pill_palette",
                    message = "折叠分组上标配色对齐失败，交回 ColorOS 原实现",
                    cause = exception,
                    revision = snapshot.revision,
                )
                chain.proceed()
            }
        }
    }

    private fun neutralPillPalette(context: Context): IntArray? {
        cachedNeutralPillPalette?.let { return it }
        val palette = members.groupPill ?: return null
        return try {
            val triple = palette.expandedPillBgColor.invoke(
                palette.expandedPillBgColorReceiver,
                context,
                ColorDrawable(Color.WHITE),
            ) ?: return null
            val getters = triple.javaClass.methods
            val light = getters.firstOrNull { it.name == "getFirst" }?.invoke(triple) as? Int
                ?: return null
            val dark = getters.firstOrNull { it.name == "getSecond" }?.invoke(triple) as? Int
                ?: return null
            intArrayOf(light, dark).also { cachedNeutralPillPalette = it }
        } catch (exception: Exception) {
            // A missing Companion helper only costs the cold-start case; coloured rows then keep
            // the host colour until a mono row has been painted once.
            diagnostics.report(
                level = DiagnosticLevel.Debug,
                event = DiagnosticEvent.MemberMissing,
                message = "无法按白色取色推导中性角标配色",
                cause = exception,
                attributes = emptyMap(),
                occurrence = OccurrencePolicy.Once("panel:neutral_pill_palette"),
            )
            null
        }
    }

    private fun installOplusHeaderHooks() {
        val oplusHeader = members.oplusHeader ?: return
        installOplusHeaderColorGuard(oplusHeader)
        installOplusHeaderRoundnessReapply(oplusHeader)
        installOplusHeaderContentUpdatedReapply(oplusHeader)
        installOplusHeaderBadgeApplyReapply(oplusHeader)
    }

    /**
     * `applyExpandedCanvasIconWithBadge(...)` is where every asynchronous host repaint of a row icon
     * ends up: the `Dispatchers.Default` avatar composition (`scheduleApplyIconWithBadge$1`), the
     * rounded/badged redraw after `getHeaderIconBadgeDrawable`. A one-shot check a few hundred
     * milliseconds after our own write loses that race whenever the host is slower, and the row then
     * stays on the plain `smallIcon` for good — the "icon not replaced" rows in the shade.
     *
     * Re-applying right after the host returns wins the race by construction: this is its last write,
     * and the re-apply resolves the same plan the rest of the panel uses (avatar rows yield to
     * [prefillHostAvatar], everything else gets the themed icon).
     */
    private fun installOplusHeaderBadgeApplyReapply(oplusHeader: OplusHeaderMembers) {
        val method = oplusHeader.applyExpandedCanvasIconWithBadge ?: return
        hooks.install(method, "systemui.panel.header.applyExpandedCanvasIconWithBadge") { chain ->
            val result = chain.proceed()
            chain.thisObject?.let { extension ->
                try {
                    oplusHeader.getBase.invoke(extension)?.let { wrapper ->
                        applyPanelIcon(wrapper, target = wrapper.iconTarget)
                    }
                } catch (exception: Exception) {
                    diagnostics.runtimeFailure(
                        scope = "panel:header_badge_apply",
                        message = "Oplus Header 徽标合成后恢复图标失败",
                        cause = exception,
                        revision = configuration.snapshot.revision,
                    )
                }
            }
            result
        }
    }

    /**
     * ColorOS 17 moved the header refresh of every Oplus template wrapper (messaging, big text,
     * media, call, big picture) into `proxyOnContentUpdated(ExpandableNotificationRow)`, which those
     * wrappers call directly instead of going through `NotificationHeaderViewWrapper.onContentUpdated`.
     * Our base-class hook therefore never fires for an in-place content update — a new WeChat
     * message, a media title change — and the host's own pass leaves `mIcon` showing the raw
     * `smallIcon` again. Re-apply right after the proxy returns, which is where the host has just
     * finished rewriting the icon tag.
     */
    private fun installOplusHeaderContentUpdatedReapply(oplusHeader: OplusHeaderMembers) {
        val method = oplusHeader.proxyOnContentUpdated ?: return
        hooks.install(method, "systemui.panel.header.proxyOnContentUpdated") { chain ->
            val result = chain.proceed()
            chain.thisObject?.let { extension ->
                try {
                    oplusHeader.getBase.invoke(extension)?.let { wrapper ->
                        applyPanelIcon(wrapper)
                    }
                } catch (exception: Exception) {
                    diagnostics.runtimeFailure(
                        scope = "panel:oplus_header_content_updated",
                        message = "Oplus Header 内容更新后恢复图标失败",
                        cause = exception,
                        revision = configuration.snapshot.revision,
                    )
                }
            }
            result
        }
    }

    private fun installOplusHeaderColorGuard(oplusHeader: OplusHeaderMembers) {
        val method = oplusHeader.updateIconColor ?: return
        val getIcon = members.headerGetIcon ?: return
        hooks.install(method, "systemui.panel.header.updateIconColor") { chain ->
            val snapshot = configuration.snapshot
            if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
            val extension = chain.thisObject ?: return@install chain.proceed()
            val wrapper = try {
                oplusHeader.getBase.invoke(extension)
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "panel:header_color_guard",
                    message = "Oplus Header 二次着色保护失败，交回原实现",
                    cause = exception,
                    revision = snapshot.revision,
                )
                null
            } ?: return@install chain.proceed()
            val icon = getIcon.invoke(wrapper) as? ImageView ?: return@install chain.proceed()
            val iconIdentifier = try {
                val row = members.notificationViewWrapperRow.get(wrapper)
                val sbn = row?.let(::statusBarNotificationFromRow)
                val drawable = icon.drawable
                if (
                    sbn != null &&
                    drawable != null &&
                    headerIconClaims.isCurrentClaim(icon, sbn.key, drawable)
                ) sbn.key else null
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "panel:header_color_guard",
                    message = "Oplus Header 二次着色保护失败，交回原实现",
                    cause = exception,
                    revision = snapshot.revision,
                )
                null
            }
            val result = if (iconIdentifier != null) false else chain.proceed()
            // The host tints the *drawable itself* with SRC_ATOP (see updateIconColor's bytecode),
            // so clearing the ImageView-level filter cannot undo it, and whether it tints at all
            // depends on a grayscale tag it only refreshes when the small-icon instance changes.
            // For the second notification of one app the tag still describes the first one, so the
            // themed icon we just wrote comes back as a solid silhouette. Re-applying after the
            // host returns is idempotent when the claim already matched (same drawable, same key)
            // and restores the themed icon when it did not, exactly like the roundness hook below.
            if (iconIdentifier == null) {
                try {
                    applyPanelIcon(wrapper)
                } catch (exception: Exception) {
                    diagnostics.runtimeFailure(
                        scope = "panel:header_color_reapply",
                        message = "Oplus Header 二次着色后恢复图标失败",
                        cause = exception,
                        revision = snapshot.revision,
                    )
                }
            }
            result
        }
    }

    private fun installOplusHeaderRoundnessReapply(oplusHeader: OplusHeaderMembers) {
        val method = oplusHeader.updateIconRoundness ?: return
        hooks.install(method, "systemui.panel.header.updateIconRoundness") { chain ->
            val extension = chain.thisObject
            val wrapper = extension?.let { instance ->
                try {
                    oplusHeader.getBase.invoke(instance)
                } catch (exception: Exception) {
                    diagnostics.runtimeFailure(
                        scope = "panel:header_roundness_reapply",
                        message = "Oplus Header 圆角处理前读取 wrapper 失败",
                        cause = exception,
                        revision = configuration.snapshot.revision,
                    )
                    null
                }
            }
            // Within a few bytecodes the host asks for this very row's badge drawable
            // (`...getHeaderIconBadgeDrawable(key|pkg)`), so the row is recorded for that lookup.
            wrapper?.let(::statusBarNotificationOfWrapper)?.let { rowSbn -> headerBadgeRow.set(rowSbn) }
            val result = try {
                chain.proceed()
            } finally {
                headerBadgeRow.remove()
            }
            if (wrapper != null) {
                try {
                    applyPanelIcon(wrapper, target = wrapper.iconTarget)
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

    /** The notification a `NotificationHeaderViewWrapper` currently renders. */
    private fun statusBarNotificationOfWrapper(wrapper: Any): StatusBarNotification? =
        runCatching { members.notificationViewWrapperRow.get(wrapper) }.getOrNull()
            ?.let(::statusBarNotificationFromRow)

    private fun applyPanelIcon(
        wrapper: Any,
        rowCandidate: Any? = null,
        target: PanelIconTarget = PanelIconTarget.Header,
        expectedSnapshot: RuntimeSnapshot? = null,
        allowDeferredLookup: Boolean = true,
        verifyAfterWrite: Boolean = true,
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
            // "Hide notification content" shows the public (redacted) layout instead of the avatar
            // composition; handle the icon there and stop before the avatar yield runs.
            if (target == PanelIconTarget.Header && applyHiddenContentIcon(row)) return
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
            }
            if (plan == null) {
                // Yielding to the host avatar. ColorOS composes that avatar asynchronously, so the
                // row would paint the plain small icon first; pre-compose it here instead.
                if (target == PanelIconTarget.Header) prefillHostAvatar(icon, wrapper, sbn, snapshot)
                return
            }

            icon.applyRenderPlan(plan, target)
            if (target == PanelIconTarget.Header) {
                icon.drawable?.let { drawable -> headerIconClaims.claim(icon, sbn.key, drawable) }
            }
            // The collapsed group summary is repainted by GroupIconManager's own coroutine on
            // ColorOS 17, so it races the write exactly like the header's async small icon does.
            if (verifyAfterWrite) {
                verifyIconAfterWrite(icon, wrapper, row, sbn, snapshot, target)
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

    /**
     * ColorOS 的 `CachingIconView` 会在 `setTag(ImageTransformState, smallIcon)` 之后异步加载
     * smallIcon 并再次 `setImageDrawable`；折叠分组的摘要图标则由 `GroupIconManager` 的协程
     * 重绘。这两处回填如果晚于模块写入，行上就会退回宿主的灰度 smallIcon —— 表现为同应用里
     * 个别通知、或短信自动分组那一行是单色/空白剪影，其它行正常，与包名和通知内容无关，
     * 是纯时序竞争。
     *
     * 单次校验会输给更慢的宿主：日志里 `icon.reapplied` 与仍未替换的行同时存在，说明回填窗口
     * 可以晚于 256ms。因此这里按 256 / 512 / 1024 / 2048ms 递增复查 [MAX_ICON_VERIFY_PASSES] 轮，
     * 每轮只在 drawable 已不是我们写的那张时补铺，实例一致就安静地进入下一轮。
     *
     * 只针对模块接管的行（`plan != null`），所以不会和让路头像互相拉扯 —— 让路时
     * `applyPanelIcon` 会走 `prefillHostAvatar` 重新合成头像而不是写主题图标。
     */
    private fun verifyIconAfterWrite(
        icon: ImageView,
        wrapper: Any,
        row: Any,
        sbn: StatusBarNotification,
        snapshot: RuntimeSnapshot,
        target: PanelIconTarget,
        pass: Int = 0,
    ) {
        if (pass >= MAX_ICON_VERIFY_PASSES) return
        val written = icon.drawable ?: return
        val rowView = row as? View ?: return
        rowView.postDelayed({
            try {
                if (!configuration.isCurrent(snapshot) || !icon.isAttachedToWindow) return@postDelayed
                if (statusBarNotificationFromRow(row)?.key != sbn.key) return@postDelayed
                if (icon.drawable === written) {
                    verifyIconAfterWrite(icon, wrapper, row, sbn, snapshot, target, pass + 1)
                    return@postDelayed
                }
                diagnostics.report(
                    level = DiagnosticLevel.Debug,
                    event = DiagnosticEvent.IconReapplied,
                    message = "宿主异步回填 smallIcon 覆盖了主题图标，重铺一次",
                    attributes = mapOf("package" to sbn.packageName, "key" to sbn.key),
                    occurrence = OccurrencePolicy.RateLimited("panel:icon_reapply", 60_000L),
                )
                applyPanelIcon(
                    wrapper = wrapper,
                    rowCandidate = row,
                    target = target,
                    expectedSnapshot = snapshot,
                    allowDeferredLookup = false,
                    verifyAfterWrite = false,
                )
                verifyIconAfterWrite(icon, wrapper, row, sbn, snapshot, target, pass + 1)
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "panel:icon_reapply",
                    message = "重铺被宿主回填的图标失败",
                    cause = exception,
                    revision = snapshot.revision,
                )
            }
        }, PANEL_RETRY_DELAY_MILLIS shl pass)
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

    /**
     * ColorOS hides the "contact photo + app-icon badge" composition behind
     * `withContext(Dispatchers.Default)` → `createBackgroundWithAppIcon(...)`. Until that coroutine
     * comes back, the row shows the plain `smallIcon`, which reads as "先闪一下应用图标再跳成
     * 头像". Composing the same picture synchronously here fills that window: the host result that
     * arrives a frame or two later is the same visuals, so nothing jumps. Failures (missing helper,
     * no avatar, unknown size) simply leave the host's own first frame in place.
     */
    private fun prefillHostAvatar(
        icon: ImageView,
        wrapper: Any,
        sbn: StatusBarNotification,
        snapshot: RuntimeSnapshot,
    ) {
        try {
            val size = headerContentSizePx(wrapper, icon)
            if (size <= 0) return
            val drawable = snapshot.resolver.composeHostAvatarPreview(icon.context, sbn, size) ?: return
            icon.setImageDrawable(drawable)
            diagnostics.report(
                level = DiagnosticLevel.Debug,
                event = DiagnosticEvent.HostAvatarPrefill,
                message = "宿主异步合成头像前先铺一帧同构图，避免应用图标闪现",
                attributes = mapOf("package" to sbn.packageName, "size" to size.toString()),
                occurrence = OccurrencePolicy.Once("panel:host_avatar_prefill"),
            )
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "panel:host_avatar_prefill",
                message = "头像预合成失败，保留宿主第一帧",
                cause = exception,
                revision = snapshot.revision,
            )
        }
    }

    /**
     * `resolveHeaderIconContentSizePx()` is the very value the host boxes into its composer; the
     * laid-out width is only a fallback for builds that renamed it.
     */
    private fun headerContentSizePx(wrapper: Any, icon: ImageView): Int {
        resolveContentSizeMethod(wrapper)?.let { method ->
            runCatching { method.invoke(wrapper) as Int }.getOrNull()?.let { return it }
        }
        return icon.width
    }

    private fun resolveContentSizeMethod(wrapper: Any): Method? {
        contentSizeMethod?.let { return it }
        if (contentSizeMethodResolved) return null
        contentSizeMethodResolved = true
        return runCatching {
            wrapper.javaClass.getMethod("resolveHeaderIconContentSizePx").also { contentSizeMethod = it }
        }.getOrNull()
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

    private companion object {
        const val PANEL_RETRY_DELAY_MILLIS = 256L

        /**
         * How many times a written icon is re-checked while the host may still repaint it. Delays
         * double every pass (256 / 512 / 1024 / 2048 ms), so this covers roughly four seconds — the
         * window `CachingIconView` and the avatar coroutine were observed to land in.
         */
        const val MAX_ICON_VERIFY_PASSES = 4
    }
}
