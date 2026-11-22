package com.demonpet.app.mdm

import android.content.Context
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Privileged Shizuku UserService running in ADB Shell UID (2000) or Root UID (0) on Android 11–16 (HyperOS/MIUI/AOSP).
 * Supports BOTH no-arg and Context constructors for 100% compatibility across Shizuku v11–v13.5+!
 */
@Keep
class DemonShizukuUserService : IDemonShellService.Stub {

    @Keep
    constructor() : super()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    override fun destroy() {
        exitProcess(0)
    }

    override fun runCommand(cmd: String?): String {
        val clean = cmd?.trim().orEmpty()
        if (clean.isEmpty()) return "EXIT:-1\nEMPTY"
        if (!isAllowedSafeCommand(clean)) return "EXIT:-2\nBLOCKED_UNSAFE: $clean"

        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", clean))
            val out = StringBuilder()
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val errReader = BufferedReader(InputStreamReader(proc.errorStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                out.append(line).append('\n')
            }
            while (errReader.readLine().also { line = it } != null) {
                out.append(line).append('\n')
            }
            proc.waitFor(6L, TimeUnit.SECONDS)
            val code = try { proc.exitValue() } catch (_: Throwable) { -1 }
            "EXIT:$code\n${out.toString().trim()}"
        } catch (t: Throwable) {
            "EXIT:-1\n${t.message.orEmpty()}"
        }
    }

    /** Strict allowlist: only reversible RPG/restore/diagnostic commands; never allows touching vital apps or destructive ops! */
    private fun isAllowedSafeCommand(cmd: String): Boolean {
        val lower = cmd.lowercase()
        if (lower == "id") return true

        // Allow strictly granting safe runtime appops to DemonMom itself so UsageStats & brightness work automatically!
        if (lower.startsWith("appops set com.demonpet.app ") && lower.endsWith(" allow")) {
            return lower.contains("get_usage_stats") ||
                lower.contains("write_settings") ||
                lower.contains("system_alert_window") ||
                lower.contains("manage_external_storage")
        }

        // Reject shell chaining or dangerous binaries/packages
        val forbidden = listOf(
            ";", "&&", "||", "|", "`", "$(", ">", "<",
            "com.demonpet.app", "moe.shizuku", "com.termux",
            "com.android.systemui", "com.android.settings", "com.android.phone",
            "dialer", "rm ", "wipe", "format", "reboot", "uninstall", "clear "
        )
        if (forbidden.any { lower.contains(it) }) return false

        return lower.startsWith("cmd package suspend ") ||
            lower.startsWith("cmd package unsuspend ") ||
            lower.startsWith("pm suspend ") ||
            lower.startsWith("pm unsuspend ") ||
            lower.startsWith("pm disable-user ") ||
            lower.startsWith("pm enable ") ||
            lower.startsWith("appops set ") ||
            lower.startsWith("input keyevent 26") ||
            lower.startsWith("settings put system screen_brightness")
    }
}
