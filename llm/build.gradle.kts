plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// 기기 안에서 돌리는 언어 모델 (llama.cpp, GGUF). whisper.cpp 와 ggml 이 겹치므로 별도 네이티브 라이브러리로 둔다.
android {
    namespace = "com.c2c.llm"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared")
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
