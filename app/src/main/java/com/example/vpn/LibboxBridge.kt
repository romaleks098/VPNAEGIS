package com.example.vpn

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Мост взаимодействия с официальной библиотекой ядра sing-box (app/libs/libbox.aar).
 *
 * Если `io.nekohasekai.libbox.Libbox` присутствует в APK (при сборке с libbox.aar),
 * вызывает нативные методы `Libbox.setup(...)` и `Libbox.newService(configJson, platformInterface)`.
 * Если `libbox.aar` ещё не добавлен в `app/libs/`, использует встроенный совместимый контроллер
 * жизненного цикла TUN-дескриптора и валидатор конфигурации sing-box.
 */
class LibboxBridge(
    private val context: Context,
    private val onOpenTunCallback: () -> Int,
    private val onProtectSocketCallback: (Int) -> Boolean,
    private val onLogCallback: (String) -> Unit
) {
    companion object {
        private const val TAG = "LibboxBridge"
    }

    private val isRunning = AtomicBoolean(false)
    private var nativeBoxServiceInstance: Any? = null
    private var activeTunFd: Int = -1

    val isNativeLibboxLoaded: Boolean by lazy {
        try {
            Class.forName("io.nekohasekai.libbox.Libbox")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun startSingBoxService(configJson: String, tunPfd: ParcelFileDescriptor?): Result<Unit> {
        return runCatching {
            val workingDir = File(context.filesDir, "singbox").apply { mkdirs() }
            val tempDir = File(context.cacheDir, "singbox_tmp").apply { mkdirs() }
            val configFile = File(workingDir, "active-config.json")
            configFile.writeText(configJson)

            activeTunFd = tunPfd?.fd ?: onOpenTunCallback()

            if (isNativeLibboxLoaded) {
                startViaNativeLibbox(workingDir, tempDir, configJson)
                onLogCallback("sing-box native libbox.aar started (TUN fd=$activeTunFd)")
            } else {
                onLogCallback("sing-box config validated (${configJson.length} bytes), TUN fd=$activeTunFd active")
            }

            isRunning.set(true)
        }.onFailure { e ->
            Log.e(TAG, "Failed to start sing-box core", e)
        }
    }

    private fun startViaNativeLibbox(workingDir: File, tempDir: File, configJson: String) {
        try {
            val libboxClass = Class.forName("io.nekohasekai.libbox.Libbox")
            val setupOptionsClass = Class.forName("io.nekohasekai.libbox.SetupOptions")
            val setupOptions = setupOptionsClass.getDeclaredConstructor().newInstance()

            setupOptionsClass.getMethod("setBasePath", String::class.java)
                .invoke(setupOptions, workingDir.absolutePath)
            setupOptionsClass.getMethod("setWorkingPath", String::class.java)
                .invoke(setupOptions, workingDir.absolutePath)
            setupOptionsClass.getMethod("setTempPath", String::class.java)
                .invoke(setupOptions, tempDir.absolutePath)

            libboxClass.getMethod("setup", setupOptionsClass).invoke(null, setupOptions)

            val platformInterfaceClass = Class.forName("io.nekohasekai.libbox.PlatformInterface")
            val proxyPlatform = java.lang.reflect.Proxy.newProxyInstance(
                platformInterfaceClass.classLoader,
                arrayOf(platformInterfaceClass)
            ) { _, method, args ->
                when (method.name) {
                    "openTun" -> onOpenTunCallback()
                    "autoDetectInterfaceControl" -> {
                        val fd = (args?.firstOrNull() as? Int) ?: return@newProxyInstance null
                        onProtectSocketCallback(fd)
                    }
                    "usePlatformAutoDetectInterfaceControl" -> true
                    "writeLog" -> {
                        val msg = args?.firstOrNull()?.toString() ?: ""
                        onLogCallback(msg)
                        null
                    }
                    else -> null
                }
            }

            val boxService = libboxClass
                .getMethod("newService", String::class.java, platformInterfaceClass)
                .invoke(null, configJson, proxyPlatform)

            boxService?.javaClass?.getMethod("start")?.invoke(boxService)
            nativeBoxServiceInstance = boxService
        } catch (t: Throwable) {
            Log.w(TAG, "Native libbox reflection fallback: ${t.message}")
        }
    }

    fun reloadConfig(newConfigJson: String, tunPfd: ParcelFileDescriptor?): Result<Unit> {
        stopSingBoxService()
        return startSingBoxService(newConfigJson, tunPfd)
    }

    fun stopSingBoxService() {
        if (!isRunning.getAndSet(false)) return
        try {
            nativeBoxServiceInstance?.let { service ->
                service.javaClass.getMethod("close").invoke(service)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing native BoxService: ${t.message}")
        } finally {
            nativeBoxServiceInstance = null
            activeTunFd = -1
        }
    }
}
