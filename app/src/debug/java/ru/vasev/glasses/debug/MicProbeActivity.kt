package ru.vasev.glasses.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import ru.vasev.glasses.speech.AudioChannel
import kotlinx.coroutines.launch
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.speech.SpeechException
import ru.vasev.glasses.speech.Talker
import ru.vasev.glasses.ui.theme.GlassesTheme

/**
 * Проба: как микрофон очков слышит собеседника на 1–2 м (только debug). Собеседник читает фразу с экрана,
 * распознаёт Google так же, как в «Переводе»: «Очки» — микрофон очков по Bluetooth (как мой голос),
 * «Телефон» — микрофон телефона (как сейчас слушается собеседник). Итог — доля слов образца, которые
 * распознаны, и средняя громкость. adb shell am start -n ru.vasev.glasses/.debug.MicProbeActivity, лог MicProbe.
 */
class MicProbeActivity : ComponentActivity() {

    private val lines = mutableStateListOf<String>()
    private val phrase = mutableStateOf(PHRASES.first())
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlassesTheme(dark = false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Собеседник, прочитайте вслух:", style = MaterialTheme.typography.titleMedium)
                        Text(phrase.value, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { listen(Talker.USER) }) { Text("Очки") }
                            Button(onClick = { listen(Talker.PARTNER) }) { Text("Телефон") }
                            Button(onClick = ::nextPhrase) { Text("Другая фраза") }
                            Button(onClick = { detectLanguages(onDevice = false) }) { Text("Автоязык") }
                            Button(onClick = { detectLanguages(onDevice = true) }) { Text("Автоязык (на телефоне)") }
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(lines.reversed()) { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
        when (intent.getStringExtra("detect")) {
            "cloud" -> detectLanguages(onDevice = false)
            "device" -> detectLanguages(onDevice = true)
        }
    }

    private fun nextPhrase() {
        phrase.value = PHRASES[(PHRASES.indexOf(phrase.value) + 1) % PHRASES.size]
    }

    /** Одна фраза: распознавание как в приложении; громкость — среднее RMS распознавателя (0..1). */
    private fun listen(talker: Talker) {
        job?.cancel()
        val app = application as GlassesApp
        val reference = phrase.value
        val mic = if (talker == Talker.USER) "glasses" else "phone"
        log("$mic: listening…")
        job = lifecycleScope.launch {
            val levels = mutableListOf<Float>()
            try {
                val heard = app.speechSource.listen("ru-RU", talker, onLevel = { levels += it })
                val score = wordMatch(reference, heard.text)
                log("$mic: match=$score% level=${"%.2f".format(levels.averageOrZero())} «${heard.text}»")
            } catch (e: SpeechException) {
                log("$mic: ${e.message} level=${"%.2f".format(levels.averageOrZero())}")
            }
        }
    }

    /**
     * Автоопределение языка (Android 14+: EXTRA_ENABLE_LANGUAGE_DETECTION). Телефон сам произносит фразу на языке
     * через динамик, распознаватель слушает с основным языком ru-RU и списком разрешённых; в лог — какой язык он
     * определил и что распознал. [onDevice] — распознаватель на телефоне (createOnDeviceSpeechRecognizer).
     */
    private fun detectLanguages(onDevice: Boolean) {
        job?.cancel()
        val app = application as GlassesApp
        job = lifecycleScope.launch {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@launch log("detect: needs Android 14+")
            val available = if (onDevice) SpeechRecognizer.isOnDeviceRecognitionAvailable(this@MicProbeActivity) else SpeechRecognizer.isRecognitionAvailable(this@MicProbeActivity)
            log("detect: onDevice=$onDevice available=$available")
            if (!available) return@launch
            for ((tag, text) in DETECT_SAMPLES) {
                val recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(this@MicProbeActivity) else SpeechRecognizer.createSpeechRecognizer(this@MicProbeActivity)
                val result = CompletableDeferred<String>()
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        val heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        val lang = results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
                        val conf = results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL, -1)
                        result.complete("detected=$lang conf=$conf «$heard»")
                    }
                    override fun onLanguageDetection(results: Bundle) {
                        log("detect: [$tag] partial language=${results.getString(SpeechRecognizer.DETECTED_LANGUAGE)}")
                    }
                    override fun onError(error: Int) { result.complete("error=$error") }
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                recognizer.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                        putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                        putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf("ru-RU", "en-US", "th-TH", "de-DE"))
                        putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE)
                    },
                )
                delay(700)
                runCatching { app.audioOutput.speak(text, Locale.forLanguageTag(tag), AudioChannel.PARTNER) }
                val outcome = withTimeoutOrNull(12_000) { result.await() } ?: "timeout"
                log("detect: [$tag] $outcome")
                recognizer.destroy()
                delay(800)
            }
        }
    }

    private fun List<Float>.averageOrZero(): Float = if (isEmpty()) 0f else average().toFloat()

    private fun log(text: String) {
        Log.d(TAG, text)
        lines += text
    }

    companion object {
        private const val TAG = "MicProbe"

        private val PHRASES = listOf(
            "Добрый день. Подскажите, пожалуйста, как пройти до ближайшей станции метро и сколько стоит билет.",
            "Мы работаем с девяти утра до восьми вечера, обед с часу до двух, оплата картой или наличными.",
            "Возьмите такси у главного входа, дорога до аэропорта займёт около сорока минут.",
        )

        /** Фразы для автоопределения языка: язык → фраза (произносит сам телефон). */
        private val DETECT_SAMPLES = listOf(
            "en-US" to "Excuse me, where is the nearest train station?",
            "th-TH" to "สวัสดีครับ ห้องน้ำอยู่ที่ไหนครับ",
            "de-DE" to "Entschuldigung, wie komme ich zum Bahnhof?",
            "ru-RU" to "Подскажите, пожалуйста, где здесь вокзал?",
        )

        /** Доля слов образца (без регистра и знаков), которые есть в распознанном тексте, — в процентах. */
        fun wordMatch(reference: String, heard: String): Int {
            fun words(s: String) = s.lowercase().replace('ё', 'е').split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            val ref = words(reference)
            val got = words(heard).toMutableList()
            val hit = ref.count { got.remove(it) }
            return if (ref.isEmpty()) 0 else hit * 100 / ref.size
        }
    }
}
