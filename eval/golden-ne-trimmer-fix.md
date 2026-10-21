# Девайс-верификация фикса RepetitionTrimmer (issue #91) — golden NE прогоны и eval-харнесc

**Повторная проверка** после доработки `maxSourceRepeats` (плотные пунктуационные границы токенов; unit-тесты 749 зелёные).

- Дата: 2026-10-21, устройство OPPO CPH2653 (Android 16 / API 36 / arm64-v8a)
- Ветка: `dev`, незакоммиченный фикс: `RepetitionTrimmer.trim(text, allowedRepeats)` +
  `maxSourceRepeats` (токенизация входа по пробельным **и пунктуационным** границам: данда ॥/।,
  `.`/`!`/`?`/`…`, `,`/`;`/`:`) + source-aware ассерты в androidTest + проброс разрешения в
  `IndicTransOnnxRuntime.translateSingleChunk/translateOnce`.
- Сборка: `assembleDebug assembleDebugAndroidTest` (JDK 17) → `adb install -r` / `-r -t`
  (без `connectedDebugAndroidTest`, модели в `/Android/data/.../files/models` не тронуты:
  `indictrans2-en-indic-int8`, `indictrans2-indic-en-int8`, `firefox-bergamot-ru/en`).
- Каждый метод — отдельно: `adb shell am instrument -w -e class <класс#метод>`;
  `setprop log.tag.IndicTransOnnxRuntime D` однократно; `adb logcat -c` перед каждой группой,
  дамп после. Выводы тестов (println) — через logcat `System.out` (штатный канал инструмента).
