# LLM-Translate

Офлайн-переводчик для Android (RU ⇄ EN ⇄ NE). Весь инференс выполняется на устройстве —
приложение не требует интернета во время перевода.

## Возможности

- **Перевод текста** между русским, английским и непальским через локальную LLM
  (llama.cpp, GGUF).
- **Распознавание речи** (STT) через whisper.cpp (непальский, русский, мультиязычный).
- **Синтез речи** (TTS) через системный Android TextToSpeech
  (с поддержкой RHVoice / eSpeak NG как альтернативных движков).
- **Менеджер моделей**: каталог моделей, скачивание с докачкой (Range),
  проверка SHA-256, импорт своих GGUF через SAF, экспорт моделей.
- **Управление памятью**: в ОЗУ только активные модели, выгрузка при сворачивании
  приложения, предупреждения о нехватке ОЗУ/места.

## Стек

- Kotlin, Jetpack Compose + Material 3
- MVVM + Clean Architecture (data / domain / ui)
- Hilt (DI), Coroutines + Flow
- Room (метаданные моделей), DataStore (выбор), WorkManager (скачивание)
- JNI + CMake (llama.cpp, whisper.cpp)

## Структура

```
app/src/main/java/com/plox/llmtranslate/
├── data/
│   ├── catalog/    # парсинг JSON-каталога моделей
│   ├── model/      # Room: сущности, DAO, репозиторий
│   ├── download/   # WorkManager-воркер, инсталляторы (GGUF/ONNX/Vosk)
│   └── prefs/      # настройки (DataStore)
├── domain/
│   ├── ModelManager.kt      # единая точка входа для UI
│   └── runtime/             # LlmRuntime, SttRuntime, TtsRuntime, NativeBridge
├── ui/                      # экраны: Перевод, Модели, Настройки
└── di/                      # Hilt-модули
app/src/main/cpp/            # CMake + JNI-мосты
app/src/main/assets/         # models_catalog.json
```

## Сборка

### Требования

- Android Studio (или JDK 17 + Android SDK 34)
- Git с поддержкой субмодулей

### Шаги

1. Клонировать репозиторий и инициализировать субмодули:

   ```bash
   git clone http://31.44.89.204:3000/plox/LLM-Translate.git
   cd LLM-Translate
   git submodule update --init --recursive
   ```

   Субмодули (llama.cpp, whisper.cpp) подключаются в `app/src/main/cpp/external/`.
   **llama.cpp фиксируется на ветке PR #22836** (ядро STQ1_0 для модели
   Hy-MT1.5 1.25-bit) — подробные команды в `app/src/main/cpp/README.md`:

   ```bash
   cd app/src/main/cpp/external
   git submodule add https://github.com/ggml-org/llama.cpp llama.cpp
   cd llama.cpp
   git fetch origin pull/22836/head:pr-22836-stq_0
   git checkout pr-22836-stq_0
   cd ..
   git submodule add https://github.com/ggml-org/whisper.cpp whisper.cpp
   cd ../..
   git add app/src/main/cpp/external
   ```

2. Создать `local.properties` с указанием пути к Android SDK:

   ```
   sdk.dir=/path/to/android/sdk
   ```

3. Собрать:

   ```bash
   ./gradlew assembleDebug
   ```

4. Установить на устройство:

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

## Тесты

```bash
./gradlew test                  # unit-тесты
./gradlew connectedAndroidTest  # instrumentation
```

## Каталог моделей

Стартовый набор в `app/src/main/assets/models_catalog.json`:

| Модель | Категория | Размер | Описание |
|--------|-----------|--------|----------|
| Hy-MT1.5 1.8B 1.25-bit (STQ) | Перевод | ~462 МБ | 33 языка, рекомендуемая (нужен llama.cpp PR #22836) |
| Hy-MT1.5 1.8B 2-bit (SEQ) | Перевод | ~601 МБ | максимальное качество (kernel Q2_0c, SME2) |
| Hy-MT2 1.8B Q4_K_M | Перевод | ~1.13 ГБ | стандартный GGUF, 33 языка |
| Hy-MT2 1.8B Q8_0 | Перевод | ~1.91 ГБ | максимальное качество Hy-MT2 |
| NLLB-200 600M Q4F16 | Перевод | ~880 МБ | 200 языков (ONNX, encoder+decoder) |
| Tiny Aya Global 3.35B | Перевод | ~2.14 ГБ | 67 языков, высокое качество (CC-BY-NC) |
| Vosk RU Small | STT | ~45 МБ | русский |
| Whisper Tiny (Nepali) | STT | ~51 МБ | непальский |
| Whisper Base (Multi) | STT | ~143 МБ | ru+en+ne |
| Системный TTS / RHVoice / eSpeak NG | TTS | — | без установки |

## Лицензии моделей

Некоторые модели распространяются по некоммерческим лицензиям (CC-BY-NC и др.).
При скачивании таких моделей приложение запрашивает подтверждение условий лицензии.
