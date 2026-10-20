package ru.vasev.glasses.nav

import android.util.Log
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.R
import ru.vasev.glasses.camera.ImagePrep
import ru.vasev.glasses.camera.ImageSource
import ru.vasev.glasses.i18n.L

/** Видоискатель: что снимаем (подсказка) и можно ли обойтись без фото («Сохранить здесь»). */
data class ViewfinderRequest(@StringRes val hint: Int, val allowSkip: Boolean, val shooting: Boolean = false)

/** Итог снимка: кадр, без фото (нет кадра или «Без фото»), отмена («Назад» в видоискателе) или сбой с текстом для экрана. */
sealed interface Shot {
    class Taken(val jpeg: ByteArray) : Shot
    data object None : Shot
    data object Cancelled : Shot
    class Failed(val notice: String) : Shot
}

/**
 * Снимки «Навигации» (фото входа, фото адреса): очки, если подключены, иначе камера телефона — через видоискатель
 * на экране вкладки ([viewfinder]) или сразу, без него (голосовая команда); и выбор из галереи.
 */
class PhotoCapture(private val g: GlassesApp, private val session: NavSession) {

    private val _viewfinder = MutableStateFlow<ViewfinderRequest?>(null)

    /** Открыт видоискатель камеры телефона: снимок — по затвору. */
    val viewfinder: StateFlow<ViewfinderRequest?> = _viewfinder.asStateFlow()

    val phoneCamera get() = g.phoneCamera

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingShot: CompletableDeferred<Shot>? = null

    /**
     * Снимок из выбранного источника: очки, если подключены, иначе камера телефона (кнопку нажали на телефоне —
     * он в руке) — через видоискатель с подсказкой [hint]; null — сразу, без видоискателя (голосовая команда).
     * [quiet] — без сообщений (фото входа необязательно): почти чёрный кадр вслепую не прикрепляется.
     */
    suspend fun shoot(@StringRes hint: Int?, quiet: Boolean = false, allowSkip: Boolean = false): Shot = try {
        val hooks = session.hooks
        val glasses = g.glasses
        val source = g.imageSources.source.value
        val fromGlasses = (source == ImageSource.GLASSES || source == ImageSource.SIMULATOR) && glasses.status.value.ready
        when {
            fromGlasses -> Shot.Taken(glasses.captureJpeg(hooks?.requestGlassesCamera ?: { false }))
            hooks?.requestPhoneCamera?.invoke() != true ->
                if (quiet) Shot.None else Shot.Failed(L.s(R.string.camera_permission_denied))
            hint != null -> viewfinder(ViewfinderRequest(hint, allowSkip))
            else -> g.phoneCamera.captureWithoutPreview(hooks.owner)
                .takeUnless { quiet && ImagePrep.isDark(it) }?.let(Shot::Taken) ?: Shot.None
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(NavSession.TAG, "photo failed: ${e.javaClass.simpleName}")
        if (quiet) Shot.None else Shot.Failed(L.s(R.string.nav_photo_failed))
    }

    /** Открыть видоискатель и ждать затвора, «Без фото» или «Назад». */
    private suspend fun viewfinder(request: ViewfinderRequest): Shot {
        val result = CompletableDeferred<Shot>()
        pendingShot = result
        _viewfinder.value = request
        return try {
            result.await()
        } finally {
            pendingShot = null
            _viewfinder.value = null
        }
    }

    /** Затвор видоискателя: кадр видно на экране, поэтому тёмный не отбрасываем. */
    fun viewfinderShoot() {
        val request = _viewfinder.value ?: return
        if (request.shooting) return
        _viewfinder.update { it?.copy(shooting = true) }
        scope.launch {
            val shot = try {
                Shot.Taken(g.phoneCamera.capture())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(NavSession.TAG, "viewfinder photo failed: ${e.javaClass.simpleName}")
                Shot.Failed(L.s(R.string.nav_photo_failed))
            }
            pendingShot?.complete(shot)
        }
    }

    fun viewfinderSkip() {
        pendingShot?.complete(Shot.None)
    }

    fun viewfinderBack() {
        pendingShot?.complete(Shot.Cancelled)
    }

    /** Снимок из галереи (системный выбор фото, без разрешений). */
    suspend fun pickFromGallery(): ByteArray? {
        val uri = session.hooks?.pickPhoto?.invoke() ?: return null
        return withContext(Dispatchers.Default) { ImagePrep.fromUri(g.contentResolver, uri) }
    }
}
