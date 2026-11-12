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
        if (userService != null) {
            resume(true)
            return
        }

        var resumed = false
        object : ServiceConnection {
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
        }.also {
            Shizuku.bindUserService(userServiceArgs, it)
        }
    }

    override suspend fun executeNow(vararg command: String) = withContext(ioDispatcher) {
        userService?.executeNow(command.joinToString(" ")) ?: TerminalResult(3)
    }

    override fun execute(vararg command: String) = userService?.run {
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
        // 显式命令 Shizuku 特权进程退出，再解绑并移除 UserServiceArgs 缓存。
        // 仅解绑不会让特权进程结束：daemon=false 时只有宿主进程死亡才会跟着退出，
        // 宿主进程仍在时特权进程会残留。因此必须调用 UserService.exit()，
        // 让特权进程自己 exitProcess(0)，与 MaterialFiles 的显式销毁思路一致。
        runCatching {
            userService?.exit()
        }.onFailure { Timber.w(it, "Failed to exit Shizuku user service") }

        userService = null

        // 第三个参数传 true：解除绑定并移除 UserServiceArgs 缓存，
        // 避免后续重新绑定复用已退出的特权进程。
        runCatching {
            Shizuku.unbindUserService(userServiceArgs, null, true)
        }.onFailure { Timber.w(it, "Failed to unbind Shizuku user service") }
    }
}
