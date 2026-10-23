package com.c17.notify.replace.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.c17.notify.replace.ui.share.LocalDarkTheme

@Composable
fun C17NotifyReplaceTheme(
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
        C17NotifyReplaceAppTheme(themeConfig = themeConfig, content = content)
    }
}
