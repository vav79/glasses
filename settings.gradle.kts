pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // DAT SDK (com.meta.wearable:mwdat-*) публикуется в Maven Central — токен не нужен.
        mavenCentral()
    }
}

rootProject.name = "Glasses"
include(":app")
