package com.sabourti.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** خدمة أمامية: تُبقي التطبيق والاتصال (صوت/كاميرا/بيانات) حيّاً حين يكون في الخلفية. */
class RoomService : Service() {

    companion object {
        @Volatile
        var running = false
    }

    private var wl: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val text = intent?.getStringExtra("text") ?: "الغرفة مفتوحة"
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel("room", "غرفة سبورتي", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = Intent(this, RoomService::class.java).setAction("STOP")
        val ps = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, "room")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("سبورتي")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(0, "إنهاء", ps)
            .build()

        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        try {
            ServiceCompat.startForeground(this, 7, n, type)
        } catch (e: Exception) {
            try {
                ServiceCompat.startForeground(this, 7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } catch (_: Exception) {
            }
        }
        running = true
        try {
            if (wl == null) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sabourti:room")
                wl?.acquire(6L * 60L * 60L * 1000L)
            }
        } catch (_: Exception) {
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try {
            if (wl?.isHeld == true) wl?.release()
        } catch (_: Exception) {
        }
        wl = null
        super.onDestroy()
    }
}
