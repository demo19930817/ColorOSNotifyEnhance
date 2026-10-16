package com.fankes.coloros.notify.hook.icon

internal enum class PolicyIconSource {
    RuleLibrary,
    DesktopTheme,
}

internal data class IconPolicyConfig(
    val rulesEnabled: Boolean,
    val source: PolicyIconSource,
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

    fun shouldKeepHostDefault(
        config: IconPolicyConfig,
        isOplusPush: Boolean,
    ): Boolean =
        shouldKeepHostAppIconBehavior(config) && isOplusPush

    fun shouldKeepHostAppIconBehavior(config: IconPolicyConfig): Boolean =
        config.source == PolicyIconSource.RuleLibrary && !config.handleOplusPush

    fun shouldProcessPanel(
        config: IconPolicyConfig,
        isMediaNotification: Boolean,
        isOplusPush: Boolean,
    ): Boolean =
        config.panelEnabled &&
            !isMediaNotification &&
            !shouldKeepHostDefault(config, isOplusPush)

    fun shouldResolveTheme(config: IconPolicyConfig): Boolean =
        config.rulesEnabled && config.source == PolicyIconSource.DesktopTheme

    /**
     * True only when the host is actually going to compose a contact avatar for this notification:
     * the package was picked *and* the notification carries something to build an avatar from.
     *
     * The package check on its own is too coarse. ColorOS composes "personal avatar + app-icon
     * badge" only when there is an avatar; a picked package's ongoing / foreground-service
     * notification has none, so yielding there hands the row to the host, which falls back to the
     * bare `smallIcon` — the themed icon is lost and nothing is preserved. Those notifications must
     * be replaced as usual, which is why [hostAvatarAvailable] is part of the decision.
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
        ruleEnabled: Boolean,
        ruleEnabledAll: Boolean,
        isOplusPush: Boolean,
        originalCompatibility: OriginalIconCompatibility,
    ): RuleReplacement {
        if (!config.rulesEnabled || config.source != PolicyIconSource.RuleLibrary) {
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
