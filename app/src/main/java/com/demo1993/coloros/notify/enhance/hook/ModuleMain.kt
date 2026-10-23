package com.demo1993.coloros.notify.enhance.hook

import com.demo1993.coloros.notify.enhance.core.SystemPackages
import com.demo1993.coloros.notify.enhance.diagnostics.DiagnosticEvent
import com.demo1993.coloros.notify.enhance.diagnostics.DiagnosticLevel
import com.demo1993.coloros.notify.enhance.diagnostics.Diagnostics
import com.demo1993.coloros.notify.enhance.diagnostics.OccurrencePolicy
import com.demo1993.coloros.notify.enhance.hook.games.GameAssistantRuntime
import com.demo1993.coloros.notify.enhance.hook.systemserver.SystemServerHooks
import com.demo1993.coloros.notify.enhance.hook.systemui.SystemUiRuntime
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class ModuleMain : XposedModule() {

    private lateinit var target: ProcessTarget
    private lateinit var processName: String
    private lateinit var diagnostics: Diagnostics
    private lateinit var hooks: HookRegistrar

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        diagnostics = Diagnostics(XposedDiagnosticSink(this, processName))
        hooks = HookRegistrar(this, diagnostics, processName)
        target = resolveProcessTarget(param.isSystemServer, processName)

        diagnostics.report(
            level = DiagnosticLevel.Info,
            event = DiagnosticEvent.ModuleLoaded,
            message = "模块入口已路由",
            attributes = mapOf(
                "api" to apiVersion,
                "framework" to frameworkName,
                "target" to target.diagnosticName,
            ),
            occurrence = OccurrencePolicy.Once(processName),
        )
        if (target == ProcessTarget.Ignore) {
            diagnostics.debug(
                event = DiagnosticEvent.ProcessIgnored,
                message = "当前进程不承载任何模块功能",
                attributes = emptyMap(),
                occurrence = OccurrencePolicy.Once(processName),
            )
            detach()
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        if (target != ProcessTarget.SystemServer) return
        try {
            SystemServerHooks(
                hooks = hooks,
                diagnostics = diagnostics,
            ).install(param.classLoader)
        } catch (exception: Exception) {
            diagnostics.installationFailure(
                scope = "system_server:initialize",
                message = "system_server 功能初始化失败",
                cause = exception,
            )
        }
        detachAfterInitialization()
    }

    override fun onPackageReady(param: PackageReadyParam) {
        when (target) {
            ProcessTarget.SystemUiMain -> installSystemUi(param)
            ProcessTarget.GameAssistant -> installGameAssistant(param)
            else -> Unit
        }
    }

    private fun installSystemUi(param: PackageReadyParam) {
        if (param.packageName != SystemPackages.SYSTEM_UI || !param.isFirstPackage) return

        try {
            SystemUiRuntime(
                xposed = this,
                hooks = hooks,
                diagnostics = diagnostics,
                processName = processName,
            ).install(param.classLoader)
        } catch (exception: Exception) {
            diagnostics.installationFailure(
                scope = "systemui:initialize",
                message = "SystemUI 运行时初始化失败",
                cause = exception,
            )
        }
        detachAfterInitialization()
    }

    private fun installGameAssistant(param: PackageReadyParam) {
        if (param.packageName != SystemPackages.GAME_ASSISTANT || !param.isFirstPackage) return

        try {
            GameAssistantRuntime(
                hooks = hooks,
                diagnostics = diagnostics,
                processName = processName,
            ).install(param.classLoader)
        } catch (exception: Exception) {
            diagnostics.installationFailure(
                scope = "games:initialize",
                message = "游戏助手主题图标初始化失败",
                cause = exception,
            )
        }
        detachAfterInitialization()
    }

    /**
     * Installed hooks, preference callbacks and delayed UI work have an explicit process lifetime.
     * A partial generation swap cannot safely retire them, so hot reload remains unsupported.
     */
    override fun onHotReloading(param: HotReloadingParam): Boolean = false

    private fun detachAfterInitialization() {
        diagnostics.debug(
            event = DiagnosticEvent.LifecycleDetached,
            message = "初始化完成，停止接收后续生命周期回调",
            attributes = emptyMap(),
            occurrence = OccurrencePolicy.Once("initialized:$processName"),
        )
        detach()
    }

}

internal enum class ProcessTarget(val diagnosticName: String) {
    SystemServer("system_server"),
    SystemUiMain("systemui_main"),
    GameAssistant("games_main"),
    Ignore("ignore"),
}

internal fun resolveProcessTarget(
    isSystemServer: Boolean,
    processName: String,
): ProcessTarget = when {
    isSystemServer -> ProcessTarget.SystemServer
    processName == SystemPackages.SYSTEM_UI -> ProcessTarget.SystemUiMain
    processName == SystemPackages.GAME_ASSISTANT -> ProcessTarget.GameAssistant
    else -> ProcessTarget.Ignore
}
