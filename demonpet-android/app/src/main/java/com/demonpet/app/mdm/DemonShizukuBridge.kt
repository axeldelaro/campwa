package com.demonpet.app.mdm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.demonpet.app.BuildConfig
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bulletproof Shizuku API 13.1.5 Bridge for DemonMom RPG (Android 11–16 / Xiaomi HyperOS):
 * - Works with `rikka.shizuku.ShizukuProvider` in AndroidManifest.xml!
 * - Registers sticky BinderReceived & BinderDead listeners so detection is instant and reactive.
 * - Auto-grants safe UsageStats/WriteSettings appops to DemonMom via Shizuku so usage-based app greying works out of the box!
 */
object DemonShizukuBridge {

    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    const val REQ_CODE_SHIZUKU = 4102

    private val initialized = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var binderAlive = false

    @Volatile
    private var shellService: IDemonShellService? = null

    @Volatile
    private var pendingLatch: CountDownLatch? = null

    private val lock = Any()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        binderAlive = true
        notifyListeners()
        if (hasShizukuPermission()) {
            Thread {
                ensureBound()
                autoGrantSafeAppOps()
                notifyListeners()
            }.start()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        binderAlive = false
        synchronized(lock) { shellService = null }
        notifyListeners()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(lock) {
                shellService = if (service != null && service.pingBinder()) {
                    IDemonShellService.Stub.asInterface(service)
                } else null
                pendingLatch?.countDown()
                pendingLatch = null
            }
            notifyListeners()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) {
                shellService = null
            }
            notifyListeners()
        }
    }

    fun init(context: Context) {
        if (initialized.compareAndSet(false, true)) {
            try {
                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
            } catch (_: Throwable) {
            }
        }
        try {
            if (!Shizuku.pingBinder()) {
                ShizukuProvider.requestBinderForNonProviderProcess(context.applicationContext)
            } else {
                binderAlive = true
            }
        } catch (_: Throwable) {
        }
    }

    fun addStateListener(cb: () -> Unit) {
        if (!listeners.contains(cb)) listeners.add(cb)
    }

    fun removeStateListener(cb: () -> Unit) {
        listeners.remove(cb)
    }

    private fun notifyListeners() {
        for (l in listeners) {
            try { l() } catch (_: Throwable) {}
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
            val alive = Shizuku.pingBinder()
            binderAlive = alive
            alive
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

    fun getDiagnostics(context: Context): String {
        val inst = isShizukuInstalled(context)
        val running = isShizukuRunning()
        val perm = hasShizukuPermission()
        val uid = if (running) try { Shizuku.getUid() } catch (_: Throwable) { -1 } else -1
        val ver = if (running) try { Shizuku.getVersion() } catch (_: Throwable) { -1 } else -1
        val bound = synchronized(lock) { shellService?.asBinder()?.pingBinder() == true }
        val mode = when (uid) {
            0 -> "ROOT (uid 0)"
            2000 -> "ADB Shell (uid 2000)"
            else -> "uid $uid"
        }
        return when {
            !inst -> "Shizuku non installé"
            !running -> "Shizuku installé mais service non démarré (ouvre Shizuku → Démarrer)"
            !perm -> "Shizuku détecté (v$ver · $mode) — en attente d'autorisation !"
            bound -> "⚡ Shizuku CONNECTÉ (v$ver · $mode · Service lié ✅)"
            else -> "⚡ Shizuku autorisé (v$ver · $mode — liaison auto au premier sort)"
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
        val latchToWait: CountDownLatch?
        synchronized(lock) {
            val existing = shellService
            if (existing != null && existing.asBinder().pingBinder()) return existing
            val latch = CountDownLatch(1)
            pendingLatch = latch
            latchToWait = latch
            try {
                Shizuku.bindUserService(buildArgs(), connection)
            } catch (_: Throwable) {
                pendingLatch = null
                return null
            }
        }
        try {
            latchToWait?.await(3_500L, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        }
        return synchronized(lock) { shellService }
    }

    /** Automatically grants all 4 special Android permissions via Shizuku so zero manual setup is needed! */
    fun autoGrantSafeAppOps() {
        if (!hasShizukuPermission()) return
        execShellSync("appops set com.demonpet.app GET_USAGE_STATS allow")
        execShellSync("appops set com.demonpet.app WRITE_SETTINGS allow")
        execShellSync("appops set com.demonpet.app SYSTEM_ALERT_WINDOW allow")
        execShellSync("appops set com.demonpet.app MANAGE_EXTERNAL_STORAGE allow")
    }

    data class ShellResult(val ok: Boolean, val exitCode: Int, val output: String)

    /** Executes a safe privileged shell command via Shizuku UserService (verifies exitCode == 0 AND no SecurityException/Failure). */
    fun execShellSync(cmd: String): ShellResult {
        val svc = ensureBound() ?: return ShellResult(false, -1, "Shizuku UserService non lié")
        return try {
            val raw = svc.runCommand(cmd).orEmpty()
            val firstLine = raw.substringBefore('\n')
            val code = firstLine.removePrefix("EXIT:").trim().toIntOrNull() ?: -1
            val body = raw.substringAfter('\n', "")
            val low = body.lowercase()
            val hasErr = low.contains("exception") || low.contains("failure") || low.contains("error:") || low.contains("not allowed") || low.contains("blocked_unsafe")
            ShellResult(code == 0 && !hasErr, code, body)
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
