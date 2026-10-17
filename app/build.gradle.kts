import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Экспорт Room-схем в app/schemas/<database FQN>/<version>.json —
// история схемы для будущих тестов миграций.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// sherpa-onnx (KMP) тянет за собой sherpa-onnx-jvm с дублирующими классами —
// исключаем JVM-артефакт глобально (по имени модуля, группа отличается),
// оставляем только Android AAR.
configurations.configureEach {
    exclude(module = "sherpa-onnx-jvm")
}

// Параметры подписи release читаются из local.properties (файл не коммитится:
// keystore и пароли лежат только локально). Ключи: RELEASE_STORE_FILE,
// RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD.
val localProperties = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
val releaseSigningKeys = listOf(
    "RELEASE_STORE_FILE" to localProperties.getProperty("RELEASE_STORE_FILE"),
    "RELEASE_STORE_PASSWORD" to localProperties.getProperty("RELEASE_STORE_PASSWORD"),
    "RELEASE_KEY_ALIAS" to localProperties.getProperty("RELEASE_KEY_ALIAS"),
    "RELEASE_KEY_PASSWORD" to localProperties.getProperty("RELEASE_KEY_PASSWORD"),
)

android {
    namespace = "com.plox.llmtranslate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.plox.llmtranslate"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "0.5.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Целевые ABI: arm64-v8a — устройство, x86_64 — эмулятор.
        // Нативный код: slimt (движок Firefox Translations, app/third_party/slimt)
        // собирается через externalNativeBuild; фильтры также обеспечивают
        // корректную упаковку AAR-библиотек (sherpa-onnx, onnxruntime).
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // c++_static: самодостаточный libslimt_jni.so без libc++_shared.so
                // (конфликта с AAR-библиотеками нет).
                arguments += listOf("-DANDROID_STL=c++_static")
                cppFlags += "-std=c++20"
            }
        }
    }

    signingConfigs {
        val missingKeys = releaseSigningKeys
            .filter { it.second.isNullOrBlank() }
            .joinToString(", ") { it.first }
        if (missingKeys.isNotEmpty()) {
            throw GradleException(
                "local.properties: не заданы RELEASE_STORE_* для подписи release (нет: $missingKeys)"
            )
        }
        create("release") {
            storeFile = rootProject.file(localProperties.getProperty("RELEASE_STORE_FILE")!!)
            storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
            keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
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
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
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
            // ORT 1.28.2. В APK остаётся одна библиотека от onnxruntime-android
            // 1.30.0 ( VERS_1.30.0 ); pickFirst подавляет дубликат и ограничен
            // путём нативной библиотеки конкретного ABI.
            pickFirsts += "lib/*/libonnxruntime.so"
            // libsherpa-onnx-jni.so из AAR требует OrtGetApiBase@VERS_1.28.2 —
            // dlopen падал на единой libonnxruntime.so 1.30.0 (UnsatisfiedLinkError
            // при инициализации OfflineRecognizer). В src/main/jniLibs лежит
            // та же библиотека с исправленным в .dynstr именем version-node
            // (VERS_1.28.2 -> VERS_1.30.0, см. tools/patch-sherpa-jni-ort-version.ps1);
            // локальный jniLibs имеет приоритет при слиянии, pickFirst подавляет
            // дубликат из AAR.
            pickFirsts += "lib/*/libsherpa-onnx-jni.so"
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
    // версия libonnxruntime.so — 1.30.0 (см. packaging.jniLibs.pickFirsts).
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
