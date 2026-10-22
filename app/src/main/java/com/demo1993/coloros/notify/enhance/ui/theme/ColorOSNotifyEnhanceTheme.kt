package com.demo1993.coloros.notify.enhance.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.demo1993.coloros.notify.enhance.ui.share.LocalDarkTheme

@Composable
fun ColorOSNotifyEnhanceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val themeConfig = ThemeConfig(
        colorMode = when {
            darkTheme -> 2
            else -> 1
        },
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        ColorOSNotifyEnhanceAppTheme(themeConfig = themeConfig, content = content)
    }
}
