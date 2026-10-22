package com.demo1993.coloros.notify.enhance

import android.app.Application
import com.demo1993.coloros.notify.enhance.framework.RemoteConfigCoordinator
import com.demo1993.coloros.notify.enhance.framework.XposedServiceBridge
import com.demo1993.coloros.notify.enhance.rules.RuleStore
import com.demo1993.coloros.notify.enhance.ui.theme.applyPredictiveBackCallbackEnabled
import com.demo1993.coloros.notify.enhance.ui.theme.readThemeConfig
import io.github.libxposed.service.XposedService

class ColorOSNotifyEnhanceApplication : Application() {

    private val configPublisher = object : XposedServiceBridge.Listener {
        override fun onServiceChanged(service: XposedService?) {
            service?.let { RemoteConfigCoordinator.publish(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        applyPredictiveBackCallbackEnabled(
            context = this,
            enabled = readThemeConfig(this).predictiveBackToHomeEnabled,
        )
        RuleStore.initialize(this)
        XposedServiceBridge.initialize()
        XposedServiceBridge.addListener(configPublisher)
    }
}
