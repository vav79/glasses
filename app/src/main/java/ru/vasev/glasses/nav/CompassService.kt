package ru.vasev.glasses.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.MainActivity
import ru.vasev.glasses.R
import ru.vasev.glasses.i18n.L

/**
 * Фоновый сервис «Компаса» (тип location): с ним GPS и подсказки работают с выключенным экраном и телефоном
 * в кармане. Сам ничего не считает — держит уведомление с расстоянием и кнопкой «Стоп»; работа — в [CompassController].
 */
class CompassService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as GlassesApp).compass.close()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, L.s(R.string.compass_notification_starting)), type)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "compass"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "ru.vasev.glasses.COMPASS_STOP"

        /** Обновить текст уведомления (расстояние и сторона); сервис не запущен — уведомления нет, ничего не делаем. */
        fun update(context: Context, text: String) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.activeNotifications.none { it.id == NOTIFICATION_ID }) return
            nm.notify(NOTIFICATION_ID, notification(context, text))
        }

        private fun notification(context: Context, text: String): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, L.s(R.string.compass_channel), NotificationManager.IMPORTANCE_LOW))
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val stop = PendingIntent.getService(
                context, 1,
                Intent(context, CompassService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_sym_navigation)
                .setContentTitle(L.s(R.string.compass_notification_title))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(open)
                .addAction(0, L.s(R.string.compass_stop), stop)
                .build()
        }
    }
}
