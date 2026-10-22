package com.demo1993.coloros.notify.enhance.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.demo1993.coloros.notify.enhance.R
import com.demo1993.coloros.notify.enhance.ui.home.HomeScreenState
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun ModuleSettingsSection(
    state: HomeScreenState,
    onRulesEnabledChange: (Boolean) -> Unit,
    onPanelIconReplacementEnabledChange: (Boolean) -> Unit,
    onLockScreenCapsuleIconReplacementEnabledChange: (Boolean) -> Unit,
    onOplusPushSpecialHandlingEnabledChange: (Boolean) -> Unit,
    onPlaceholderIconEnabledChange: (Boolean) -> Unit,
    onHostAvatarPriorityEnabledChange: (Boolean) -> Unit,
) {
    val canEditConfig = state.canEditConfig
    ToggleComponent(
        title = stringResource(R.string.label_host_avatar_priority),
        checked = state.config.hostAvatarPriorityEnabled,
        enabled = canEditConfig,
        onCheckedChange = onHostAvatarPriorityEnabledChange,
    )
    ToggleComponent(
        title = stringResource(R.string.label_icon_enhancement_enabled),
        checked = state.config.rulesEnabled,
        enabled = canEditConfig,
        onCheckedChange = onRulesEnabledChange,
    )
    ToggleComponent(
        title = stringResource(R.string.label_panel_icon_replacement_enabled),
        checked = state.config.panelIconReplacementEnabled,
        enabled = canEditConfig,
        onCheckedChange = onPanelIconReplacementEnabledChange,
    )
    ToggleComponent(
        title = stringResource(R.string.label_lock_screen_capsule_icon_replacement_enabled),
        checked = state.config.lockScreenCapsuleIconReplacementEnabled,
        enabled = canEditConfig,
        onCheckedChange = onLockScreenCapsuleIconReplacementEnabledChange,
    )
    // The two rule-library options belong to the status bar, whose source is always the rule
    // library, so they stay available instead of following a global source switch.
    ToggleComponent(
        title = stringResource(R.string.label_oplus_push_special_handling_enabled),
        checked = state.config.oplusPushSpecialHandlingEnabled,
        enabled = canEditConfig && state.config.rulesEnabled,
        onCheckedChange = onOplusPushSpecialHandlingEnabledChange,
    )
    ToggleComponent(
        title = stringResource(R.string.label_placeholder_icon_enabled),
        checked = state.config.placeholderIconEnabled,
        enabled = canEditConfig && state.config.rulesEnabled,
        onCheckedChange = onPlaceholderIconEnabledChange,
    )
}

@Composable
private fun ToggleComponent(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        title = title,
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
    )
}
