import java.time.LocalDate
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Локальные настройки сборки (режим по умолчанию, ID для Meta) — из local.properties (он в .gitignore).
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Подпись релизов — отдельный ключ вне репозитория: %USERPROFILE%\.sputnik-signing\ (keystore + signing.properties
// с паролями). Нет папки — release собирается неподписанным.
val signingProps = Properties().apply {
    val file = File(System.getProperty("user.home"), ".sputnik-signing/signing.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Публичный APK: -Pmwdat.devMode=true — идентификаторы Meta из local.properties не берутся (режим разработчика),
// чтобы наш токен не попал в файл, который выкладывается для всех.
val forceMwdatDevMode = (findProperty("mwdat.devMode") as String?) == "true"

android {
    namespace = "ru.vasev.glasses"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.vasev.glasses"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // Только 64-битный ARM: все актуальные телефоны, APK в несколько раз меньше.
        ndk {
            abiFilters += "arm64-v8a"
        }

        // API-ключи провайдеров в сборку не входят: они вводятся в приложении и хранятся зашифрованными (ApiKeyStore).
        // Симулятор очков выбирается в приложении как источник снимков (только в debug-сборке).

        // Идентификаторы проекта из Wearables Developer Center — только в local.properties (не в git):
        // mwdat.applicationId и mwdat.clientToken (старые имена mwdat_application_id / mwdat_client_token тоже читаются).
        // Нет строк — "0": режим разработчика Meta AI, аттестация не используется.
        fun mwdat(name: String, legacy: String): String =
            if (forceMwdatDevMode) "0" else (localProps.getProperty(name) ?: localProps.getProperty(legacy))?.trim()?.takeIf { it.isNotEmpty() } ?: "0"
        val mwdatAppId = mwdat("mwdat.applicationId", "mwdat_application_id")
        manifestPlaceholders["mwdat_application_id"] = mwdatAppId
        manifestPlaceholders["mwdat_client_token"] = mwdat("mwdat.clientToken", "mwdat_client_token")
        // Режим в лог при запуске (сами значения не пишем).
        buildConfigField("boolean", "MWDAT_DEV_MODE", (mwdatAppId == "0").toString())
        // «О приложении»: дата сборки (день, без времени — сборки за день не отличаются).
        buildConfigField("String", "BUILD_DATE", "\"${LocalDate.now()}\"")
    }

    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: сжатие и удаление недостижимого кода (в т. ч. веток BuildConfig.DEBUG). Правила — proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

dependencies {
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    // Симулятор очков (MockDeviceKit) — только debug: в release вместо MockGlassesController — заглушка из src/release.
    debugImplementation(libs.mwdat.mockdevice)
    implementation(libs.anthropic.java)
    // OpenAI-совместимые провайдеры (DeepSeek); та же версия, что приходит транзитивно от SDK.
    implementation(libs.okhttp)
    implementation(libs.mlkit.barcode)
    implementation(libs.mlkit.text)
    implementation(libs.androidx.browser)
    // Камера телефона как источник снимков.
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    // Per-app language (setApplicationLocales) на Android 10–12 и AppCompatActivity.
    implementation(libs.androidx.appcompat)
    // DAT Speech (экспериментальный, только dev/beta) — пока только debug-проба SpeechProbeActivity.
    debugImplementation(libs.mwdat.speech)
    // DAT Inputs (сенсорная панель и кнопки очков, только dev/beta) — пока только debug-проба InputsProbeActivity.
    debugImplementation(libs.mwdat.inputs)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // org.json есть в Android, но не в JVM-тестах.
    testImplementation(libs.org.json)
}
