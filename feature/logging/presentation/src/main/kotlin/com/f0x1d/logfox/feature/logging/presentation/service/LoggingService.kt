package com.f0x1d.logfox.feature.logging.presentation.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.f0x1d.logfox.core.context.EXIT_APP_INTENT_ID
import com.f0x1d.logfox.core.context.OPEN_APP_INTENT_ID
import com.f0x1d.logfox.core.context.activityManager
import com.f0x1d.logfox.core.context.makeServicePendingIntent
import com.f0x1d.logfox.core.context.toast
import com.f0x1d.logfox.core.tea.Store
import com.f0x1d.logfox.core.ui.icons.Icons
import com.f0x1d.logfox.feature.notifications.api.LOGGING_STATUS_CHANNEL_ID
import com.f0x1d.logfox.feature.strings.Strings
import com.f0x1d.logfox.feature.terminals.api.domain.ExitTerminalUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class LoggingService : LifecycleService() {

    companion object {
        const val ACTION_RESTART_LOGGING = "logfox.RESTART_LOGGING"
        const val ACTION_CLEAR_LOGS = "logfox.CLEAR_LOGS"
        const val ACTION_KILL_SERVICE = "logfox.KILL_SERVICE"
    }

    private val binder = LocalBinder()

    @Inject
    lateinit var mainActivityPendingIntentProvider: MainActivityPendingIntentProvider

    @Inject
    internal lateinit var storeFactory: LoggingServiceStoreFactory

    @Inject
    lateinit var exitTerminalUseCase: ExitTerminalUseCase

    // 独立于 Service 生命周期的 scope，保证 onDestroy 阶段仍能执行终端释放。
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // onTaskRemoved、onDestroy、以及外部强制清理都可能触发终端释放，
    // 用标志位保证只真正释放一次，避免重复 unbind。
    private val isTerminalReleased = AtomicBoolean(false)

    private lateinit var store: Store<LoggingServiceState, LoggingServiceCommand, LoggingServiceSideEffect>

    override fun onCreate() {
        super.onCreate()

        startForeground(-1, notification())

        store = storeFactory.create(lifecycleScope)

        // Observe side effects for UI handling
        lifecycleScope.launch {
            store.sideEffects.collect { sideEffect ->
                handleSideEffect(sideEffect)
            }
        }

        // Start logging
        store.send(LoggingServiceCommand.StartLogging)

        // onTaskRemoved 在 Android 8.0+ 及许多 OEM ROM 上可能不被调用，
        // 导致从最近任务划掉后 UserService 仍然存活。
        // 在 onDestroy 之外，额外监听进程的 “仅保留缓存” 状态作为兜底触发点。
        registerTrimMemoryCleanup()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        Timber.d("got command ${intent?.action}")
        when (intent?.action) {
            ACTION_RESTART_LOGGING -> store.send(LoggingServiceCommand.RestartLogging)
            ACTION_CLEAR_LOGS -> clearLogs()
            ACTION_KILL_SERVICE -> store.send(LoggingServiceCommand.KillService)
        }

        return START_NOT_STICKY
    }

    private fun clearLogs() {
        store.send(LoggingServiceCommand.ClearLogs)
    }

    private fun handleSideEffect(sideEffect: LoggingServiceSideEffect) {
        when (sideEffect) {
            is LoggingServiceSideEffect.ShowToast -> {
                toast(sideEffect.message)
            }

            is LoggingServiceSideEffect.PerformKillService -> {
                killApp()
            }

            // Business logic side effects - handled by EffectHandler, ignored here
            else -> Unit
        }
    }

    private fun notification() = NotificationCompat.Builder(this, LOGGING_STATUS_CHANNEL_ID)
        .setContentTitle(getString(Strings.logging))
        .setSmallIcon(Icons.ic_logfox)
        .setOngoing(true)
        .setContentIntent(mainActivityPendingIntentProvider.provide(OPEN_APP_INTENT_ID))
        .addAction(
            Icons.ic_clear,
            getString(Strings.exit),
            makeServicePendingIntent<LoggingService>(EXIT_APP_INTENT_ID) {
                action = ACTION_KILL_SERVICE
            },
        )
        .build()

    // 通知栏“退出”按钮路径：释放特权进程、清理最近任务列表并停止日志服务。
    private fun killApp() {
        Timber.d("killing app")

        releaseTerminal()
        store.cancel()

        activityManager.appTasks.forEach {
            it.finishAndRemoveTask()
        }

        ServiceCompat.stopForeground(this@LoggingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // 在应用任务被移除、Service 销毁、或进程即将变为缓存态时释放 Shizuku 特权进程。
    // 使用 AtomicBoolean 保证只执行一次。
    private fun releaseTerminal() {
        if (!isTerminalReleased.compareAndSet(false, true)) {
            return
        }

        val terminal = runCatching { store.state.value.currentTerminal }.getOrNull() ?: return

        releaseScope.launch {
            runCatching { exitTerminalUseCase(terminal) }
                .onFailure { Timber.w(it, "Failed to release terminal") }
        }
    }

    // 注册 ActivityManager.OnTrimMemoryListener，当进程进入 TRIM_MEMORY_UI_HIDDEN
    // （所有 UI 已不可见，即用户离开了应用）时执行清理。
    // 这一信号在用户按 Home 键或从最近任务划掉后通常都会触发，比 onTaskRemoved 更可靠。
    @Suppress("DEPRECATION")
    private fun registerTrimMemoryCleanup() {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.addOnTrimMemoryListener { level ->
            if (level == ActivityManager.TRIM_MEMORY_UI_HIDDEN) {
                Timber.d("trim memory UI hidden, releasing terminal")
                releaseTerminal()
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        Timber.d("task removed, releasing terminal")
        releaseTerminal()
        store.cancel()
        ServiceCompat.stopForeground(this@LoggingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()

        Timber.d("service destroyed, releasing terminal")
        releaseTerminal()
        store.cancel()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    inner class LocalBinder : Binder() {
        val service: LoggingService get() = this@LoggingService
    }
}
