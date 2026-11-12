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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import javax.inject.Inject
import kotlin.system.exitProcess

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

    // 通知栏"退出"按钮路径：显式释放特权进程、清理最近任务列表并硬退出进程。
    private fun killApp() {
        Timber.d("killing app")

        releaseTerminalBlocking()
        store.cancel()

        activityManager.appTasks.forEach {
            it.finishAndRemoveTask()
        }

        ServiceCompat.stopForeground(this@LoggingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()

        exitProcess(0)
    }

    // 显式释放当前终端持有的特权资源。
    // Shizuku 后端下会走 Shizuku.unbindUserService，触发 daemon(false) 的 UserService 被 Shizuku 回收，
    // 不依赖宿主进程死亡带来的 binder death，符合 MaterialFiles 的显式解绑思路。
    // 使用 runBlocking 保证在 Service 销毁前完成解绑（unbind 本身是同步调用，不会长时间阻塞）。
    private fun releaseTerminalBlocking() {
        val terminal = runCatching { store.state.value.currentTerminal }.getOrNull() ?: return

        runCatching {
            runBlocking { exitTerminalUseCase(terminal) }
        }.onFailure { Timber.w(it, "Failed to release terminal") }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        // 用户从最近任务划掉应用：显式解绑 Shizuku 特权进程并停止日志服务。
        // 不硬退进程，应用进程交给系统按常规策略回收；下次打开应用时
        // MainViewModel 的 StartLoggingServiceIfNeeded 会重新启动 Service。
        Timber.d("task removed, releasing terminal")
        releaseTerminalBlocking()
        store.cancel()
        ServiceCompat.stopForeground(this@LoggingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()

        // 兜底：部分定制系统划掉后台时可能不回调 onTaskRemoved，直接杀进程或走 onDestroy。
        // 此时同样显式释放特权进程，避免 UserService 残留在 Shizuku/Sui 中。
        releaseTerminalBlocking()
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
