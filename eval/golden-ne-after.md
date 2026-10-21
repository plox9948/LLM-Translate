# GoldenCorpusNeTest — прогон ПОСЛЕ фикса IndicTrans (issue #90)

- Состояние кода: `dev` @ `2fd4ec1` «fix(runtime): no_repeat_ngram=3 основным проходом IndicTrans + чанкинг строго по предложениям (issue #90)»
- Устройство: OPPO CPH2653 (Android 16 / API 36 / arm64-v8a)
- Модели (на месте, не переустанавливались): `indictrans2-en-indic-int8`, `indictrans2-indic-en-int8`, `firefox-bergamot-ru-en` (`MARIAN_SLIMT`)
- Прогон каждого метода отдельно: `adb shell am instrument -w -e class com.plox.llmtranslate.GoldenCorpusNeTest#<метод>`; APK: `assembleDebug assembleDebugAndroidTest` (UP-TO-DATE @ 2fd4ec1) + `adb install -r` / `-r -t` (без `connectedDebugAndroidTest`)
- Динамика: `setprop log.tag.IndicTransOnnxRuntime D`, logcat после каждого прогона

## Итоговый статус тестов

| Тест | Статус | Время |
|---|---|---|
| goldenCorpusEn2Ne | **OK** | 5,165 c |
| goldenCorpusNe2En | **OK** | 4,252 c (повторный прогон для logcat — 4,274 c первый) |
| longNepaliParagraphChunking | **FAIL** (ассерт) | 6,488 c |
| chainRegressionLake | **OK** | 3,309 c |

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

Среднее: **0,7680** ≥ порога 0.30 — тест прошёл. Регрессия en2ne_009 («часы» घन्टा) — в выводе есть («तिन घन्टा»).

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

Среднее: **0,8009** ≥ порога 0.35 — тест прошёл. Регрессия ne2en_020 («500») — в выводе есть.

### Худшие chrF (топ-3 по обеим таблицам)

| id | chrF | вывод | комментарий |
|---|---|---|---|
| ne2en_014 | 0,3099 | `No, I don 't know.` | артефакт детокенизации «don 't» (ref: `No, I do not know.` / «don't») — chrF на символах падает из-за разрыва апострофа |
| en2ne_019 | 0,4159 | `के यो सिट निःशुल्क छ?` | дословно-синонимичный вариант («सिट निःशुल्क»vs lexical ref) — семантически верен, chrF низкий из-за расхождения морфем |
| ne2en_017 | 0,4386 | `This seat is vacant.` | синоним «vacant» vs ref («free/empty») — перевод корректен, chrF penalized за лексику |

Общей закономерности вырождения нет — низкие баллы у кейсов с синонимичными, но валидными переводами и у артефакта детокенизации `don 't`.

## longNepaliParagraphChunking — FAIL

Перевод длинного абзаца (×3 склейка ne-предложений корпуса) **удался**: лог `IndicTrans: вход разбит на 60 чанков (budget=250)` — чанкинг строго по предложениям отработал, `result.isSuccess == true`, декодер не вырожден (60/60 `вырожден=false`, 0 ретраев).

Падает попунктный ассерт свойства вывода `assertNoDegeneration` (GoldenCorpusNeTest.kt:131):

```
org.junit.ComparisonFailure: Остался повтор в выводе: «Hi, how are you? I don 't know. … It is worth 500 rupees. Hi, how are you? … »
expected:<...is worth 500 rupees.[ Hi, how are you? … 3-й повтор … rupees.]> but was:<...is worth 500 rupees.[]>
at com.plox.llmtranslate.GoldenCorpusNeTest.assertNoDegeneration(GoldenCorpusNeTest.kt:131)
at com.plox.llmtranslate.GoldenCorpusNeTest.longNepaliParagraphChunking(GoldenCorpusNeTest.kt:287)
```

Суть: сам тестовый абзац намеренно повторяет один и тот же набор предложений трижды; склейка 60 чанков даёт легитимный ×3 повтор, который `RepetitionTrimmer.trim()` схлопывает до одной копии — ассерт «вывод == trim(вывод)» не выполняется. Это конфликт свойства-ассерта с конструкцией входа (повтор в исходном тексте), а не вырождение декодера.

Отмеченный пробел в `don 't` в длинном выводе — тот же артефакт детокенизации апострофов.

## chainRegressionLake («Озеро») — OK

Регрессия скриншот-бага «Озеро» (RU→EN slimt → EN→NE IndicTrans2):

```
CHAIN «Озеро» → EN: «Lake»
CHAIN «Озеро.» → EN: «The lake.»
CHAIN «Озеро» → NE: «ताल»
CHAIN «Озеро.» → NE: «ताल।»
```

Оба вывода короткие однословные с маркером «ताल», вырождения нет.

## logcat-маркеры IndicTransOnnxRuntime (по каждому прогону)

| Прогон | «схлопнут повтор» | «ретрай…» | «проход 1 вырожден=true» | «вырожден=false» |
|---|---|---|---|---|
| goldenCorpusEn2Ne | 0 | 0 | 0 | 20 |
| goldenCorpusNe2En | 0 | 0 | 0 | 20 |
| longNepaliParagraphChunking | 0 | 0 | 0 | 60 |
| chainRegressionLake | 0 | 0 | 0 | 2 |

Все прогоны: ни одного retry/ретрай-прохода (`no_repeat_ngram=3` в основном проходе хватало); 102 decode-прохода — не вырождены (`eosReached=true, hitMaxLength=false`), ни одной коррекции `RepetitionTrimmer` внутри рантайма («схлопнут повтор» — 0).

Это eval-отчёт: упавший ассерт `longNepaliParagraphChunking` зафиксирован как есть, код не менялся.
