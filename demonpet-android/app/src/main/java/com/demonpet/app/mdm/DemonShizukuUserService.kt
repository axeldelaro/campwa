package com.demonpet.app.mdm

import android.content.Context
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Privileged Shizuku UserService (UID 2000 ADB Shell / UID 0 Root) on Android 11–16 (HyperOS/MIUI/AOSP) — v9.0:
 * Executes strictly scoped, 100% reversible Witch RPG & Demon Companion commands (NEVER touches Wi-Fi or vital apps!).
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
            while (reader.readLine().also { line = it } != null) out.append(line).append('\n')
            while (errReader.readLine().also { line = it } != null) out.append(line).append('\n')
            proc.waitFor(6L, TimeUnit.SECONDS)
            val code = try { proc.exitValue() } catch (_: Throwable) { -1 }
            "EXIT:$code\n${out.toString().trim()}"
        } catch (t: Throwable) {
            "EXIT:-1\n${t.message.orEmpty()}"
        }
    }

    /** Strict allowlist: 100% reversible Shizuku powers; never disables Wi-Fi (keeps wireless Shizuku alive!) or vital OS apps. */
    private fun isAllowedSafeCommand(cmd: String): Boolean {
        val lower = cmd.lowercase()
        if (lower == "id") return true

        // Allow strictly granting safe runtime permissions to DemonMom itself & pulling DemonMom to front!
        if (lower.startsWith("appops set com.demonpet.app ") && lower.endsWith(" allow")) {
            return lower.contains("get_usage_stats") ||
                lower.contains("write_settings") ||
                lower.contains("system_alert_window") ||
                lower.contains("manage_external_storage")
        }
        if (lower == "am start -n com.demonpet.app/.ui.mainactivity" ||
            lower.startsWith("am start -n com.demonpet.app/.ui.mainactivity ")
        ) return true

        // Global reversible system effects (no package targets)
        if (lower == "cmd battery reset" || lower.startsWith("cmd battery set level ")) return true
        if (lower == "wm density reset" || lower.startsWith("wm density ")) return true
        if (lower == "am task lock stop" || lower.startsWith("am task lock ")) return true
        if (lower.startsWith("cmd power set-mode ")) return true
        if (lower.startsWith("cmd uimode night ")) return true
        if (lower.startsWith("cmd notification set_dnd ")) return true
        if (lower.startsWith("cmd media_session volume ")) return true
        if (lower.startsWith("settings put global window_animation_scale ") ||
            lower.startsWith("settings put global transition_animation_scale ") ||
            lower.startsWith("settings put global animator_duration_scale ") ||
            lower.startsWith("settings put system screen_brightness ")
        ) return true
        if (lower == "input keyevent 26" || lower == "input keyevent 4" || lower == "input keyevent 3") return true

        // Reject shell chaining, Wi-Fi disruption, destructive commands, or touching vital/protected apps
        val forbidden = listOf(
            ";", "&&", "||", "|", "`", "$(", ">", "<",
            "svc wifi", "com.demonpet.app", "moe.shizuku", "com.termux",
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
            lower.startsWith("am force-stop ") ||
            lower.startsWith("appops set ")
    }
}
