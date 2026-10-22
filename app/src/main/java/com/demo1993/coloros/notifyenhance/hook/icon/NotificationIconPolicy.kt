package com.demo1993.coloros.notifyenhance.hook.icon

internal enum class PolicyIconSource {
    RuleLibrary,
    DesktopTheme,
}

/**
 * The SystemUI surface an icon is rendered on.
 *
 * The source is no longer a setting: every surface has one fixed owner. The status bar draws
 * rule-library icons (so the rule list keeps its fine-grained control), while everything inside
 * the notification centre — rows, group summaries, avatar badges, lock-screen island/AOD — draws
 * the desktop theme icon. Mixing the two through a global switch caused notifications to flip
 * between the two looks depending on which surface rendered them.
 */
internal enum class IconSurface(val source: PolicyIconSource) {
    StatusBar(PolicyIconSource.RuleLibrary),
    Panel(PolicyIconSource.DesktopTheme),
}

internal data class IconPolicyConfig(
    val rulesEnabled: Boolean,
    val panelEnabled: Boolean,
    val handleOplusPush: Boolean,
    val placeholderEnabled: Boolean,
    val hostAvatarPriorityEnabled: Boolean,
    val avatarPriorityPackages: Set<String>,
)

internal enum class RuleReplacement {
    Rule,
    Placeholder,
    None,
}

internal enum class OriginalIconCompatibility {
    Unchecked,
    Compatible,
    Incompatible,
}

internal object NotificationIconPolicy {

    /** The icon source that owns [surface], independent of any user setting. */
    fun policySource(surface: IconSurface): PolicyIconSource = surface.source

    fun shouldKeepHostDefault(
        config: IconPolicyConfig,
        surface: IconSurface,
        isOplusPush: Boolean,
    ): Boolean =
        shouldKeepHostAppIconBehavior(config, surface) && isOplusPush

    fun shouldKeepHostAppIconBehavior(
        config: IconPolicyConfig,
        surface: IconSurface,
    ): Boolean =
        policySource(surface) == PolicyIconSource.RuleLibrary && !config.handleOplusPush

    fun shouldProcessPanel(
        config: IconPolicyConfig,
        isMediaNotification: Boolean,
        isOplusPush: Boolean,
    ): Boolean =
        config.panelEnabled &&
            !isMediaNotification &&
            !shouldKeepHostDefault(config, IconSurface.Panel, isOplusPush)

    fun shouldResolveTheme(
        config: IconPolicyConfig,
        surface: IconSurface,
    ): Boolean =
        config.rulesEnabled && policySource(surface) == PolicyIconSource.DesktopTheme

    /**
     * True only when the host is actually going to compose a contact avatar for this notification:
     * the package was picked *and* the notification carries something to build an avatar from.
     *
     * The package check on its own is too coarse. ColorOS composes "personal avatar + app-icon
     * badge" only when there is an avatar; a picked package's ongoing / foreground-service
     * notification has none, so yielding there hands the row to the host, which falls back to the
     * bare `smallIcon` — the themed icon is lost and nothing is preserved. Those notifications must
     * be replaced as usual, which is why [hostAvatarAvailable] is part of the decision.
     *
     * The status bar is not part of this decision — it only shows an avatar for important
     * conversations, which `StatusBarHooks` already guards through `StatusBarIcon.Type.PeopleAvatar`.
     */
    fun shouldYieldToHostAvatar(
        config: IconPolicyConfig,
        packageName: String?,
        hostAvatarAvailable: Boolean,
    ): Boolean = isAvatarPriorityPackage(config, packageName) && hostAvatarAvailable

    /**
     * Whether the user picked this package for the personal avatar. Surfaces that cannot tell
     * whether a given notification has an avatar (AOD reuses a drawable cached by notification key)
     * use this alone and keep the conservative, package-wide behaviour.
     */
    fun isAvatarPriorityPackage(
        config: IconPolicyConfig,
        packageName: String?,
    ): Boolean =
        config.hostAvatarPriorityEnabled &&
            !packageName.isNullOrEmpty() &&
            packageName in config.avatarPriorityPackages

    fun selectRuleReplacement(
        config: IconPolicyConfig,
        surface: IconSurface,
        ruleEnabled: Boolean,
        ruleEnabledAll: Boolean,
        isOplusPush: Boolean,
        originalCompatibility: OriginalIconCompatibility,
    ): RuleReplacement {
        if (!config.rulesEnabled || policySource(surface) != PolicyIconSource.RuleLibrary) {
            return RuleReplacement.None
        }
        if (isOplusPush && !config.handleOplusPush) return RuleReplacement.None

        // These choices are explicit and do not depend on whether the original Icon can be loaded.
        if (ruleEnabled && (ruleEnabledAll || isOplusPush)) {
            return RuleReplacement.Rule
        }

        return when (originalCompatibility) {
            OriginalIconCompatibility.Unchecked,
            OriginalIconCompatibility.Compatible -> RuleReplacement.None

            OriginalIconCompatibility.Incompatible -> when {
                ruleEnabled -> RuleReplacement.Rule
                config.placeholderEnabled -> RuleReplacement.Placeholder
                else -> RuleReplacement.None
            }
        }
    }
}
