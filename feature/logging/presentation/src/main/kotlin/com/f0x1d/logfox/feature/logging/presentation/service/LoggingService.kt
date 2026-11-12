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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
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

    // 通知栏"退出"按钮路径：先清理最近任务列表，再走统一的硬退出。
    // finishAndRemoveTask 会回调 onTaskRemoved，后者直接调用 forceExit，
    // 不会回到这里，因此不存在递归。
    private fun killApp() {
        Timber.d("killing app")

        activityManager.appTasks.forEach {
            it.finishAndRemoveTask()
        }

        forceExit()
    }

    // 硬退出收尾：取消 store、停止前台服务、终止进程。
    // store.cancel 幂等，stopSelf 触发的 onDestroy 再次 cancel 也不会有副作用。
    // exitProcess 确保宿主进程立即死亡，Shizuku/Sui 通过 binder death 回收
    // 以 daemon(false) 绑定的 UserService，避免特权进程残留。
    private fun forceExit() {
        store.cancel()

        ServiceCompat.stopForeground(this@LoggingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()

        exitProcess(0)
    }

    override fun onDestroy() {
        super.onDestroy()
        store.cancel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        // 用户从最近任务划掉应用时彻底退出进程，确保 Shizuku 托管的特权进程
        // （UserService）随宿主进程死亡被回收，不残留。此时 task 已被系统移除，
        // 无需再 finishAndRemoveTask，直接 forceExit 即可。
        Timber.d("task removed, force exiting")
        forceExit()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    inner class LocalBinder : Binder() {
        val service: LoggingService get() = this@LoggingService
    }
}
