plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// sherpa-onnx (KMP) тянет за собой sherpa-onnx-jvm с дублирующими классами —
// исключаем JVM-артефакт глобально (по имени модуля, группа отличается),
// оставляем только Android AAR.
configurations.configureEach {
    exclude(module = "sherpa-onnx-jvm")
}

android {
    namespace = "com.plox.llmtranslate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.plox.llmtranslate"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Целевые ABI: arm64-v8a — устройство, x86_64 — эмулятор.
        // Нативный код (llama/whisper) больше не собирается; фильтры оставлены
        // для корректной упаковки AAR-библиотек (sherpa-onnx, onnxruntime).
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Нативные библиотеки AAR (sherpa-onnx, onnxruntime) упаковываем по-старинке.
        jniLibs {
            useLegacyPackaging = true
            // Конфликт libonnxruntime.so: библиотеку несут onnxruntime-android
            // 1.30.0 (прямая зависимость; Java-API ai.onnxruntime 1.30.0 вызывается
            // из MarianOnnxRuntime/IndicTransOnnxRuntime, Java-часть 1.30.0 всегда
            // попадает в APK) и sherpa-onnx 1.13.8 со своей внутренней нативной
            // ORT 1.28.2. Требуемая для Java-API версия зафиксирована:
            // onnxruntime-android:1.30.0 (gradle/libs.versions.toml); на слиянии
            // сейчас побеждает именно она (проверено по mergedNativeLibs.debug),
            // pickFirst подавляет дубликат и ограничен путём нативной
            // библиотеки конкретного ABI (lib/<abi>/libonnxruntime.so).
            pickFirsts += "lib/*/libonnxruntime.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.work.multiprocess)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.timber)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // ONNX Runtime: Java-API инференса Marian/IndicTrans. Требуемая нативная
    // версия libonnxruntime.so — 1.30.0 (см. packaging.resources.pickFirsts).
    implementation(libs.onnxruntime.android)

    // Новый инференс-слой: sherpa-onnx (STT) + распаковка архивов моделей.
    implementation(libs.sherpa.onnx)
    implementation(libs.commons.compress)
    implementation(libs.xz)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.tooling)
}
