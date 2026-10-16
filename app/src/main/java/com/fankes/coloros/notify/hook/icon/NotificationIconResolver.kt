package com.fankes.coloros.notify.hook.icon

import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toDrawable
import com.fankes.coloros.notify.diagnostics.DiagnosticEvent
import com.fankes.coloros.notify.diagnostics.DiagnosticLevel
import com.fankes.coloros.notify.diagnostics.Diagnostics
import com.fankes.coloros.notify.diagnostics.OccurrencePolicy
import com.fankes.coloros.notify.hook.runtimeFailure
import com.fankes.coloros.notify.rules.IconRule
import com.fankes.coloros.notify.rules.RuleStore

internal class NotificationIconResolver(
    config: RuleStore.ModuleConfig,
    private val rules: Map<String, IconRule>,
    private val themeIcons: ThemeIconProvider,
    private val iconConfiguration: OplusIconConfigurationReader,
    private val diagnostics: Diagnostics,
    private val revision: Long,
) {
    private val policyConfig = config.toPolicyConfig()

    data class StatusBarIconRenderPlan(
        val icon: Icon,
        val isColorable: Boolean,
    )

    data class PanelIconRenderPlan(
        val drawable: Drawable,
        val tintColor: Int?,
    )

    fun resolveStatusBarIconPlan(
        context: Context,
        sbn: StatusBarNotification,
        originalSmallIcon: Icon?,
    ): StatusBarIconRenderPlan? = resolveOrFallback("status_bar", "状态栏图标解析失败") {
        if (shouldKeepHostDefault(sbn)) return null
        resolveThemeIconReplacement(context, sbn)
            ?.toStatusBarIconRenderPlanOrNull(context, "status_bar")
            ?.let { return it }
        val forcedReplacement = resolveRuleIconReplacement(
            sbn,
            OriginalIconCompatibility.Unchecked,
        )
        forcedReplacement
            ?.toStatusBarIconRenderPlanOrNull(context, "status_bar")
            ?.let { return it }

        val originalDrawable = originalSmallIcon?.loadDrawable(context)?.mutate() ?: return null
        val originalCompatibility = NotificationIconMaskClassifier.classify(originalDrawable)
        if (forcedReplacement == null) {
            resolveRuleIconReplacement(
                sbn,
                originalCompatibility.toPolicyCompatibility(),
            )
                ?.toStatusBarIconRenderPlanOrNull(context, "status_bar")
                ?.let { return it }
        }
        originalSmallIcon
            .takeIf { originalCompatibility == NotificationIconMaskCompatibility.Compatible }
            ?.let { icon -> StatusBarIconRenderPlan(icon = icon, isColorable = true) }
    }

    fun resolvePanelIconPlan(
        context: Context,
        sbn: StatusBarNotification,
        originalSmallIcon: Icon?,
    ): PanelIconRenderPlan? = resolveOrFallback("panel", "通知面板图标解析失败") {
        // ColorOS renders the "personal avatar + app-icon badge" composition itself for the picked
        // packages. Leave those rows, banner cards and lock-screen capsules untouched — but only
        // when this notification has an avatar for the host to compose (see the resolver method).
        if (shouldYieldToHostAvatar(context, sbn.packageName, sbn)) return@resolveOrFallback null
        resolvePanelIconPlanBody(context, sbn, originalSmallIcon)
    }

    /**
     * The collapsed group row is the app's own summary: ColorOS draws the app icon plus a count
     * badge there (`GroupIconManager` / `OplusNotificationGroupExtImpl.attachCollapsedGroupBadge`)
     * and never a contact avatar, so honouring the avatar here would only replace a themed icon with
     * the plain one. That row therefore keeps the configured icon source.
     */
    fun resolveGroupSummaryIconPlan(
        context: Context,
        sbn: StatusBarNotification,
        originalSmallIcon: Icon?,
    ): PanelIconRenderPlan? = resolveOrFallback("panel", "通知面板图标解析失败") {
        resolvePanelIconPlanBody(context, sbn, originalSmallIcon)
    }

    private fun resolvePanelIconPlanBody(
        context: Context,
        sbn: StatusBarNotification,
        originalSmallIcon: Icon?,
    ): PanelIconRenderPlan? {
        if (
            !NotificationIconPolicy.shouldProcessPanel(
                config = policyConfig,
                isMediaNotification = sbn.isMediaNotification(),
                isOplusPush = sbn.isOplusPush(),
            )
        ) return null
        resolveThemeIconReplacement(context, sbn)
            ?.toPanelIconRenderPlanOrNull(context, "panel")
            ?.let { return it }

        val forcedReplacement = resolveRuleIconReplacement(
            sbn,
            OriginalIconCompatibility.Unchecked,
        )
        forcedReplacement?.toPanelIconRenderPlanOrNull(context, "panel")?.let { return it }

        val originalDrawable = originalSmallIcon?.loadDrawable(context)?.mutate() ?: return null
        val originalCompatibility = NotificationIconMaskClassifier.classify(originalDrawable)
        if (forcedReplacement == null) {
            resolveRuleIconReplacement(
                sbn,
                originalCompatibility.toPolicyCompatibility(),
            )
                ?.toPanelIconRenderPlanOrNull(context, "panel")
                ?.let { return it }
        }

        if (originalCompatibility == NotificationIconMaskCompatibility.Compatible) {
            return PanelIconRenderPlan(
                drawable = originalDrawable,
                tintColor = context.defaultPanelIconTint,
            )
        }
        return null
    }

    private fun resolveThemeIconReplacement(
        context: Context,
        sbn: StatusBarNotification,
    ): IconReplacement? {
        if (!NotificationIconPolicy.shouldResolveTheme(policyConfig)) return null
        return themeIcons.resolve(context, sbn)?.let(IconReplacement::ThemeIcon)
    }

    private fun resolveRuleIconReplacement(
        sbn: StatusBarNotification,
        originalCompatibility: OriginalIconCompatibility,
    ): IconReplacement? {
        val rule = rules[sbn.rulePackageName()]
        return when (
            NotificationIconPolicy.selectRuleReplacement(
                config = policyConfig,
                ruleEnabled = rule?.isEnabled == true,
                ruleEnabledAll = rule?.isEnabledAll == true,
                isOplusPush = sbn.isOplusPush(),
                originalCompatibility = originalCompatibility,
            )
        ) {
            RuleReplacement.Rule -> rule?.let(IconReplacement::RuleIcon)
            RuleReplacement.Placeholder -> IconReplacement.Placeholder
            RuleReplacement.None -> null
        }
    }

    private fun IconReplacement.toStatusBarIconRenderPlan(
        context: Context,
    ): StatusBarIconRenderPlan = StatusBarIconRenderPlan(
        icon = when (this) {
            is IconReplacement.ThemeIcon -> Icon.createWithBitmap(bitmap)
            is IconReplacement.RuleIcon -> Icon.createWithBitmap(
                rule.iconBitmap.withDarkEffect(context.isDarkIconEffectEnabled())
            )
            IconReplacement.Placeholder -> Icon.createWithBitmap(PlaceholderIconFactory.createBitmap())
        },
        // Theme icons are full-color launcher assets. Rule, placeholder and restored original
        // icons are notification masks whose RGB is replaced by SystemUI.
        isColorable = this !is IconReplacement.ThemeIcon,
    )

    private fun IconReplacement.toStatusBarIconRenderPlanOrNull(
        context: Context,
        feature: String,
    ): StatusBarIconRenderPlan? = try {
        toStatusBarIconRenderPlan(context)
    } catch (exception: Exception) {
        reportReplacementFailure(feature, exception)
        null
    }

    private fun IconReplacement.toPanelIconRenderPlan(context: Context): PanelIconRenderPlan {
        return when (this) {
            is IconReplacement.ThemeIcon -> PanelIconRenderPlan(
                drawable = bitmap.toDrawable(context.resources),
                tintColor = null,
            )
            is IconReplacement.RuleIcon -> PanelIconRenderPlan(
                drawable = rule.iconBitmap.toDrawable(context.resources),
                tintColor = (rule.iconColor.takeIf { it != 0 } ?: context.defaultPanelIconTint)
                    .withDarkEffect(context.isDarkIconEffectEnabled()),
            )
            IconReplacement.Placeholder -> PanelIconRenderPlan(
                drawable = PlaceholderIconFactory.createBitmap().toDrawable(context.resources),
                tintColor = context.defaultPanelIconTint,
            )
        }
    }

    private fun IconReplacement.toPanelIconRenderPlanOrNull(
        context: Context,
        feature: String,
    ): PanelIconRenderPlan? = try {
        toPanelIconRenderPlan(context)
    } catch (exception: Exception) {
        reportReplacementFailure(feature, exception)
        null
    }

    private fun IconReplacement.reportReplacementFailure(feature: String, exception: Exception) {
        diagnostics.runtimeFailure(
            scope = "icon_resolver:$feature:replacement:$diagnosticName",
            message = "候选图标无法解码或构造，跳过该候选",
            cause = exception,
            revision = revision,
        )
    }

    private fun Context.isDarkIconEffectEnabled(): Boolean {
        val configuration = iconConfiguration.read(this)
        return ThemeIconDarkEffect.isEnabled(configuration.uiMode, configuration.uxIconConfig)
    }

    private fun Bitmap.withDarkEffect(enabled: Boolean): Bitmap =
        if (enabled) ThemeIconDarkEffect.apply(this) else this

    private fun Int.withDarkEffect(enabled: Boolean): Int =
        if (enabled) ThemeIconDarkEffect.apply(this) else this

    private val Context.defaultPanelIconTint: Int
        get() {
            val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
                DARK_PANEL_ICON_TINT
            } else {
                LIGHT_PANEL_ICON_TINT
            }
        }

    private inline fun <T> resolveOrFallback(
        feature: String,
        message: String,
        block: () -> T?,
    ): T? = try {
        block()
    } catch (exception: Exception) {
        diagnostics.runtimeFailure(
            scope = "icon_resolver:$feature",
            message = message,
            cause = exception,
            revision = revision,
        )
        null
    }

    private fun StatusBarNotification.rulePackageName(): String =
        packageName.orEmpty()

    fun shouldKeepHostDefault(sbn: StatusBarNotification): Boolean =
        NotificationIconPolicy.shouldKeepHostDefault(policyConfig, sbn.isOplusPush())

    /**
     * True when the user picked this package for the personal avatar and ColorOS is going to render
     * that avatar for *this* notification, in which case the avatar-capable surfaces must keep the
     * host result. Callers that render outside [resolvePanelIconPlan] (lock-screen capsule, AOD)
     * must ask before consulting `StatusBarIconReplacementCache`, otherwise a status-bar
     * replacement cached for the same package would still take the slot.
     *
     * A picked package is not enough by itself — [HostAvatarPresence] decides whether the host has
     * an avatar to compose. Without that, WeChat's "视频通话中" (an ongoing notification with no
     * contact photo) stays with the host and shows the bare small icon instead of the themed one.
     * Pass a null [sbn] on surfaces that only know the notification key; they keep yielding.
     *
     * The status bar deliberately never yields here: for these packages the host only shows an
     * avatar for important conversations, and `StatusBarHooks` already lets that case through.
     */
    fun shouldYieldToHostAvatar(
        context: Context,
        packageName: String?,
        sbn: StatusBarNotification?,
    ): Boolean {
        val hostAvatarAvailable = sbn?.let(HostAvatarPresence::contains) ?: true
        val yield = NotificationIconPolicy.shouldYieldToHostAvatar(
            config = policyConfig,
            packageName = packageName,
            hostAvatarAvailable = hostAvatarAvailable,
        )
        if (!yield && sbn != null &&
            NotificationIconPolicy.isAvatarPriorityPackage(policyConfig, packageName)
        ) {
            diagnostics.report(
                level = DiagnosticLevel.Debug,
                event = DiagnosticEvent.HostAvatarReplace,
                message = "已勾选显示个人头像，但本条通知没有头像可合成，按图标来源替换",
                attributes = mapOf("package" to packageName),
                occurrence = OccurrencePolicy.Once("icon:host_avatar_replace:$packageName"),
            )
        }
        if (yield) {
            // host_listed=false means ColorOS itself does not render an avatar for that package, so
            // the pick only removes the module's own icon there — handy while testing a selection.
            diagnostics.report(
                level = DiagnosticLevel.Debug,
                event = DiagnosticEvent.HostAvatarYield,
                message = "宿主将显示联系人头像，本次不覆盖图标",
                attributes = mapOf(
                    "package" to packageName,
                    "host_listed" to HostAvatarPackages.contains(context, packageName),
                ),
                occurrence = OccurrencePolicy.Once("icon:host_avatar:$packageName"),
            )
        }
        return yield
    }

    fun shouldKeepHostAppIconBehavior(): Boolean =
        NotificationIconPolicy.shouldKeepHostAppIconBehavior(policyConfig)

    private fun StatusBarNotification.isOplusPush(): Boolean =
        opPkg == SYSTEM_FRAMEWORK_PACKAGE && opPkg != packageName

    private fun StatusBarNotification.isMediaNotification(): Boolean {
        if (notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) return true
        return notification.category == Notification.CATEGORY_TRANSPORT
    }

    private fun NotificationIconMaskCompatibility.toPolicyCompatibility(): OriginalIconCompatibility =
        when (this) {
            NotificationIconMaskCompatibility.Compatible -> OriginalIconCompatibility.Compatible
            NotificationIconMaskCompatibility.Incompatible -> OriginalIconCompatibility.Incompatible
            NotificationIconMaskCompatibility.Unknown -> OriginalIconCompatibility.Unchecked
        }

    private sealed class IconReplacement(val diagnosticName: String) {
        data class ThemeIcon(val bitmap: Bitmap) : IconReplacement("theme")
        data class RuleIcon(val rule: IconRule) : IconReplacement("rule")
        object Placeholder : IconReplacement("placeholder")
    }

    private fun RuleStore.ModuleConfig.toPolicyConfig() = IconPolicyConfig(
        rulesEnabled = rulesEnabled,
        source = when (iconSourceMode) {
            RuleStore.IconSourceMode.RuleLibrary -> PolicyIconSource.RuleLibrary
            RuleStore.IconSourceMode.DesktopTheme -> PolicyIconSource.DesktopTheme
        },
        panelEnabled = panelIconReplacementEnabled,
        handleOplusPush = oplusPushSpecialHandlingEnabled,
        placeholderEnabled = placeholderIconEnabled,
        hostAvatarPriorityEnabled = hostAvatarPriorityEnabled,
        avatarPriorityPackages = avatarPriorityPackages,
    )

    private companion object {
        const val SYSTEM_FRAMEWORK_PACKAGE = "android"
        const val LIGHT_PANEL_ICON_TINT = 0xFF707173.toInt()
        const val DARK_PANEL_ICON_TINT = 0xFFDCDCDC.toInt()
    }
}
