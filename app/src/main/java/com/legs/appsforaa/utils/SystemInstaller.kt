package com.legs.appsforaa.utils

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
 * The install is handed off to the system confirmation dialog and **not** awaited: the final
 * outcome arrives through the package-change broadcast that the catalog screen already listens
 * for, which also covers the user taking a while to tap through.
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

    private fun statusIntentSender(context: Context, sessionId: Int): android.content.IntentSender {
        val intent = Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName)
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
