pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        id("com.android.library") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.1.0"
        id("org.jetbrains.kotlin.jvm") version "2.1.0"
        id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "C2C"

// core: 순수 Kotlin/JVM 모듈 (Android SDK 없이 빌드·테스트 가능)
include(":core")

// app, llm: Android 모듈. Android SDK가 있는 환경(local.properties의 sdk.dir 또는 ANDROID_HOME)에서만 포함한다.
// SDK가 없는 CI/서버에서도 `./gradlew :core:test`가 동작하도록 하기 위함.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (hasAndroidSdk) {
    include(":app")
    include(":llm") // 기기 내 언어 모델 (llama.cpp)
} else {
    logger.lifecycle("Android SDK를 찾지 못해 :app 모듈을 건너뜁니다. (:core 만 빌드)")
}
