# R8 для release. Консервативно: библиотеки с рефлексией сохраняются целиком, сжимается в основном наш код.

# Атрибуты для рефлексии, generics и Kotlin-метаданных (Jackson + Kotlin module в Anthropic SDK).
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,Exceptions
-keep class kotlin.Metadata { *; }

# Anthropic Java SDK: модели запросов и ответов разбирает Jackson по рефлексии.
-keep class com.anthropic.** { *; }
-dontwarn com.anthropic.**
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

# OkHttp / Okio (DeepSeek и SDK).
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Meta Wearables DAT SDK: регистрация, сессии, интенты Meta AI — сохраняем целиком.
# Кроме симулятора (MockDeviceKit): он нужен только debug-сборке, в release его вызовы за BuildConfig.DEBUG — R8 его убирает.
-keep class !com.meta.wearable.dat.mockdevice.**, com.meta.wearable.** { *; }
-dontwarn com.meta.wearable.**

# ML Kit (QR и текст на снимках, адрес с фото): нативные библиотеки обращаются к классам по именам через JNI —
# без этого в release сканирование падало с NullPointerException (проверено на эмуляторе, debug при этом читал QR).
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_** { *; }
-keep class com.google.android.libraries.barhopper.** { *; }
-keep class com.google.photos.vision.barhopper.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-dontwarn com.google.mlkit.**

# Прочие необязательные зависимости SDK.
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn java.lang.management.**
# Генератор JSON-схем в Anthropic SDK (структурированные ответы — не используем) ссылается на API Java 8+, которого нет в Android.
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
