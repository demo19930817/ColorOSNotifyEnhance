package com.demo1993.coloros.notify.enhance.hook.systemui

import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import androidx.core.os.BundleCompat
import com.demo1993.coloros.notify.enhance.diagnostics.Diagnostics
import com.demo1993.coloros.notify.enhance.hook.HookContract
import com.demo1993.coloros.notify.enhance.hook.runtimeFailure

internal fun StatusBarNotification.originalSmallIcon(
    diagnostics: Diagnostics,
    revision: Long,
): Icon? {
    val preserved = try {
        BundleCompat.getParcelable(
            notification.extras,
            HookContract.EXTRA_ORIGINAL_SMALL_ICON,
            Icon::class.java,
        )
    } catch (exception: Exception) {
        diagnostics.runtimeFailure(
            scope = "notification:original_small_icon",
            message = "读取修正前的 smallIcon 失败，使用当前通知图标",
            cause = exception,
            revision = revision,
        )
        null
    }
    return preserved ?: notification.smallIcon
}
