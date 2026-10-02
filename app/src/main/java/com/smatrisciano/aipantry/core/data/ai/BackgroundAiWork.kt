package com.smatrisciano.aipantry.core.data.ai

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether AI work nobody is waiting for (recipes written ahead of time) may run.
 * On-device models are slow and share one CPU: that work gives way while the
 * camera is open, so recognition has the CPU to itself, and while the app is out
 * of sight.
 */
class BackgroundAiWork(private val appScope: CoroutineScope) {

    private val pauses = MutableStateFlow(0)
    private val appVisible = MutableStateFlow(true)

    val isAllowed: Flow<Boolean> =
        combine(pauses, appVisible) { pauses, visible -> pauses == 0 && visible }.distinctUntilChanged()

    /** Holds background work back until the returned function is called. */
    fun pause(): () -> Unit {
        pauses.update { it + 1 }
        val resumed = AtomicBoolean(false)
        return { if (resumed.compareAndSet(false, true)) pauses.update { it - 1 } }
    }

    /** Follows the app in and out of sight: called once, at startup. */
    fun watchVisibility(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            private var hiding: Job? = null

            override fun onActivityStarted(activity: Activity) {
                started++
                hiding?.cancel()
                appVisible.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                started--
                if (started > 0) return
                // A rotation stops the activity and starts its replacement right away:
                // the grace keeps that from interrupting the work
                hiding = appScope.launch {
                    delay(HIDDEN_GRACE_MILLIS)
                    appVisible.value = false
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private companion object {
        const val HIDDEN_GRACE_MILLIS = 2_000L
    }
}
