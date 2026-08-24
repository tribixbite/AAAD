package com.legs.appsforaa.utils

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Fallback installer using the platform [PackageInstaller], for devices without Shizuku.
 *
 * **This cannot make an app visible to Android Auto on its own.** An ordinary app may only
 * attribute an install to itself, so the resulting package reports
 * `installer=sksa.aa.customapps`, not the Play Store. Android Auto will then list the app only if
 * the user has enabled *Unknown sources* in Android Auto's own developer settings. Callers must
 * say so rather than implying the install succeeded in the sense the user cares about. See
 * `docs/aa-visibility.md`.
 *
 * Catalog installs use [install], which hands off to the confirmation dialog without awaiting it.
 * Conversion uses [installAndAwait] so its queue does not open overlapping confirmation dialogs.
 */
object SystemInstaller {

    private const val TAG = "SystemInstaller"
    private const val ACTION_INSTALL_STATUS = "com.legs.appsforaa.INSTALL_STATUS"
    private const val BUFFER_BYTES = 64 * 1024

    sealed interface Result {
        /** The system dialog has been shown; the outcome will arrive as a package broadcast. */
        data object HandedOffToSystem : Result
        data class Failure(val message: String) : Result
    }

    /** Terminal result for conversion installs whose queue must wait for the system dialog. */
    sealed interface AwaitedResult {
        data object Installed : AwaitedResult
        data class Failure(val message: String) : AwaitedResult
    }

