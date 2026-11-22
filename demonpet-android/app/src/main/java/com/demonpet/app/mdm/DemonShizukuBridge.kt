package com.demonpet.app.mdm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.demonpet.app.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Clean Shizuku API 13.1.5 Bridge for DemonMom RPG:
 * - Detects Shizuku app & live binder on Android 11–16 (including Xiaomi HyperOS / Android 16!).
 * - Requests permission in 1 tap.
 * - Binds `DemonShizukuUserService` cleanly and executes reversible privileged shell commands synchronously.
 */
object DemonShizukuBridge {

    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    const val REQ_CODE_SHIZUKU = 4102

    @Volatile
    private var shellService: IDemonShellService? = null

    @Volatile
    private var pendingLatch: CountDownLatch? = null

    private val lock = Any()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(lock) {
                shellService = if (service != null && service.pingBinder()) {
                    IDemonShellService.Stub.asInterface(service)
                } else null
                pendingLatch?.countDown()
                pendingLatch = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) {
                shellService = null
            }
        }
    }

    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.isPreV11()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestShizukuPermission(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
            Shizuku.requestPermission(REQ_CODE_SHIZUKU)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun openShizukuApp(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else false
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildArgs(): Shizuku.UserServiceArgs {
        return Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, DemonShizukuUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("demon_shizuku")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    fun ensureBound(): IDemonShellService? {
        if (!hasShizukuPermission()) return null
        synchronized(lock) {
            val existing = shellService
            if (existing != null && existing.asBinder().pingBinder()) return existing
            val latch = CountDownLatch(1)
            pendingLatch = latch
            try {
                Shizuku.bindUserService(buildArgs(), connection)
            } catch (_: Throwable) {
                pendingLatch = null
                return null
            }
        }
        try {
            pendingLatch?.await(2_800L, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        }
        return synchronized(lock) { shellService }
    }

    data class ShellResult(val ok: Boolean, val exitCode: Int, val output: String)

    /** Executes a safe privileged shell command via Shizuku UserService (must be called off-UI or briefly). */
    fun execShellSync(cmd: String): ShellResult {
        val svc = ensureBound() ?: return ShellResult(false, -1, "Shizuku service not bound")
        return try {
            val raw = svc.runCommand(cmd).orEmpty()
            val firstLine = raw.substringBefore('\n')
            val code = firstLine.removePrefix("EXIT:").trim().toIntOrNull() ?: -1
            val body = raw.substringAfter('\n', "")
            ShellResult(code == 0, code, body)
        } catch (t: Throwable) {
            synchronized(lock) { shellService = null }
            ShellResult(false, -1, t.message.orEmpty())
        }
    }

    fun unbindSafely() {
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.unbindUserService(buildArgs(), connection, true)
            }
        } catch (_: Throwable) {
        }
        synchronized(lock) { shellService = null }
    }
}
