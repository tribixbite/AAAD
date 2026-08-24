package com.legs.appsforaa.utils

import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import kotlin.coroutines.resume

/**
 * Installs an APK through Shizuku without an Android confirmation dialog.
 *
 * Shizuku runs `pm` as the shell uid. The command retains `-i com.android.vending` for legacy
 * Android Auto builds that read the installer label, but current builds also expose the shell as
 * the initiating package. This path therefore provides automation, not genuine Play Store trust.
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

    /** Shizuku normally delivers its binder within a few hundred ms of process start. */
    private const val BINDER_WAIT_MILLIS = 3000L

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

    /**
     * Cheap, synchronous, safe to call from the UI thread.
     *
     * [Availability.NotRunning] is **not** precise, and cannot be: without a binder there is
     * nothing to ask. A server that is stopped and a server that is running but has not authorised
     * this app look identical from here — Shizuku only hands the binder to apps the user has
     * granted, and that grant is its own prompt rather than the Android permission. Observed on a
     * real device: `shizuku_server` running as shell, `pingBinder()` still false. So the copy for
     * this state names both possibilities instead of sending people to restart a running service.
     */
    fun availability(): Availability = when {
        !isBinderAlive() -> if (isShizukuAppInstalled()) Availability.NotRunning
        else Availability.NotInstalled
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
            Availability.PermissionRequired
        else -> Availability.Ready
    }

    /**
     * Returns true when Shizuku is usable, prompting for permission and **waiting for the
     * answer** if needed.
     *
     * The waiting is the point. Requesting permission and continuing immediately guarantees the
     * first install of every session silently takes the interactive fallback path before the user
     * has answered.
     */
    suspend fun ensureReady(): Boolean {
        // Shizuku hands its binder to the app ASYNCHRONOUSLY, through ShizukuProvider after the
        // process starts. pingBinder() is false until that lands, so checking it synchronously
        // reports "NotRunning" on a perfectly healthy Shizuku — and silently downgrades the
        // install to the interactive path. Wait for it before deciding anything.
        if (!isBinderAlive() && shizukuAppInstalled) {
            awaitBinder(BINDER_WAIT_MILLIS)
        }

        val state = availability()
        // Logged unconditionally: "why did it not use Shizuku" is the single most common
        // question when an install turns out not to be visible in Android Auto.
        Logger.i(TAG, "Shizuku availability: $state (binder=${isBinderAlive()}, appInstalled=$shizukuAppInstalled)")
        when (state) {
            Availability.Ready -> return true
            Availability.NotInstalled, Availability.NotRunning -> return false
            Availability.PermissionRequired -> Unit
        }

        // shouldShowRequestPermissionRationale() means the user denied and asked not to be asked
        // again; prompting would be a no-op that hangs this coroutine forever.
        if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
            Logger.i(TAG, "Shizuku permission was permanently denied")
            return false
        }

        return suspendCancellableCoroutine { continuation ->
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != PERMISSION_REQUEST_CODE) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (continuation.isActive) {
                        continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            continuation.invokeOnCancellation {
                Shizuku.removeRequestPermissionResultListener(listener)
            }
            runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
                .onFailure { error ->
                    Shizuku.removeRequestPermissionResultListener(listener)
                    Logger.w(TAG, "Shizuku permission request failed", error)
                    if (continuation.isActive) continuation.resume(false)
                }
        }
    }

    private fun isBinderAlive(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /**
     * Waits up to [timeoutMillis] for Shizuku's binder to arrive.
     *
     * Uses the *sticky* listener so a binder that already arrived fires immediately rather than
     * waiting for the next delivery, which would never come.
     */
    private suspend fun awaitBinder(timeoutMillis: Long) {
        withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : Shizuku.OnBinderReceivedListener {
                    override fun onBinderReceived() {
                        Shizuku.removeBinderReceivedListener(this)
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                Shizuku.addBinderReceivedListenerSticky(listener)
                continuation.invokeOnCancellation {
                    Shizuku.removeBinderReceivedListener(listener)
                }
            }
        } ?: Logger.d(TAG, "Shizuku binder did not arrive within ${timeoutMillis}ms")
    }

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

        try {
            val written = writeSession(sessionId, apk)
            if (!written) {
                abandonSession(sessionId)
                return@withContext Result.Failure("Could not stream the APK into the session")
            }
            currentCoroutineContext().ensureActive()

            val commit = runShellCommand("pm install-commit $sessionId")
            if (commit.exitCode != 0 || !commit.output.contains("Success")) {
                abandonSession(sessionId)
                return@withContext Result.Failure(
                    commit.errorOrOutput().ifBlank { "Install failed" }
                )
            }

            Logger.i(TAG, "Installed ${apk.name} via session $sessionId")
            Result.Success(null)
        } catch (cancelled: CancellationException) {
            abandonSession(sessionId)
            throw cancelled
        }
    }

    /**
     * Re-stages an already-installed app's own APKs through an attributed session — "conversion".
     *
     * The APKs are read from `/data/app/...` by the shell uid directly rather than streamed,
     * because a split app has several of them and they are already readable there. The signature
     * is unchanged, so this is an update over the top: **app data is preserved**.
     *
     * @param apkPaths base APK first, then splits. All must go into one session; committing a
     *   session with only the base of a split app fails or yields a broken install.
     */
    suspend fun convertInstalled(
        packageName: String,
        apkPaths: List<String>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        if (availability() != Availability.Ready) {
            return@withContext Result.Failure("Shizuku is not ready")
        }
        if (apkPaths.isEmpty()) {
            return@withContext Result.Failure("No APK paths for $packageName")
        }

        val sessionId = createSession()
            ?: return@withContext Result.Failure("Could not create an install session")

        try {
            onProgress(0, apkPaths.size)
            apkPaths.forEachIndexed { index, path ->
                currentCoroutineContext().ensureActive()
                // Names only have to be unique within the session; base first by convention.
                val name = if (index == 0) "base.apk" else "split_$index.apk"
                // No -S here: when install-write is given a path it sizes the file itself. Only
                // the stdin form needs an explicit byte count.
                val result = runShellCommand("pm install-write $sessionId $name '$path'")
                if (result.exitCode != 0) {
                    abandonSession(sessionId)
                    return@withContext Result.Failure(
                        "Could not stage ${path.substringAfterLast('/')}: " +
                            result.errorOrOutput()
                    )
                }
                onProgress(index + 1, apkPaths.size)
            }
            currentCoroutineContext().ensureActive()

            val commit = runShellCommand("pm install-commit $sessionId")
            if (commit.exitCode != 0 || !commit.output.contains("Success")) {
                abandonSession(sessionId)
                return@withContext Result.Failure(
                    commit.errorOrOutput().ifBlank { "Conversion failed" }
                )
            }

            Logger.i(TAG, "Re-staged $packageName (${apkPaths.size} APK(s)) unattended")
            Result.Success(packageName)
        } catch (cancelled: CancellationException) {
            abandonSession(sessionId)
            throw cancelled
        }
    }

    private fun createSession(): Int? {
        val command = buildString {
            append("pm install-create -r")
            // Retain the legacy installer-of-record label. Current Android Auto can separately
            // observe that shell initiated the session; this does not confer Play trust.
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