    /**
     * Registers the status receiver, stages the APK into a session, and commits it.
     *
     * The receiver is registered per-call and unregisters itself once the session reaches a
     * terminal state, so nothing leaks if the user abandons the dialog and returns later.
     */
    suspend fun install(context: Context, apk: File): Result = withContext(Dispatchers.IO) {
        if (!apk.isFile || apk.length() == 0L) {
            return@withContext Result.Failure("APK missing or empty: ${apk.name}")
        }

        val appContext = context.applicationContext
        runCatching {
            val installer = appContext.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            val sessionId = installer.createSession(params)

            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { input ->
                        input.copyTo(output, BUFFER_BYTES)
                    }
                    session.fsync(output)
                }
                registerStatusReceiver(appContext)
                session.commit(statusIntentSender(appContext, sessionId))
            }
            Logger.d(TAG, "Committed system install session $sessionId")
            Result.HandedOffToSystem
        }.getOrElse { error ->
            Logger.e(TAG, "System install failed", error)
            Result.Failure(error.message ?: "System installer failed")
        }
    }

    /**
     * Stages one base APK or a complete split-APK set, shows Android's confirmation UI, and waits
     * for its terminal result. Waiting is essential for a conversion queue: starting the next
     * package while the current confirmation dialog is open would stack installer activities and
     * make it unclear which app the user is approving.
     */
    suspend fun installAndAwait(
        context: Context,
        apks: List<File>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        onAwaitingConfirmation: () -> Unit = {},
    ): AwaitedResult = withContext(Dispatchers.IO) {
        val invalid = apks.firstOrNull { !it.isFile || it.length() == 0L }
        if (apks.isEmpty() || invalid != null) {
            return@withContext AwaitedResult.Failure(
                invalid?.let { "APK missing or empty: ${it.name}" } ?: "No APK files supplied"
            )
        }

        val appContext = context.applicationContext
        val installer = appContext.packageManager.packageInstaller
        var sessionId: Int? = null
        try {
            val createdSessionId = installer.createSession(
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            )
            sessionId = createdSessionId
            installer.openSession(createdSessionId).use { session ->
                onProgress(0, apks.size)
                apks.forEachIndexed { index, apk ->
                    currentCoroutineContext().ensureActive()
                    val name = if (index == 0) "base.apk" else "split_${index}.apk"
                    session.openWrite(name, 0, apk.length()).use { output ->
                        apk.inputStream().use { input ->
                            input.copyTo(output, BUFFER_BYTES)
                        }
                        session.fsync(output)
                    }
                    onProgress(index + 1, apks.size)
                }
            }
            currentCoroutineContext().ensureActive()
            awaitCommit(
                appContext,
                installer,
                createdSessionId,
                onAwaitingConfirmation,
            )
        } catch (cancelled: CancellationException) {
            sessionId?.let { runCatching { installer.abandonSession(it) } }
            throw cancelled
        } catch (error: Throwable) {
            sessionId?.let { runCatching { installer.abandonSession(it) } }
            Logger.e(TAG, "Awaited system install failed", error)
            AwaitedResult.Failure(error.message ?: "System installer failed")
        }
    }

    private suspend fun awaitCommit(
        context: Context,
        installer: PackageInstaller,
        sessionId: Int,
        onAwaitingConfirmation: () -> Unit,
    ): AwaitedResult = suspendCancellableCoroutine { continuation ->
        val action = "$ACTION_INSTALL_STATUS.${sessionId}"
        val finished = AtomicBoolean(false)
        lateinit var statusReceiver: BroadcastReceiver

        fun cleanup() {
            if (!finished.compareAndSet(false, true)) return
            runCatching { context.unregisterReceiver(statusReceiver) }
                .onFailure { Logger.w(TAG, "Awaited status receiver already unregistered", it) }
        }

        fun finish(result: AwaitedResult) {
            cleanup()
            if (continuation.isActive) continuation.resume(result)
        }

        statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val status = intent?.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE,
                ) ?: return
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        onAwaitingConfirmation()
                        val confirm = intent.getConfirmationIntent()
                        if (confirm == null) {
                            finish(AwaitedResult.Failure("Android did not provide an install dialog"))
                            return
                        }
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(confirm) }
                            .onFailure {
                                finish(
                                    AwaitedResult.Failure(
                                        it.message ?: "Could not show the install dialog"
                                    )
                                )
                            }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        Logger.i(TAG, "Awaited system install succeeded")
                        finish(AwaitedResult.Installed)
                    }
                    else -> {
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                            ?: "Installation was declined or failed"
                        Logger.w(TAG, "Awaited system install failed ($status): $message")
                        finish(AwaitedResult.Failure(message))
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            statusReceiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        continuation.invokeOnCancellation {
            cleanup()
            runCatching { installer.abandonSession(sessionId) }
        }

        runCatching {
            installer.openSession(sessionId).use { session ->
                session.commit(statusIntentSender(context, sessionId, action))
            }
            Logger.d(TAG, "Committed awaited system install session $sessionId")
        }.onFailure { error ->
            finish(AwaitedResult.Failure(error.message ?: "Could not commit install session"))
        }
    }

    private fun statusIntentSender(
        context: Context,
        sessionId: Int,
        action: String = ACTION_INSTALL_STATUS,
    ): android.content.IntentSender {
        val intent = Intent(action).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return pending.intentSender
    }

    private var receiver: BroadcastReceiver? = null

    @Synchronized
    private fun registerStatusReceiver(context: Context) {
        if (receiver != null) return
        val statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val status = intent?.getIntExtra(
                    PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
                ) ?: return
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // The system needs the user to confirm; surface its dialog.
                        val confirm = intent.getConfirmationIntent()
                        if (confirm == null) {
                            Logger.e(TAG, "Pending user action without a confirmation intent")
                            return
                        }
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(confirm) }
                            .onFailure { Logger.e(TAG, "Could not show install dialog", it) }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        Logger.i(TAG, "System install succeeded")
                        unregisterStatusReceiver(context)
                    }
                    else -> {
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        Logger.w(TAG, "System install failed ($status): $message")
                        unregisterStatusReceiver(context)
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            statusReceiver,
            IntentFilter(ACTION_INSTALL_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiver = statusReceiver
    }

    @Synchronized
    private fun unregisterStatusReceiver(context: Context) {
        val current = receiver ?: return
        runCatching { context.unregisterReceiver(current) }
            .onFailure { Logger.w(TAG, "Status receiver already unregistered", it) }
        receiver = null
    }

    @Suppress("DEPRECATION") // typed getParcelableExtra needs API 33; minSdk here is 24
    private fun Intent.getConfirmationIntent(): Intent? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_INTENT)
        }
}