- Харнесc `GoldRuNeEvalTest` в этом прогоне **не перепрогонялся**: trim-правки на него не влияют,
  отчёт уже побайтово совпал с `eval/gold-ne-after.md` (замер после #90).

## Статусы прогонов (6 перепрогонявшихся)

| Прогон | Прошлая итерация #91 | Эта итерация | Время |
| --- | --- | --- | --- |
| `GoldenCorpusNeTest#goldenCorpusEn2Ne` | OK | **OK** | 5,168 c |
| `GoldenCorpusNeTest#goldenCorpusNe2En` | OK | **OK** | 4,27 c |
| `GoldenCorpusNeTest#longNepaliParagraphChunking` | **FAIL** (ассерт, разрешение 2 вместо 3) | **OK** | 6,154 c |
| `GoldenCorpusNeTest#chainRegressionLake` | OK | **OK** | 3,268 c |
| `InferenceSmokeTest#indicTransEnNeWelcomeTranslation` | OK | **OK** | 3,227 c |
| `InferenceSmokeTest#indicTransIndicEnDuplication` | OK | **OK** | 3,138 c |

Инференс-часть long-теста healthy: `вход разбит на 60 чанков (budget=250)`,
60/60 «проход 1 вырожден=false», 0 ретраев, `result.isSuccess=true`, движок сохранил
легитимный ×3 повтор блока в итоговой склейке (перечанковая склейка триммером не обрабатывается).
Ассерт `assertNoDegeneration` теперь получает `allowedRepeats = maxSourceRepeats(paragraph) = 3`
(токенизация входа по пунктуационным стыкам распознаёт полные копии на беспробельной
данда-склейке `base।base।base।`) — `assertEquals(out, trim(out, 3))` выполняется, FAIL закрыт.

### Что чинит dense-пунктуационная токенизация `maxSourceRepeats`

Прежняя версия резала вход только по пробелам, и на стыках копий `слово।слово`
последнее слово копии сливалось с данда/первым словом следующей в составной токен —
пословное сравнение блоков видело максимум 2 полные копии вместо 3, и ассерт сжимал
вывод до 2 копий. Теперь границами токена считаются и пунктуационные разделители —
`maxSourceRepeats("मलाई थाहा छ।मलाई थाहा छ।मलाई थाहा छ।") = 3`
(зафиксировано unit-тестом `maxSourceRepeats treats punctuation boundaries as token separators on dense joins`).

## chrF golden-корпуса (сверка с baseline после #90, `eval/golden-ne-after.md`)

- EN→NE: **среднее 0,7680** (порог 0.30) — совпало с baseline;
- NE→EN: **среднее 0,8009** (порог 0.35) — совпало с baseline;
- построчная сверка таблиц `id | chrF | вывод` (40/40 строк из logcat `System.out`,
  нормализованные `Compare-Object -SyncWindow 0`): **байт-в-байт идентичны** baseline
  (значения chrF и тексты выводов);
- Регрессии не обнаружены: en2ne_009 («तिन घन्टा») и ne2en_020 («500») — на месте
  (ассерты кейсов проходят); CHAIN «Озеро» вывод побайтово тот же (`Lake`/`The lake.`/`ताल`/`ताल।`).

## Eval-харнесc RU⇄NE (`GoldRuNeEvalTest`)

- Не перепрогонялся в этой итерации (trim-правки на харнесc не влияют — входов с
  repetition-разрешением >1 в нём нет); предыдущий отчёт `eval/gold-ne-trimmer-fix.md`
  побайтово совпал с `eval/gold-ne-after.md` (замер после-#90): SHA-256 идентичны,
  средний round-trip **chrF-back = 0,5254** (15 фраз) — на baseline-уровне.
- Метрика golden-корпуса (0,7680 / 0,8009) и вывод харнесcа не менялись между итерациями #91.

## Logcat-маркеры (подробно в `eval/golden-ne-trimmer-fix.logcat.txt`)

Перепрогонявшиеся группы 1 (Golden: en2ne/ne2en/long/chain) и 2 (smoke):
`схлопнут повтор` = 0, `ретрай` = 0, `вырожден=true` = 0;
декод-проходов с `вырожден=false`: 102 (группа 1, из них 60 — long) + 3 (группа 2).
Группа 3 (харнесc, предыдущий прогон): те же 0/0/0 и 36 проходов — без изменений.
Короткие кейсы (одно предложение/слово, `allowance=1`): поведение побайтно как до фикса —
выводы совпадают с baseline (кейс #30 «नमस्कार» → `Hello.` без дубля, триммер не вызывался).

## Вердикт по критериям issue #91

1. **Циклы декодера подавляются** — выполнено: во всех прогонах 0 вырожденных проходов,
   0 ретраев, 0 схлопнутых повторов; выроденных циклов нет ни в golden, ни в smoke
   (как и в baseline #90 — маска no_repeat_ngram работает, фикс ничего не сломал).
2. **Легитимные повторы сохранены** — выполнено в полном объёме: long-абзац из трёх
   identical-блоков переведён, склейка сохранила все 3 копии, и source-aware ассерт
   `longNepaliParagraphChunking` теперь **OK** (после dense-пунктуационных границ
   токенизации входа `maxSourceRepeats` корректно даёт разрешение 3 даже для
   беспробельной данда-склейки копий); в остальные прогоны входов с повторами не было,
   выводы байт-идентичны baseline (allowance=1 эквивалентен прежнему поведению).
3. **Метрики не хуже baseline** — выполнено побайтово: golden EN→NE 0,7680 / NE→EN 0,8009
   (40/40 строк идентичны), харнесc chrF-back 0,5254 (отчёт идентичен hash-в-hash).

**Итог: 6/6 перепрогонявшихся прогонов OK; метрики и декод-поведение — побайтово на уровне
baseline после #90; целевой `longNepaliParagraphChunking` закрыт (FAIL → OK).
Девайс-верификация issue #91 завершена, все три критерия выполнены.**

## Созданные файлы

- `eval/golden-ne-trimmer-fix.md` — этот отчёт;
- `eval/golden-ne-trimmer-fix.logcat.txt` — извлечённые маркеры (сырые logcat-дампы удалены);
- `eval/gold-ne-trimmer-fix.md` — отчёт харнеса предыдущей итерации с устройства
  (побайтово = `gold-ne-after.md`; харнесc в этой итерации не перепрогонялся).
