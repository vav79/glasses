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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.MainActivity
import ru.vasev.glasses.R
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.links.FoundLink
import ru.vasev.glasses.ui.Phase
import java.util.UUID

/**
 * Служба ассистента (типы microphone + mediaPlayback): держит «Жест на дужке» и ход ассистента
 * ([ru.vasev.glasses.ui.AssistantEngine]) и при закрытом приложении — после «Назад» и смахивания из недавних.
 * Android включает микрофон из фона, только если такая служба запущена, пока приложение было на экране, —
 * поэтому её запускает MainActivity ([sync]); сама после перезагрузки не стартует.
 * Живёт до «Выключить» в уведомлении. Уведомление: состояние (готов / слушаю / думаю / отвечаю) и ссылки,
 * найденные без экрана, — кнопкой (из фона приложение само не откроется).
 */
class GesturesService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as GlassesApp
        if (intent?.action == ACTION_OFF) {
            Log.d(TAG, "gesture: service turned off from notification")
            app.gestureSettings.setEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        val view = combine(
            app.gestures.state,
            app.gestureSettings.tap,
            app.assistant.state.map { it.phase }.distinctUntilChanged(),
            app.assistant.backgroundLinks,
        ) { state, tap, phase, links -> View(state, tap, phase, links) }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, View(app.gestures.state.value, app.gestureSettings.tap.value, Phase.IDLE, emptyList())), type)
        // onStartCommand приходит при каждом открытии приложения (sync) — подписка одна.
        collector?.cancel()
        collector = scope.launch {
            view.distinctUntilChanged().collect {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(this@GesturesService, it))
            }
        }
        // Перезапуск после гибели процесса не нужен: из фона Android микрофон не даст (START_NOT_STICKY).
        return START_NOT_STICKY
    }

    /** Смахнули приложение из недавних: служба остаётся (stopWithTask=false) — только отметка в логе. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "gesture: app task removed, service keeps running")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d(TAG, "gesture: service destroyed")
        scope.cancel()
        super.onDestroy()
    }

    /** Что показывает уведомление. */
    private data class View(val state: GestureState, val tap: Tap, val phase: Phase, val links: List<FoundLink>)

    companion object {
        private const val TAG = "Audio"
        private const val CHANNEL = "gestures"
        private const val NOTIFICATION_ID = 43
        private const val ACTION_OFF = "ru.vasev.glasses.GESTURES_OFF"

        /** Кнопка ссылки в уведомлении → MainActivity: адрес, из QR ли, открыть сразу (сайт без предупреждений). */
        const val EXTRA_LINK = "ru.vasev.glasses.extra.LINK"
        const val EXTRA_LINK_QR = "ru.vasev.glasses.extra.LINK_QR"
        const val EXTRA_LINK_OPEN = "ru.vasev.glasses.extra.LINK_OPEN"
        const val EXTRA_LINK_TOKEN = "ru.vasev.glasses.extra.LINK_TOKEN"

        /**
         * Одноразовый ключ кнопок ссылок: MainActivity открывает ссылку, только если ключ совпал, — иначе любое
         * приложение могло бы попросить нас открыть сайт. Меняется при каждом показе ссылок.
         */
        @Volatile
        var linkToken: String = UUID.randomUUID().toString()
            private set

        /** Включить или выключить службу по настройке. Вызывать, пока приложение на экране. */
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

        private fun notification(context: Context, view: View): Notification {
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
            val title = when (view.phase) {
                Phase.LISTENING -> R.string.assistant_notif_listening
                Phase.IDLE -> if (view.state != GestureState.READY) R.string.gesture_notification_title
                    else if (view.tap == Tap.DOUBLE) R.string.assistant_notif_ready_double else R.string.assistant_notif_ready_single
                Phase.SPEAKING -> R.string.assistant_notif_speaking
                else -> R.string.assistant_notif_thinking
            }
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_sym_eyeglasses)
                .setContentTitle(L.s(title))
                .setContentText(L.s(stateText(view.state, view.tap)))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(open)
            linkAction(context, view.links)?.let { builder.addAction(it) }
            return builder.addAction(0, L.s(R.string.gesture_off), off).build()
        }

        /**
         * Кнопка для ссылок, найденных без экрана. Одна ссылка на сайт без предупреждений — «Открыть <домен>»
         * (нажатие и есть подтверждение); всё остальное — «Проверить ссылку» (окно проверки на экране, как в чате);
         * несколько — «Показать ссылки» (чат с карточками).
         */
        private fun linkAction(context: Context, links: List<FoundLink>): NotificationCompat.Action? {
            if (links.isEmpty()) return null
            linkToken = UUID.randomUUID().toString()
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val label = when (val link = links.singleOrNull()) {
                null -> L.s(R.string.assistant_notif_links_show)
                else -> {
                    val direct = link.info.voiceConfirmAllowed
                    intent.putExtra(EXTRA_LINK, link.info.raw).putExtra(EXTRA_LINK_QR, link.fromQr)
                        .putExtra(EXTRA_LINK_OPEN, direct).putExtra(EXTRA_LINK_TOKEN, linkToken)
                    if (direct) L.s(R.string.assistant_notif_link_open, link.info.domain.orEmpty()) else L.s(R.string.assistant_notif_link_check)
                }
            }
            val pending = PendingIntent.getActivity(context, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Action(0, label, pending)
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
