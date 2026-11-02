package ru.vasev.glasses.video

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.MainActivity
import ru.vasev.glasses.R
import ru.vasev.glasses.i18n.L

/**
 * Служба записи видео (типы connectedDevice + microphone, если со звуком): запись идёт при погасшем экране и
 * закрытом приложении. Уведомление «Идёт запись · 12:34 · 210 МБ» с «Стоп» (и «Продолжить» на паузе).
 * Запускает и останавливает [VideoRecorder]; стартует только из открытого приложения (из фона Android не даст).
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recorder = (application as GlassesApp).recorder
        when (intent?.action) {
            ACTION_STOP -> {
                recorder.stop()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                recorder.resume()
                return START_NOT_STICKY
            }
        }
        val withMic = intent?.getBooleanExtra(EXTRA_MIC, false) == true
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or (if (withMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, recorder.status.value), type)
        collector?.cancel()
        // Раз в секунду не нужно: статус обновляется каждые 15 кадров (0,5 с) — уведомлению хватает секунд.
        collector = scope.launch {
            recorder.status.map { it.copy(elapsedMs = it.elapsedMs / 1000 * 1000, bytes = it.bytes / (1024 * 1024)) }
                .distinctUntilChanged()
                .collect { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(this@RecordingService, recorder.status.value)) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Video"
        private const val CHANNEL = "video"
        private const val NOTIFICATION_ID = 44
        private const val ACTION_STOP = "ru.vasev.glasses.VIDEO_STOP"
        private const val ACTION_RESUME = "ru.vasev.glasses.VIDEO_RESUME"
        private const val EXTRA_MIC = "mic"

        fun start(context: Context, withMic: Boolean) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).putExtra(EXTRA_MIC, withMic))
            } catch (e: Exception) {
                Log.w(TAG, "service start failed: ${e.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }

        private fun notification(context: Context, status: RecordingStatus): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, L.s(R.string.video_notif_channel), NotificationManager.IMPORTANCE_LOW))
            }
            val open = PendingIntent.getActivity(
                context, 10,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab", "media"),
                PendingIntent.FLAG_IMMUTABLE,
            )
            fun action(action: String, code: Int) = PendingIntent.getService(
                context, code, Intent(context, RecordingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
            )
            val title = when (status.phase) {
                RecPhase.RECORDING -> L.s(R.string.video_notif_recording, formatDuration(status.elapsedMs), formatBytes(status.bytes))
                RecPhase.PAUSED -> L.s(R.string.video_notif_paused)
                RecPhase.WAITING -> L.s(R.string.video_notif_waiting)
                else -> L.s(R.string.video_notif_starting)
            }
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_sym_eyeglasses)
                .setContentTitle(title)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(open)
            if (status.phase == RecPhase.PAUSED) builder.addAction(0, L.s(R.string.video_resume), action(ACTION_RESUME, 12))
            return builder.addAction(0, L.s(R.string.video_stop), action(ACTION_STOP, 11)).build()
        }

        /** 12:34 или 1:02:03. */
        fun formatDuration(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }

        /** 210 МБ / 1,2 ГБ. */
        fun formatBytes(bytes: Long): String {
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1024) L.s(R.string.video_gb, mb / 1024) else L.s(R.string.video_mb, mb)
        }
    }
}
