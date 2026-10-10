package com.fankes.coloros.notify.hook.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIconPolicyTest {

    @Test
    fun `each surface owns one fixed icon source`() {
        assertEquals(
            PolicyIconSource.RuleLibrary,
            NotificationIconPolicy.policySource(IconSurface.StatusBar),
        )
        assertEquals(
            PolicyIconSource.DesktopTheme,
            NotificationIconPolicy.policySource(IconSurface.Panel),
        )
    }

    @Test
    fun `Oplus push is kept only for the status bar rule library when handling is disabled`() {
        assertTrue(
            NotificationIconPolicy.shouldKeepHostDefault(
                config(handleOplusPush = false),
                IconSurface.StatusBar,
                isOplusPush = true,
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldKeepHostDefault(
                config(handleOplusPush = true),
                IconSurface.StatusBar,
                isOplusPush = true,
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldKeepHostDefault(
                config(handleOplusPush = false),
                IconSurface.Panel,
                isOplusPush = true,
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldKeepHostDefault(
                config(handleOplusPush = false),
                IconSurface.StatusBar,
                isOplusPush = false,
            )
        )
    }

    @Test
    fun `Oplus push preservation is independent from rule enablement`() {
        assertTrue(
            NotificationIconPolicy.shouldKeepHostDefault(
                config(rulesEnabled = false, handleOplusPush = false),
                IconSurface.StatusBar,
                isOplusPush = true,
            )
        )
    }

    @Test
    fun `ColorOS app icon predicate is preserved only by the status bar rule library`() {
        assertTrue(
            NotificationIconPolicy.shouldKeepHostAppIconBehavior(
                config(handleOplusPush = false),
                IconSurface.StatusBar,
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldKeepHostAppIconBehavior(
                config(handleOplusPush = true),
                IconSurface.StatusBar,
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldKeepHostAppIconBehavior(
                config(handleOplusPush = false),
                IconSurface.Panel,
            )
        )
    }

    @Test
    fun `panel gate rejects the disabled switch and media notifications`() {
        assertFalse(
            NotificationIconPolicy.shouldProcessPanel(
                config = config(panelEnabled = false),
                isMediaNotification = false,
                isOplusPush = false,
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldProcessPanel(
                config = config(),
                isMediaNotification = true,
                isOplusPush = false,
            )
        )
        // The notification centre follows the desktop theme, so system pushes are themed like any
        // other row instead of keeping the pushed icon.
        assertTrue(
            NotificationIconPolicy.shouldProcessPanel(
                config = config(handleOplusPush = false),
                isMediaNotification = false,
                isOplusPush = true,
            )
        )
        assertTrue(
            NotificationIconPolicy.shouldProcessPanel(
                config = config(rulesEnabled = false),
                isMediaNotification = false,
                isOplusPush = false,
            )
        )
    }

    @Test
    fun `desktop theme is resolved for the panel only while rules are enabled`() {
        assertTrue(
            NotificationIconPolicy.shouldResolveTheme(
                config(),
                IconSurface.Panel,
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldResolveTheme(
                config(rulesEnabled = false),
                IconSurface.Panel,
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldResolveTheme(
                config(),
                IconSurface.StatusBar,
            )
        )
    }

    @Test
    fun `rule selection covers enabled enabled-all and mask compatibility`() {
        val cases = listOf(
            RuleCase(
                ruleEnabled = true,
                ruleEnabledAll = false,
                maskCompatible = false,
                expected = RuleReplacement.Rule,
            ),
            RuleCase(
                ruleEnabled = true,
                ruleEnabledAll = true,
                maskCompatible = true,
                expected = RuleReplacement.Rule,
            ),
            RuleCase(
                ruleEnabled = true,
                ruleEnabledAll = false,
                maskCompatible = true,
                expected = RuleReplacement.None,
            ),
            RuleCase(
                ruleEnabled = false,
                ruleEnabledAll = true,
                maskCompatible = false,
                expected = RuleReplacement.None,
            ),
            RuleCase(
                ruleEnabled = false,
                ruleEnabledAll = false,
                maskCompatible = false,
                expected = RuleReplacement.None,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.toString(),
                case.expected,
                NotificationIconPolicy.selectRuleReplacement(
                    config = config(),
                    surface = IconSurface.StatusBar,
                    ruleEnabled = case.ruleEnabled,
                    ruleEnabledAll = case.ruleEnabledAll,
                    isOplusPush = false,
                    originalCompatibility = case.maskCompatible.toCompatibility(),
                ),
            )
        }
    }

    @Test
    fun `forced rules are selected before the original icon is loaded`() {
        assertEquals(
            RuleReplacement.Rule,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(),
                surface = IconSurface.StatusBar,
                ruleEnabled = true,
                ruleEnabledAll = true,
                isOplusPush = false,
                originalCompatibility = OriginalIconCompatibility.Unchecked,
            )
        )
        assertEquals(
            RuleReplacement.Rule,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(handleOplusPush = true),
                surface = IconSurface.StatusBar,
                ruleEnabled = true,
                ruleEnabledAll = false,
                isOplusPush = true,
                originalCompatibility = OriginalIconCompatibility.Unchecked,
            )
        )
        assertEquals(
            RuleReplacement.None,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(placeholderEnabled = true),
                surface = IconSurface.StatusBar,
                ruleEnabled = true,
                ruleEnabledAll = false,
                isOplusPush = false,
                originalCompatibility = OriginalIconCompatibility.Unchecked,
            )
        )
    }

    @Test
    fun `handled Oplus push prefers enabled rule over a compatible injected bitmap`() {
        assertEquals(
            RuleReplacement.Rule,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(handleOplusPush = true),
                surface = IconSurface.StatusBar,
                ruleEnabled = true,
                ruleEnabledAll = false,
                isOplusPush = true,
                originalCompatibility = OriginalIconCompatibility.Compatible,
            )
        )
        assertEquals(
            RuleReplacement.None,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(handleOplusPush = true),
                surface = IconSurface.StatusBar,
                ruleEnabled = false,
                ruleEnabledAll = false,
                isOplusPush = true,
                originalCompatibility = OriginalIconCompatibility.Compatible,
            )
        )
        assertEquals(
            RuleReplacement.None,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(handleOplusPush = false),
                surface = IconSurface.StatusBar,
                ruleEnabled = true,
                ruleEnabledAll = false,
                isOplusPush = true,
                originalCompatibility = OriginalIconCompatibility.Compatible,
            )
        )
    }

    @Test
    fun `the panel never falls back to rule icons`() {
        assertEquals(
            RuleReplacement.None,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(),
                surface = IconSurface.Panel,
                ruleEnabled = true,
                ruleEnabledAll = true,
                isOplusPush = false,
                originalCompatibility = OriginalIconCompatibility.Unchecked,
            )
        )
        assertEquals(
            RuleReplacement.None,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(placeholderEnabled = true),
                surface = IconSurface.Panel,
                ruleEnabled = false,
                ruleEnabledAll = false,
                isOplusPush = false,
                originalCompatibility = OriginalIconCompatibility.Incompatible,
            )
        )
    }

    @Test
    fun `placeholder is used only for incompatible originals on the status bar`() {
        assertEquals(
            RuleReplacement.Placeholder,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(placeholderEnabled = true),
                surface = IconSurface.StatusBar,
                ruleEnabled = false,
                ruleEnabledAll = false,
                isOplusPush = false,
                originalCompatibility = OriginalIconCompatibility.Incompatible,
            )
        )
        assertEquals(
            RuleReplacement.None,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(placeholderEnabled = true),
                surface = IconSurface.StatusBar,
                ruleEnabled = false,
                ruleEnabledAll = false,
                isOplusPush = false,
                originalCompatibility = OriginalIconCompatibility.Compatible,
            )
        )
        assertEquals(
            RuleReplacement.None,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(rulesEnabled = false, placeholderEnabled = true),
                surface = IconSurface.StatusBar,
                ruleEnabled = true,
                ruleEnabledAll = true,
                isOplusPush = false,
                originalCompatibility = OriginalIconCompatibility.Incompatible,
            )
        )
    }

    @Test
    fun `disabled rule may fall back to placeholder for an incompatible original`() {
        assertEquals(
            RuleReplacement.Placeholder,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(placeholderEnabled = true),
                surface = IconSurface.StatusBar,
                ruleEnabled = false,
                ruleEnabledAll = true,
                isOplusPush = false,
                originalCompatibility = OriginalIconCompatibility.Incompatible,
            )
        )
    }

    @Test
    fun `host avatar passthrough needs the master switch and a picked package`() {
        val picked = setOf("com.tencent.mm", "com.tencent.mobileqq")
        assertTrue(
            NotificationIconPolicy.shouldYieldToHostAvatar(
                config = config(avatarPriorityPackages = picked),
                packageName = "com.tencent.mm",
            )
        )
        assertTrue(
            NotificationIconPolicy.shouldYieldToHostAvatar(
                config = config(avatarPriorityPackages = picked),
                packageName = "com.tencent.mobileqq",
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldYieldToHostAvatar(
                config = config(hostAvatarPriorityEnabled = false, avatarPriorityPackages = picked),
                packageName = "com.tencent.mm",
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldYieldToHostAvatar(
                config = config(avatarPriorityPackages = picked),
                packageName = "com.eg.android.AlipayGphone",
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldYieldToHostAvatar(
                config = config(),
                packageName = "com.tencent.mm",
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldYieldToHostAvatar(
                config = config(avatarPriorityPackages = picked),
                packageName = null,
            )
        )
    }

    private fun config(
        rulesEnabled: Boolean = true,
        panelEnabled: Boolean = true,
        handleOplusPush: Boolean = true,
        placeholderEnabled: Boolean = false,
        hostAvatarPriorityEnabled: Boolean = true,
        avatarPriorityPackages: Set<String> = emptySet(),
    ) = IconPolicyConfig(
        rulesEnabled = rulesEnabled,
        panelEnabled = panelEnabled,
        handleOplusPush = handleOplusPush,
        placeholderEnabled = placeholderEnabled,
        hostAvatarPriorityEnabled = hostAvatarPriorityEnabled,
        avatarPriorityPackages = avatarPriorityPackages,
    )

    private data class RuleCase(
        val ruleEnabled: Boolean,
        val ruleEnabledAll: Boolean,
        val maskCompatible: Boolean,
        val expected: RuleReplacement,
    )

    private fun Boolean.toCompatibility(): OriginalIconCompatibility =
        if (this) {
            OriginalIconCompatibility.Compatible
        } else {
            OriginalIconCompatibility.Incompatible
        }
}
