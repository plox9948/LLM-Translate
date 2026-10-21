# GoldenCorpusNeTest — прогон ДО фикса IndicTrans (до 2fd4ec1, issue #90)

- Состояние кода: `dev` @ `2fd4ec1~1` (`6c2868b`) — откачены только `IndicTransOnnxRuntime.kt` и `ChunkPlanner.kt` (`git checkout 2fd4ec1~1 -- ...`); тестовый класс и активы без изменений
- Устройство: OPPO CPH2653 (Android 16 / API 36 / arm64-v8a)
- Модели (на месте, не переустанавливались): `indictrans2-en-indic-int8`, `indictrans2-indic-en-int8`, `firefox-bergamot-ru-en` (`MARIAN_SLIMT`)
- Прогон каждого метода отдельно: `adb shell am instrument -w -e class com.plox.llmtranslate.GoldenCorpusNeTest#<метод>`; APK: `assembleDebug assembleDebugAndroidTest` + `adb install -r` / `-r -t` (без `connectedDebugAndroidTest`)
- Динамика: `setprop log.tag.IndicTransOnnxRuntime D`, `logcat` до/после каждого прогона (`-c` + `-d`)

## Итоговый статус тестов

| Тест | Статус | Время |
|---|---|---|
| goldenCorpusEn2Ne | **OK** | 5,129 c |
| goldenCorpusNe2En | **OK** | 4,225 c |
| longNepaliParagraphChunking | **OK** | 6,87 c |
| chainRegressionLake | **OK** | 3,326 c |

В дофиксном состоянии все 4 теста проходят (в отличие от after-прогона, где `longNepaliParagraphChunking` падал на ассерте свойства — см. `golden-ne-after.md`).

## chrF EN→NE (порог среднего 0.30)

chrF EN->NE: id | chrF | вывод

| id | chrF | вывод |
|---|---|---|
| en2ne_001 | 0,7018 | नमस्कार, तपाईँ कस्तो हुनुहुन्छ? |
| en2ne_002 | 0,8298 | म भोलि काठमाडौँ जाँदैछु। |
| en2ne_003 | 0,5469 | यो पुस्तक राम्रो छ। |
| en2ne_004 | 0,8576 | उनी हरेक दिन स्कुल जान्छिन्। |
| en2ne_005 | 1,0000 | मलाई थाहा छैन। |
| en2ne_006 | 0,6875 | कृपया मलाई सहयोग गर्नुहोस्। |
| en2ne_007 | 1,0000 | अस्पताल कहाँ छ? |
| en2ne_008 | 0,5115 | यो कति खर्च हुन्छ? |
| en2ne_009 | 0,8575 | मस्कोबाट दिल्ली जाने हाम्रो उडान ढिलाइ भएको थियो, त्यसैले हामी काठमाडौँ तिन घन्टा ढिलो आइपुग्नेछौँ। |
| en2ne_010 | 1,0000 | धन्यवाद। |
| en2ne_011 | 0,6460 | शुभ प्रभात। |
| en2ne_012 | 0,7200 | मेरो नाम रवि हो। |
| en2ne_013 | 1,0000 | पानी चिसो छ। |
| en2ne_014 | 0,8150 | मलाई डाक्टर चाहिन्छ। |
| en2ne_015 | 1,0000 | बस स्टेसन कहाँ छ? |
| en2ne_016 | 0,5423 | मलाई एक कप चिया चाहिन्छ। |
| en2ne_017 | 0,5752 | बिल 1500 रुपैयाँ छ। |
| en2ne_018 | 0,7286 | बिहानदेखि नै मेरो टाउको दुखिरहेको छ। |
| en2ne_019 | 0,4159 | के यो सिट निःशुल्क छ? |
| en2ne_020 | 0,9249 | माफ गर्नुहोस्, के तपाईँ बिस्तारै बोल्न सक्नुहुन्छ? |

Среднее: **0,7680** ≥ порога 0.30 — тест прошёл. Значения совпадают с after- прогоном байт-в-байт (корпусные короткие предложения до фикса и не вырождались).

## chrF NE→EN (порог среднего 0.35)

chrF NE->EN: id | chrF | вывод

