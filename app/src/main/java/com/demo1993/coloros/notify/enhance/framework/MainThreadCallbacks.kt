package com.demo1993.coloros.notify.enhance.framework

import android.os.Handler
import android.os.Looper
import com.demo1993.coloros.notify.enhance.diagnostics.AppDiagnostics
import com.demo1993.coloros.notify.enhance.diagnostics.DiagnosticEvent
import com.demo1993.coloros.notify.enhance.diagnostics.DiagnosticLevel

/** Delivers app-side callbacks with one asynchronous, main-thread failure boundary. */
internal object MainThreadCallbacks {
    private val handler = Handler(Looper.getMainLooper())

    fun dispatch(scope: String, callback: () -> Unit) {
        val posted = try {
            handler.post {
                try {
                    callback()
                } catch (exception: Exception) {
                    report(scope, "invoke", exception)
                }
            }
        } catch (exception: Exception) {
            report(scope, "schedule", exception)
            return
        }
        if (!posted) {
            report(
                scope = scope,
                phase = "schedule",
                cause = IllegalStateException("Main thread rejected the callback"),
            )
        }
    }

    private fun report(scope: String, phase: String, cause: Exception) {
        AppDiagnostics.logger.report(
            level = DiagnosticLevel.Error,
            event = DiagnosticEvent.AppCallbackFailed,
            message = "App callback failed",
            cause = cause,
            attributes = mapOf(
                "scope" to scope,
                "phase" to phase,
            ),
        )
    }
}
