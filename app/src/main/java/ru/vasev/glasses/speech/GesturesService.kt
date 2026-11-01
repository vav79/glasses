package ru.vasev.glasses.speech

import android.app.ForegroundServiceStartNotAllowedException
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.MainActivity
import ru.vasev.glasses.R
import ru.vasev.glasses.i18n.L

/**
 * Фоновый сервис «Жеста на дужке» (типы microphone + mediaPlayback): с ним касание очков начинает вопрос
 * при погасшем экране и телефоне в кармане. Android включает микрофон из фона, только если такой сервис
 * запущен, пока приложение было на экране, — поэтому его запускает MainActivity ([sync]).
 * Сам ничего не ловит — держит уведомление с состоянием и кнопкой «Выключить»; работа — в [GlassesGestures].
 */
class GesturesService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as GlassesApp
        if (intent?.action == ACTION_OFF) {
            app.gestureSettings.setEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, app.gestures.state.value, app.gestureSettings.tap.value), type)
        scope.launch {
            app.gestures.state.collect { state ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(this@GesturesService, state, app.gestureSettings.tap.value))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Audio"
        private const val CHANNEL = "gestures"
        private const val NOTIFICATION_ID = 43
        private const val ACTION_OFF = "ru.vasev.glasses.GESTURES_OFF"

        /** Включить или выключить сервис по настройке. Вызывать, пока приложение на экране. */
        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, GesturesService::class.java)
            if (!enabled) {
                context.stopService(intent)
                return
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Android 12+: из фона запускать нельзя — запустится при следующем открытии приложения.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    Log.w(TAG, "gesture: service not started from background")
                } else {
                    Log.w(TAG, "gesture: service start failed: ${e.javaClass.simpleName}")
                }
            }
        }

        private fun notification(context: Context, state: GestureState, tap: Tap): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, L.s(R.string.gesture_channel), NotificationManager.IMPORTANCE_LOW))
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val off = PendingIntent.getService(
                context, 2,
                Intent(context, GesturesService::class.java).setAction(ACTION_OFF),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_sym_eyeglasses)
                .setContentTitle(L.s(R.string.gesture_notification_title))
                .setContentText(L.s(stateText(state, tap)))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(open)
                .addAction(0, L.s(R.string.gesture_off), off)
                .build()
        }

        /** Подпись состояния — в уведомлении и в настройках. */
        fun stateText(state: GestureState, tap: Tap): Int = when (state) {
            GestureState.READY -> if (tap == Tap.DOUBLE) R.string.gesture_state_ready_double else R.string.gesture_state_ready_single
            GestureState.NO_GLASSES -> R.string.gesture_state_no_glasses
            GestureState.OTHER_MEDIA -> R.string.gesture_state_other_media
            GestureState.MEDIA_COOLDOWN -> R.string.gesture_state_cooldown
            GestureState.CALL -> R.string.gesture_state_call
            GestureState.OFF -> R.string.gesture_state_off
        }
    }
}
