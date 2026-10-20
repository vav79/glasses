package ru.vasev.glasses

import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CompletableDeferred

/**
 * Запросы MainActivity, ждущие ответа системы (разрешения, выбор фото). Живут в ViewModel, а не в полях Activity:
 * если Activity пересоздаётся, пока открыт системный диалог или галерея (поворот, смена языка на Android 10–12),
 * результат приходит уже новой Activity — и должен завершить тот же запрос, иначе выбор пропадает.
 */
class PendingRequests : ViewModel() {

    var glassesCamera: CompletableDeferred<Boolean>? = null
    var phoneCamera: CompletableDeferred<Boolean>? = null
    var notifications: CompletableDeferred<Boolean>? = null
    var location: CompletableDeferred<Boolean>? = null
    var photo: CompletableDeferred<Uri?>? = null

    /** Activity закрыта насовсем: ждущим — «отказ», чтобы корутины не висели. */
    fun cancelAll() {
        glassesCamera?.complete(false)
        phoneCamera?.complete(false)
        notifications?.complete(false)
        location?.complete(false)
        photo?.complete(null)
        glassesCamera = null
        phoneCamera = null
        notifications = null
        location = null
        photo = null
    }

    override fun onCleared() = cancelAll()
}
