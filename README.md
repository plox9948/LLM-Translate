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

## Резервное копирование и перенос на другое устройство

Приложение умеет экспортировать все модели и настройки в ZIP-архив и восстанавливать их.

### Экспорт на телефоне

1. Откройте экран «Модели».
2. Нажмите **«Бэкап»** — выберите место сохранения.
3. Получите файл вида `llm_translate_backup_<timestamp>.zip`.

### Импорт на телефоне

1. Скопируйте архив на телефон (по USB, облаку, Bluetooth и т.д.).
2. Откройте экран «Модели» → **«Восстановить»**.
3. Выберите архив. Модели и настройки будут восстановлены.

### Подготовка архива на ПК

Если нужно передать модели с ПК на телефон (или наоборот), создайте архив вручную.

**Структура архива:**

```
llm_translate_backup.zip
├── manifest.json          # метаданные моделей (обязательно)
├── settings.json          # настройки (опционально)
└── models/                # файлы моделей
    ├── GGUF/
    │   └── hy-mt2-1.8b-q4_k_m.gguf
    ├── VOSK/
    │   └── vosk-ru-small/
    │       └── ...
    └── ONNX/
        └── nllb-200-600m-q4f16/
            ├── encoder_model_q4f16.onnx
            └── decoder_model_merged_q4f16.onnx
```

**manifest.json** — список моделей с путями:

```json
{
  "version": 1,
  "exportedAt": 1700000000000,
  "models": [
    {
      "id": "hy-mt2-1.8b-q4_k_m",
      "category": "TRANSLATION",
      "displayName": "Hy-MT2 1.8B (Q4_K_M)",
      "description": "Tencent Hy-MT2",
      "runtime": "GGUF",
      "sizeBytes": 1214378880,
      "ramRequiredMb": 3000,
      "languages": ["ru", "en", "ne"],
      "quality": 4,
      "speed": 5,
      "url": "",
      "urls": [],
      "sha256": null,
      "license": "Apache-2.0",
      "minAndroidSdk": 26,
      "recommendedRamMb": 6144,
      "relativePath": "models/GGUF/hy-mt2-1.8b-q4_k_m.gguf",
      "isBuiltin": true
    }
  ]
}
```

**settings.json** — активные модели и настройки:

```json
{
  "activeTranslation": "hy-mt2-1.8b-q4_k_m",
  "activeStt": "whisper-base-multilingual",
  "activeTts": "tts-system",
  "ttsEngine": "system",
  "uiLanguage": "ru",
  "theme": "dark"
}
```

**Пошагово:**

1. Скачайте нужные модели (GGUF, ONNX, Vosk).
2. Создайте папку `models/` с подкаталогами `GGUF`, `VOSK`, `ONNX`.
3. Разместите файлы моделей.
4. Создайте `manifest.json` с записью на каждую модель (поле `relativePath` — путь к файлу внутри архива).
5. При желании создайте `settings.json`.
6. Заархивируйте всё в ZIP.
7. На телефоне: «Модели» → «Восстановить» → выберите архив.

> 💡 Проще всего: сделать «Бэкап» на одном телефоне и восстановить на другом.

## NLLB (ONNX) — настройка токенизатора

Модель NLLB-200 использует токенизатор SentencePiece BPE. Для работы нужно
поместить в `app/src/main/assets/` два файла:

- `nllb_vocab.txt` — словарь, строки вида `id piece`
- `nllb_merges.txt` — правила слияния, строки вида `rank piece1 piece2`

Их можно получить из репозитория [Xenova/nllb-200-distilled-600M](https://huggingface.co/Xenova/nllb-200-distilled-600M)
или сгенерировать из `sentencepiece.bpe.model`.

> ⚠️ Имена входных/выходных тензоров ONNX-сессий (`input_ids`, `attention_mask`,
> `encoder_hidden_states` и т.д.) заданы в `OnnxLlmRuntime.kt`. При использовании
> другого экспорта ONNX проверьте и скорректируйте их под фактические имена модели.
