package com.f0x1d.logfox.feature.terminals.presentation.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.os.ParcelFileDescriptor.AutoCloseOutputStream
import androidx.annotation.Keep
import com.f0x1d.logfox.feature.terminals.api.model.TerminalResult
import com.f0x1d.logfox.feature.terminals.presentation.IUserService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import kotlin.system.exitProcess

class UserService() : IUserService.Stub() {

    private val serviceScopeJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceScopeJob)

    private var latestId = 0L
    private val currentProcesses = HashMap<Long, Process>()

    // Needed for shizuku v13
    @Suppress("UNUSED_PARAMETER")
    @Keep
    constructor(context: Context) : this()

    override fun destroy() {
        // 直接结束进程。Shizuku 在 unbindUserService(remove = true) 时同步调用本方法，
        // 若在此阻塞（例如等待阻塞于 pipe 写操作的协程），unbind 无法返回，
        // Shizuku 内部的绑定记录不会被移除，重新绑定时会再创建新进程，
        // 造成特权进程残留与累积。协程会随 exitProcess 一并终止，无需等待。
        currentProcesses.values.forEach { process ->
            process.tryDestroy()
        }

        exitProcess(0)
    }

    override fun exit() {
        destroy()
    }

    override fun executeNow(command: String?) = runBlocking(Dispatchers.IO) {
        val process = Runtime.getRuntime().exec(command)

        val output = async {
            process.inputStream.readBytes().decodeToString()
        }
        val error = async {
            process.errorStream.readBytes().decodeToString()
        }
        val exitCode = process.waitFor()

        TerminalResult(exitCode, output.await(), error.await())
    }

    override fun execute(command: String?): Long {
        val processId = latestId++

        val process = Runtime.getRuntime().exec(command)
        currentProcesses[processId] = process

        return processId
    }

    override fun processOutput(processId: Long): ParcelFileDescriptor? {
        val process = currentProcesses[processId] ?: return null

        return pipeFrom(process.inputStream)
    }

    override fun processError(processId: Long): ParcelFileDescriptor? {
        val process = currentProcesses[processId] ?: return null

        return pipeFrom(process.errorStream)
    }

    private fun pipeFrom(inputStream: InputStream): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()

        serviceScope.launch {
            AutoCloseOutputStream(pipe[1]).use {
                try {
                    inputStream.copyTo(it)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return pipe[0]
    }

    override fun processInput(processId: Long): ParcelFileDescriptor? {
        val process = currentProcesses[processId] ?: return null

        val pipe = ParcelFileDescriptor.createPipe()
        serviceScope.launch {
            AutoCloseInputStream(pipe[0]).use {
                try {
                    it.copyTo(process.outputStream)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return pipe[1]
    }

    override fun destroyProcess(processId: Long) {
        currentProcesses.remove(processId)?.tryDestroy()
    }

    // Cancellable implementation
    private suspend fun InputStream.copyTo(
        out: OutputStream,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
    ): Long = withContext(Dispatchers.IO) {
        var bytesCopied: Long = 0
        val buffer = ByteArray(bufferSize)
        var bytes = read(buffer)

        while (bytes >= 0 && isActive) {
            out.write(buffer, 0, bytes)
            bytesCopied += bytes
            bytes = read(buffer)
        }

        return@withContext bytesCopied
    }

    private fun Process.tryDestroy() = runCatching {
        destroy()
    }
}
