package dev.turnzen.app.signin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.turnzen.app.R
import dev.turnzen.app.ui.signin.KeepAwake

/**
 * Keeps the app running while an OGS sign-in waits for the browser.
 *
 * The sign-in's redirect lands on a listener inside this app (see `LoopbackRedirect` in
 * `:core-ogs`). But the browser is in front while the user signs in, and a few seconds after
 * an app goes to the background Android *freezes* its process: the listener then cannot
 * answer, and the browser hangs on the redirect. A running foreground service keeps the
 * process out of the freezer — and nothing else here needs one.
 *
 * A "short service" (Android 14+): Android stops it after three minutes at most, which is
 * longer than a sign-in has any business taking. It holds no work of its own; the sign-in
 * itself runs in its view model, this only keeps the process awake around it.
 */
class SignInKeepAwakeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    /** The three minutes are up. The sign-in is abandoned by then; stop as Android requires. */
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.signin_channel), NotificationManager.IMPORTANCE_LOW),
        )
        return Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.signin_keep_awake))
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "sign-in"
        const val NOTIFICATION_ID = 1
    }
}

/** Starts and stops [SignInKeepAwakeService] around a browser sign-in. */
class ServiceKeepAwake(
    private val context: Context,
) : KeepAwake {
    override fun start() {
        // Started while the user is looking at the app (they just tapped Sign in), which is
        // when Android allows a foreground service to start.
        runCatching { context.startForegroundService(Intent(context, SignInKeepAwakeService::class.java)) }
    }

    override fun stop() {
        context.stopService(Intent(context, SignInKeepAwakeService::class.java))
    }
}
