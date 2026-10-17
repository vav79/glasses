package ru.vasev.glasses.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** Состояние ключа для интерфейса: только последние 4 символа, сам ключ сюда не попадает. */
data class ApiKeyInfo(val tail: String, val strongBox: Boolean)

class ApiKeyException(message: String) : Exception(message)

/**
 * Хранение API-ключа Anthropic на телефоне.
 *
 * - Ключ шифруется AES-256-GCM ключом из Android Keystore (StrongBox, если он есть). Ключ шифрования
 *   не покидает Keystore, а в SharedPreferences лежит только `IV || шифротекст` в Base64.
 * - Расшифровка — только в памяти, непосредственно перед запросом к Claude ([decrypt]); открытый ключ
 *   нигде не сохраняется и не логируется.
 * - Резервное копирование данных приложения отключено в манифесте, а ключ Keystore на другом устройстве
 *   всё равно недоступен.
 */
class ApiKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val hasStrongBox =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    private val _hasKey = MutableStateFlow(prefs.contains(PREF_CIPHERTEXT))

    /** Сохранён ли ключ. Определяется по наличию шифротекста — без расшифровки. */
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    /** Шифрует и сохраняет ключ (заменяя прежний). */
    fun save(apiKey: String) {
        val key = apiKey.trim()
        validate(key)
        val plain = key.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD(AAD)
            val sealed = cipher.iv + cipher.doFinal(plain)
            prefs.edit().putString(PREF_CIPHERTEXT, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
        } finally {
            plain.fill(0)
        }
        Log.d(TAG, "API-ключ сохранён (зашифрован${if (isStrongBoxKey()) ", StrongBox" else ""})")
        _hasKey.value = true
    }

    /** Для экрана «Настройки»: расшифровывает в памяти, оставляет только последние 4 символа. */
    fun describe(): ApiKeyInfo? = decrypt()?.let { ApiKeyInfo(tail = it.takeLast(4), strongBox = isStrongBoxKey()) }

    /**
     * Расшифровывает ключ в памяти для одного запроса. Вызывающий код не должен его сохранять или логировать.
     * null — ключ не сохранён; [ApiKeyException] — шифротекст не расшифровывается (ключ нужно ввести заново).
     */
    fun decrypt(): String? {
        val stored = prefs.getString(PREF_CIPHERTEXT, null) ?: return null
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val plain = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(GCM_TAG_BITS, sealed, 0, GCM_IV_BYTES))
            cipher.updateAAD(AAD)
            cipher.doFinal(sealed, GCM_IV_BYTES, sealed.size - GCM_IV_BYTES)
        } catch (e: Exception) {
            // Ключ Keystore удалён или сменился (например, после сброса блокировки экрана) — ключ API не восстановить.
            Log.w(TAG, "Не удалось расшифровать API-ключ: ${e.javaClass.simpleName}")
            throw ApiKeyException("Сохранённый API-ключ не читается. Введите его заново в «Настройках».")
        } finally {
            sealed.fill(0)
        }
        return try {
            String(plain, Charsets.UTF_8)
        } finally {
            plain.fill(0)
        }
    }

    fun delete() {
        prefs.edit().remove(PREF_CIPHERTEXT).apply()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
        Log.d(TAG, "API-ключ удалён")
        _hasKey.value = false
    }

    private fun validate(key: String) {
        if (key.isEmpty()) throw ApiKeyException("Введите ключ.")
        if (key.any { it.isWhitespace() }) throw ApiKeyException("В ключе не должно быть пробелов и переносов строк.")
        if (key.length < MIN_KEY_LENGTH) throw ApiKeyException("Ключ слишком короткий — скопируйте его целиком.")
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey =
        keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: throw ApiKeyException("Ключ шифрования не найден.")

    /** Ключ шифрования из Keystore; создаётся при первом сохранении (в StrongBox, если он есть). */
    private fun secretKey(): SecretKey {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (hasStrongBox) {
            try {
                return generateKey(strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                Log.w(TAG, "StrongBox недоступен, ключ шифрования будет в обычном Keystore")
            }
        }
        return generateKey(strongBox = false)
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun isStrongBoxKey(): Boolean = runCatching {
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: return false
        val info = SecretKeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        } else {
            false
        }
    }.getOrDefault(false)

    private companion object {
        const val TAG = "KeyStore"
        const val PREFS = "secure"
        const val PREF_CIPHERTEXT = "anthropic_key_ct"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "anthropic_api_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val MIN_KEY_LENGTH = 20
        val AAD = "ru.vasev.glasses/anthropic_api_key".toByteArray(Charsets.UTF_8)
    }
}
