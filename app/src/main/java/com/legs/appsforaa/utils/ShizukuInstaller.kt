package com.legs.appsforaa.utils

import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Installs an APK through Shizuku so Android Auto will list it.
 *
 * The mechanism, and why it is the only one that works, is documented in
 * `docs/aa-visibility.md`. In short: Android Auto surfaces a third-party app only when the app
 * declares AA support **and** the install is attributed to the Play Store. Shizuku runs `pm` as
 * the shell uid, which is allowed to declare `-i com.android.vending` when it creates the session.
 *
 * Two properties of that mechanism shape this class:
 *
 * - **Attribution can only be declared at session creation, never changed afterwards.** A
 *   `pm set-installer` repair path is impossible — it fails with
 *   `SecurityException: Caller does not have same cert as new installer package`, verified on
 *   Android 16. So an app installed without attribution must be uninstalled and reinstalled.
 * - **The APK is streamed over stdin**, never passed as a path. The download lives in app-private
 *   cache, which the shell uid cannot read; streaming sidesteps the problem entirely and avoids
 *   staging a world-readable copy.
 */
object ShizukuInstaller {

    private const val TAG = "ShizukuInstaller"
    private const val PLAY_STORE_PACKAGE = "com.android.vending"
    private const val PLAY_STORE_URI = "https://play.google.com/store"

    /** Shizuku's own permission, requested at runtime like any dangerous permission. */
    const val PERMISSION_REQUEST_CODE = 8721

    sealed interface Availability {
        data object Ready : Availability
        data object NotInstalled : Availability
        data object NotRunning : Availability
        data object PermissionRequired : Availability
    }

    sealed interface Result {
        data class Success(val packageName: String?) : Result
        data class Failure(val message: String) : Result
    }

    /** Cheap, synchronous, safe to call from the UI thread. */
    fun availability(): Availability = when {
        !isBinderAlive() -> if (isShizukuAppInstalled()) Availability.NotRunning
        else Availability.NotInstalled
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
            Availability.PermissionRequired
        else -> Availability.Ready
    }

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure { Logger.w(TAG, "Shizuku permission request failed", it) }
    }

    private fun isBinderAlive(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /**
     * Shizuku's own app is a separate package. [Shizuku.pingBinder] cannot distinguish
     * "not installed" from "installed but not started", and that difference is the whole
     * difference between two very different pieces of user advice.
     */
    private fun isShizukuAppInstalled(): Boolean = shizukuAppInstalled

    /** Set once by [refreshInstalledState]; avoids a PackageManager hit on every UI pass. */
    @Volatile
    private var shizukuAppInstalled: Boolean = false

    fun refreshInstalledState(packageManager: PackageManager) {
        shizukuAppInstalled = runCatching {
            packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        }.isSuccess
    }

    /**
     * Runs the three-step session install, streaming [apk] over stdin.
     *
     * @return [Result.Success] only when `pm install-commit` reports success. Any earlier failure
     *   abandons the session so it does not linger in the package manager.
     */
    suspend fun install(apk: File): Result = withContext(Dispatchers.IO) {
        if (availability() != Availability.Ready) {
            return@withContext Result.Failure("Shizuku is not ready")
        }
        if (!apk.isFile || apk.length() == 0L) {
            return@withContext Result.Failure("APK missing or empty: ${apk.name}")
        }

        val sessionId = createSession()
            ?: return@withContext Result.Failure("Could not create an install session")

        val written = writeSession(sessionId, apk)
        if (!written) {
            abandonSession(sessionId)
            return@withContext Result.Failure("Could not stream the APK into the session")
        }

        val commit = runShellCommand("pm install-commit $sessionId")
        if (commit.exitCode != 0 || !commit.output.contains("Success")) {
            abandonSession(sessionId)
            return@withContext Result.Failure(commit.errorOrOutput().ifBlank { "Install failed" })
        }

        Logger.i(TAG, "Installed ${apk.name} via session $sessionId")
        Result.Success(null)
    }

    private fun createSession(): Int? {
        val command = buildString {
            append("pm install-create -r")
            // The payload: this is what Android Auto reads. See docs/aa-visibility.md.
            append(" -i $PLAY_STORE_PACKAGE")
            append(" --originating-uri '$PLAY_STORE_URI'")
            append(" --install-reason 0")
            // Android 14+ refuses APKs targeting very old SDKs without this; several of the
            // catalog apps are old enough to trip it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                append(" --bypass-low-target-sdk-block")
            }
        }
        val result = runShellCommand(command)
        if (result.exitCode != 0) {
            Logger.e(TAG, "install-create failed: ${result.errorOrOutput()}")
            return null
        }
        // "Success: created install session [1234567]"
        return Regex("\\[(\\d+)]").find(result.output)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun writeSession(sessionId: Int, apk: File): Boolean {
        val size = apk.length()
        val process = newProcess(arrayOf("sh", "-c", "pm install-write -S $size $sessionId base -"))
            ?: return false
        return runCatching {
            process.outputStream.use { out -> apk.inputStream().use { it.copyTo(out) } }
            val exit = process.waitFor()
            if (exit != 0) Logger.e(TAG, "install-write exit $exit")
            exit == 0
        }.onFailure {
            Logger.e(TAG, "install-write threw", it)
            runCatching { process.destroy() }
        }.getOrDefault(false)
    }

    private fun abandonSession(sessionId: Int) {
        runShellCommand("pm install-abandon $sessionId")
    }

    private data class ShellResult(val exitCode: Int, val output: String, val error: String) {
        fun errorOrOutput(): String = error.ifBlank { output }.trim()
    }

    private fun runShellCommand(command: String): ShellResult {
        val process = newProcess(arrayOf("sh", "-c", command))
            ?: return ShellResult(-1, "", "Shizuku process could not be created")
        return runCatching {
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            ShellResult(process.waitFor(), output, error)
        }.getOrElse { ShellResult(-1, "", it.message.orEmpty()) }
    }

    /**
     * `Shizuku.newProcess` is deliberately hidden from the public API, so it is reached by
     * reflection — the same approach upstream uses. If Shizuku ever publishes it, this collapses
     * to a direct call.
     */
    private fun newProcess(command: Array<String>): Process? = runCatching {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        method.invoke(null, command, null, null) as Process
    }.onFailure {
        Logger.e(TAG, "Shizuku.newProcess unavailable", it)
    }.getOrNull()
}
