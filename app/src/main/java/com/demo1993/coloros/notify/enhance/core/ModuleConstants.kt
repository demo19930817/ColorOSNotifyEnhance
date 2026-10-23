package com.demo1993.coloros.notify.enhance.core

object SystemPackages {
    const val SYSTEM_SCOPE = "system"
    const val SYSTEM_UI = "com.android.systemui"

    /** OPPO game assistant: draws its own dock, "弹幕" and message box over the running game. */
    const val GAME_ASSISTANT = "com.oplus.games"
}

object ModuleInfo {
    const val LOG_TAG = "ColorOSNotifyEnhance"

    /**
     * Self-use release line. The in-app update check follows this repository so it never offers the
     * upstream build, which would drop the ColorOS 17 adaptations and the personal-avatar feature.
     * Upstream attribution lives in the open-source list on the about page.
     */
    const val PROJECT_URL = "https://github.com/demo19930817/ColorOSNotifyEnhance"
    const val RELEASES_PAGE = "$PROJECT_URL/releases"
    const val RELEASES_API = "https://api.github.com/repos/demo19930817/ColorOSNotifyEnhance/releases/latest"
    const val ANIP_REPO = "BetterAndroid/android-notification-icon-project"
    const val ANIP_BRANCH = "main"
    const val ANIP_PROJECT_URL = "https://github.com/$ANIP_REPO"

    /**
     * Rule catalog URLs are not fixed here anymore: "更新规则" resolves them from the source picked in
     * the sync sheet (GitHub proxy, direct, or jsDelivr), see `rules/RuleSourceConfig.kt`.
     */
}
