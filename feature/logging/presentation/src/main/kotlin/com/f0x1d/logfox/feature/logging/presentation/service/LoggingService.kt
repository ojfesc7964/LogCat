package com.f0x1d.logfox.feature.logging.presentation.service

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

    // 通知栏"退出"按钮路径：释放特权进程、清理最近任务列表并停止日志服务。
    // 不硬退应用进程，特权进程由 ShizukuTerminal.exit() 显式销毁，应用进程交给系统回收。
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

    // 显式释放当前终端持有的特权资源。
    // ShizukuTerminal.exit() 内部是同步 AIDL 调用，没有真正的挂起点，
    // 因此这里的 launch 会在当前线程同步执行完再返回，不阻塞也不依赖 runBlocking。
    // 使用独立 scope 而不是 lifecycleScope，保证 onDestroy 阶段也能执行。
    private fun releaseTerminal() {
        val terminal = runCatching { store.state.value.currentTerminal }.getOrNull() ?: return

        releaseScope.launch {
            runCatching { exitTerminalUseCase(terminal) }
                .onFailure { Timber.w(it, "Failed to release terminal") }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        // 用户从最近任务划掉应用：显式释放 Shizuku 特权进程并停止日志服务。
        // 不硬退进程，应用进程交给系统按常规策略回收；下次打开应用时
        // MainViewModel 的 StartLoggingServiceIfNeeded 会重新启动 Service。
        Timber.d("task removed, releasing terminal")
        releaseTerminal()
        store.cancel()
        ServiceCompat.stopForeground(this@LoggingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()

        // 兜底：部分定制系统划掉后台时可能不回调 onTaskRemoved，直接杀进程或走 onDestroy。
        // 此时同样显式释放特权进程，避免 UserService 残留在 Shizuku/Sui 中。
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
