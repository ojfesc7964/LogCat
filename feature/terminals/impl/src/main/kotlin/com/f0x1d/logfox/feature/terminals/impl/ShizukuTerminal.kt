package com.f0x1d.logfox.feature.terminals.impl

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.os.ParcelFileDescriptor.AutoCloseOutputStream
import com.f0x1d.logfox.core.di.IODispatcher
import com.f0x1d.logfox.feature.strings.Strings
import com.f0x1d.logfox.feature.terminals.api.base.Terminal
import com.f0x1d.logfox.feature.terminals.api.base.TerminalType
import com.f0x1d.logfox.feature.terminals.api.model.TerminalProcess
import com.f0x1d.logfox.feature.terminals.api.model.TerminalResult
import com.f0x1d.logfox.feature.terminals.presentation.IUserService
import com.f0x1d.logfox.feature.terminals.presentation.service.UserService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Singleton
internal class ShizukuTerminal @Inject constructor(
    @ApplicationContext private val context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : Terminal {

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST_ID = 8
    }

    override val type = TerminalType.Shizuku
    override val title = R.string.shizuku

    private var userService: IUserService? = null

    // 保存绑定时的 ServiceConnection，unbindUserService 需要它来精确匹配并移除
    // Shizuku 内部的 connection 记录；只传 null 无法匹配到已绑定的连接。
    private var serviceConnection: ServiceConnection? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context, UserService::class.java))
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(BuildConfig.DEBUG)
            .version(
                context.run {
                    packageManager.getPackageInfo(packageName, 0).versionCode
                },
            )
            .tag(context.getString(Strings.app_name))
    }

    override suspend fun isSupported() = suspendCoroutine {
        when {
            !Shizuku.pingBinder() -> it.resume(false)

            Shizuku.isPreV11() -> it.resume(false)

            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> it.resumeWithServiceBinding()

            Shizuku.shouldShowRequestPermissionRationale() -> it.resume(false)

            else -> {
                val listener = object : Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                        if (requestCode != SHIZUKU_PERMISSION_REQUEST_ID) return

                        when (grantResult == PackageManager.PERMISSION_GRANTED) {
                            true -> it.resumeWithServiceBinding()
                            else -> it.resume(false)
                        }

                        Shizuku.removeRequestPermissionResultListener(this)
                    }
                }

                Shizuku.addRequestPermissionResultListener(listener)
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_ID)
            }
        }
    }

    private fun Continuation<Boolean>.resumeWithServiceBinding() {
        // 复用仍然存活的特权进程，避免每次重新绑定时都创建新的 UserService。
        val existingService = userService
        if (existingService != null) {
            if (existingService.asBinder().pingBinder()) {
                resume(true)
                return
            }
            // binder 已失效（特权进程被系统回收），清空本地引用以便重新绑定。
            userService = null
        }

        // 清理残留的旧连接，避免 Shizuku 内部继续保留一条无效的绑定记录，
        // 导致重新绑定时叠加出多个特权进程。
        val oldConnection = serviceConnection
        if (oldConnection != null) {
            serviceConnection = null
            runCatching {
                Shizuku.unbindUserService(userServiceArgs, oldConnection, true)
            }.onFailure { Timber.w(it, "Failed to unbind stale Shizuku user service") }
        }

        var resumed = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(componentName: ComponentName?, binder: IBinder?) {
                if (binder == null || !binder.pingBinder()) {
                    if (resumed.not()) {
                        resume(false)
                        resumed = true
                    }
                    return
                }

                userService = IUserService.Stub.asInterface(binder)

                if (resumed.not()) {
                    resume(true)
                    resumed = true
                }
            }

            override fun onServiceDisconnected(componentName: ComponentName?) {
                userService = null
            }
        }

        serviceConnection = connection
        Shizuku.bindUserService(userServiceArgs, connection)
    }

    override suspend fun executeNow(vararg command: String) = withContext(ioDispatcher) {
        val service = userService
        // binder 已死或尚未建立时直接返回失败结果，避免向失效服务发起调用。
        if (service == null || !service.asBinder().pingBinder()) {
            return@withContext TerminalResult(3)
        }
        service.executeNow(command.joinToString(" "))
    }

    override fun execute(vararg command: String) = userService?.run {
        // binder 已死时清空本地引用，避免后续调用继续命中失效服务。
        if (!asBinder().pingBinder()) {
            userService = null
            return@run null
        }

        val processId = execute(command.joinToString(" "))

        TerminalProcess(
            output = AutoCloseInputStream(processOutput(processId) ?: return@run null),
            error = AutoCloseInputStream(processError(processId) ?: return@run null),
            input = AutoCloseOutputStream(processInput(processId) ?: return@run null),
        ) {
            destroyProcess(processId)
        }
    }

    override suspend fun exit() {
        // 按 MaterialFiles 的方式解绑：传真实的 ServiceConnection 与 remove = true。
        // Shizuku 收到后会对 UserService 调用 destroy()（UserService 内部 exitProcess(0) 退出），
        // 并从内部缓存移除该 UserServiceArgs 记录。第三个参数为 true 是销毁特权进程的关键，
        // 传 null connection 或不传 true 都只解除绑定而不会让特权进程结束。
        val connection = serviceConnection
        serviceConnection = null
        userService = null

        runCatching {
            Shizuku.unbindUserService(userServiceArgs, connection, true)
        }.onFailure { Timber.w(it, "Failed to unbind Shizuku user service") }
    }
}
