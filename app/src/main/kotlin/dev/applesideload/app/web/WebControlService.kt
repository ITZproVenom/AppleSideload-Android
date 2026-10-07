package dev.applesideload.app.web

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import dev.applesideload.app.MainActivity
import dev.applesideload.app.R
import dev.applesideload.app.SideloadApplication

/**
 * Keeps the web controller running while the app is in the background.
 *
 * A foreground service with a notification that shows the address and
 * has a Stop button. It holds a partial wake lock and a Wi-Fi lock
 * while it runs, because without them the phone stops answering on the
 * network a few minutes after the screen turns off.
 */
class WebControlService : Service() {

    private val app: SideloadApplication get() = application as SideloadApplication
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val watchAddresses = object : Runnable {
        override fun run() {
            if (app.webControl.refreshAddresses()) notifyStatus()
            handler.postDelayed(this, ADDRESS_POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Turned off by the user, in Settings or from the notification: it stays off.
            app.settings.webEnabled = false
            shutdown()
            return START_NOT_STICKY
        }
        app.settings.webEnabled = true
        try {
            startInForeground(buildNotification())
        } catch (error: RuntimeException) {
            // Android refuses a foreground service started from the background
            // or without its prerequisites; say so instead of crashing.
            app.webControl.reportError("Android did not allow the web controller to run: ${error.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!app.webControl.start()) {
            shutdown()
            return START_NOT_STICKY
        }
        acquireLocks()
        notifyStatus()
        handler.removeCallbacks(watchAddresses)
        handler.postDelayed(watchAddresses, ADDRESS_POLL_MS)
        return START_NOT_STICKY
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun shutdown() {
        handler.removeCallbacks(watchAddresses)
        app.webControl.stop()
        releaseLocks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchAddresses)
        app.webControl.stop()
        releaseLocks()
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AppleSideload:web").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AppleSideload:web").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun notifyStatus() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Web controller", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the LAN web controller is running"
                }
            )
        }
        val status = app.webControl.status.value
        val where = status.addresses.firstOrNull()?.url ?: "no network yet"
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, WebControlService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val text = where
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Web controller is running")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(
                (status.addresses.joinToString("\n") { "${it.label}: ${it.url}" }.ifEmpty { where })
            ))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "web-controller"
        private const val NOTIFICATION_ID = 8686
        private const val ADDRESS_POLL_MS = 15_000L
        const val ACTION_START = "dev.applesideload.app.web.START"
        const val ACTION_STOP = "dev.applesideload.app.web.STOP"

        fun start(context: Context) {
            val intent = Intent(context, WebControlService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (error: RuntimeException) {
                // Android refuses to start it while the app is in the background;
                // say so in Settings instead of crashing.
                (context.applicationContext as SideloadApplication).webControl
                    .reportError("Android did not allow the web controller to start: ${error.message}")
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WebControlService::class.java).setAction(ACTION_STOP))
        }
    }
}