| id | chrF | вывод |
|---|---|---|
| ne2en_001 | 0,7291 | Hi, how are you? |
| ne2en_002 | 0,5302 | I don 't know. |
| ne2en_003 | 0,7165 | How much does it cost? |
| ne2en_004 | 1,0000 | Where is the hospital? |
| ne2en_005 | 1,0000 | Please help me. |
| ne2en_006 | 1,0000 | I am going to Kathmandu tomorrow. |
| ne2en_007 | 1,0000 | Thank you. |
| ne2en_008 | 0,4651 | I want water. |
| ne2en_009 | 1,0000 | This book is good. |
| ne2en_010 | 1,0000 | She goes to school every day. |
| ne2en_011 | 0,7166 | The weather is good today. |
| ne2en_012 | 1,0000 | My name is Sita. |
| ne2en_013 | 1,0000 | Yes, I am coming. |
| ne2en_014 | 0,3099 | No, I don 't know. |
| ne2en_015 | 1,0000 | My head hurts. |
| ne2en_016 | 0,9188 | where is the bus station |
| ne2en_017 | 0,4386 | This seat is vacant. |
| ne2en_018 | 0,8331 | Excuse me, can you speak softly? |
| ne2en_019 | 0,8171 | Do you know that? |
| ne2en_020 | 0,5440 | It is worth 500 rupees. |

Среднее: **0,8009** ≥ порога 0.35 — тест прошёл. Совпадает с after-прогоном.

### Худшие chrF (топ-3 по обеим таблицам)

| id | chrF | вывод | комментарий |
|---|---|---|---|
| ne2en_014 | 0,3099 | `No, I don 't know.` | артефакт детокенизации «don 't» (ref: «No, I do not know.») — идентичен after-прогону |
| en2ne_019 | 0,4159 | `के यो सिट निःशुल्क छ?` | синонимичный валидный перевод против lexical ref |
| ne2en_017 | 0,4386 | `This seat is vacant.` | синоним «vacant» vs ref («free/empty») |

Низкие баллы не связаны с вырождением декодера — те же кейсы, что и в after-прогоне.

## longNepaliParagraphChunking — OK (но с вырождением в основном проходе)

До фикса `ChunkPlanner` резал НЕ строго по предложениям: `IndicTrans: вход разбит на 2 чанков (budget=250)` (против 60 предложных чанков after). В чанке 1 основной проход вырожден (зацикливание `? ? ? …` до упора в лимит):

```
IndicTrans: проход (ngram=0): 257 токенов, eosReached=false, hitMaxLength=true,
  trimmed: [Hi how are you ? ? ? ? ? … 256 × «?»]
IndicTrans: проход 1 вырожден=true (eosReached=false, hitMaxLength=true)
IndicTrans: проход (ngram=3): 108 токенов, eosReached=true, hitMaxLength=false,
  trimmed: [Hi how are you ? ? ? I don 't know how much . How much is it ? ? Where is the hospital ? ? …]
IndicTrans: ретрай (ngram=3) вырожден=false (eosReached=true, hitMaxLength=false)
```

Чанк 2 прошёл чисто (`проход 1 вырожден=false`). Тест OK только потому, что legacy-ретрай (retry-проход с ngram-штрафом) спас вырожденный чанок: ассерт `assertNoDegeneration` увидел уже валидный склеенный текст без точного ×3 повтора (после postprocess вывод — «Hi how are you??? … Can you speak slowly??» + второй чанк; дедуп-схлопывания `RepetitionTrimmer` в рантайме не было, «схлопнут повтор» — 0).

Смысловые потери в чанке 1 при этом заметны: часть предложений склейки слита/проглочена («A.S.» артефакты, «No , I am not .» вместо полной фразы) — качество длинного входа до фикса хуже, чем после строгого почанкового разбиения.

## chainRegressionLake («Озеро») — OK

```
CHAIN «Озеро» → EN: «Lake»
CHAIN «Озеро.» → EN: «The lake.»
CHAIN «Озеро» → NE: «ताल»
CHAIN «Озеро.» → NE: «ताल।»
```

Идентично after-прогону, вырождения нет.

## logcat-маркеры IndicTransOnnxRuntime (по каждому прогону)

Формат дофиксных логов: `проход 1 вырожден=…`, `ретрай (ngram=3) вырожден=…` (после фикса — единый `проход (ngram=3)`).

| Прогон | «схлопнут повтор» | «ретрай» | «вырожден=true» | «вырожден=false» |
|---|---|---|---|---|
| goldenCorpusEn2Ne | 0 | 0 | 0 | 20 |
| goldenCorpusNe2En | 0 | 0 | 0 | 20 |
| longNepaliParagraphChunking | 0 | 1 | 1 | 2 |
| chainRegressionLake | 0 | 0 | 0 | 2 |

Итого за прогоны: 45 decode-проходов, из них 1 вырожден (`hitMaxLength=true`, зацикленные «?»), 1 спасён retry-проходом с ngram=3. Корпусные 40 предложений и «Озеро» не вырождались и без `no_repeat_ngram=3` в основном проходе; различие before/after проявляется только на длинном входе (2 чанка с вырождением+ретраем вместо 60 чистых почанковых проходов).

Релевантные строки логов сохранены в `eval/golden-ne-before.logcat.txt`.

Это eval-отчёт: код не менялся (только временный откат двух runtime-файлов для замера).
