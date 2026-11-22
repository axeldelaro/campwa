package com.demonpet.app.mdm

import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Privileged Shizuku UserService running in ADB Shell UID (2000) on Android 11–16 (no root, no factory reset!).
 * Executes strictly scoped, reversible app-suspension/curse/restore shell commands for DemonMom RPG.
 */
@Keep
class DemonShizukuUserService : IDemonShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun runCommand(cmd: String?): String {
        val clean = cmd?.trim().orEmpty()
        if (clean.isEmpty()) return "EXIT:-1\nEMPTY"
        if (!isAllowedSafeCommand(clean)) return "EXIT:-2\nBLOCKED_UNSAFE"

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

    /** Strict allowlist: only reversible RPG/restore commands; never allows touching vital apps or destructive rm/wipe! */
    private fun isAllowedSafeCommand(cmd: String): Boolean {
        val lower = cmd.lowercase()
        val forbidden = listOf(
            "com.demonpet.app", "moe.shizuku", "com.termux",
            "com.android.systemui", "com.android.settings", "com.android.phone",
            "dialer", "rm ", "wipe", "format", "reboot", "uninstall", "clear "
        )
        if (forbidden.any { lower.contains(it) }) return false
        return lower.startsWith("pm suspend ") ||
            lower.startsWith("pm unsuspend ") ||
            lower.startsWith("pm disable-user ") ||
            lower.startsWith("pm enable ") ||
            lower.startsWith("appops set ") ||
            lower.startsWith("input keyevent 26") ||
            lower.startsWith("settings put system screen_brightness")
    }
}
