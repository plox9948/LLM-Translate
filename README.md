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

   Субмодули (llama.cpp, whisper.cpp) подключаются в `app/src/main/cpp/external/`:

   ```bash
   git submodule add https://github.com/ggml-org/llama.cpp app/src/main/cpp/external/llama.cpp
   git submodule add https://github.com/ggml-org/whisper.cpp app/src/main/cpp/external/whisper.cpp
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
| Hy-MT 1.8B Q1.25 | Перевод | ~440 МБ | 33 языка, рекомендуемая |
| Hy-MT 1.8B Q2 | Перевод | ~574 МБ | максимальное качество |
| NLLB-200 600M INT8 | Перевод | ~1.1 ГБ | 200 языков (ONNX) |
| Tiny Aya 3.35B | Перевод | ~2.1 ГБ | 70 языков, высокое качество |
| Vosk RU Small | STT | ~45 МБ | русский |
| Whisper Tiny (Nepali) | STT | ~78 МБ | непальский |
| Whisper Base (Multi) | STT | ~150 МБ | ru+en+ne |
| Системный TTS / RHVoice / eSpeak NG | TTS | — | без установки |

## Лицензии моделей

Некоторые модели распространяются по некоммерческим лицензиям (CC-BY-NC и др.).
При скачивании таких моделей приложение запрашивает подтверждение условий лицензии.
