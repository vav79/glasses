import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Секреты читаются только из local.properties (он в .gitignore).
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun String.asBuildConfigString() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

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

        buildConfigField(
            "String",
            "ANTHROPIC_API_KEY",
            localProps.getProperty("anthropic_api_key", "").trim().asBuildConfigString(),
        )
        buildConfigField(
            "boolean",
            "USE_MOCK_DEVICE_DEFAULT",
            localProps.getProperty("use_mock_device", "true").trim().toBoolean().toString(),
        )

        // В Developer Mode приложения Meta AI аттестация не используется — достаточно "0".
        manifestPlaceholders["mwdat_application_id"] = localProps.getProperty("mwdat_application_id", "0")
        manifestPlaceholders["mwdat_client_token"] = localProps.getProperty("mwdat_client_token", "0")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation(libs.mwdat.mockdevice)
    implementation(libs.anthropic.java)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
