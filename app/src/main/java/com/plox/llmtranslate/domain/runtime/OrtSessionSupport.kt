package com.plox.llmtranslate.domain.runtime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.plox.llmtranslate.domain.model.RuntimeError
import java.io.File
import java.nio.LongBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber

// Треды ONNX-сессий переводческих моделей (Marian и IndicTrans — одинаково).
private const val INTRA_OP_THREADS = 4
private const val INTER_OP_THREADS = 1

/**
 * Создать сессии encoder/decoder/decoder_past переводческой модели
 * с единой настройкой потоков (общий путь загрузки Marian и IndicTrans).
 *
 * SessionOptions закрывается сразу после создания всех сессий, в том числе
 * при исключении на любой из createSession (иначе утечка native-памяти ORT).
 * Между createSession выдержана граница отмены: само создание сессии
 * (десятки секунд) не прерываемо, но «Отмена» обязана срабатывать между
 * ними. При сбое на любой сессии уже созданные закрываются до проброса
 * исключения наверх.
 */
internal suspend fun createEncoderDecoderSessions(
    label: String,
    ortEnv: OrtEnvironment,
    encoderFile: File,
    decoderFile: File,
    decoderPastFile: File,
): Triple<OrtSession, OrtSession, OrtSession> {
    val created = mutableListOf<OrtSession>()
    try {
        val sessionOpts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(INTRA_OP_THREADS)
            setInterOpNumThreads(INTER_OP_THREADS)
        }
        try {
            for (file in listOf(encoderFile, decoderFile, decoderPastFile)) {
                currentCoroutineContext().ensureActive()
                created += ortEnv.createSession(file.absolutePath, sessionOpts)
            }
        } finally {
            sessionOpts.close()
        }
    } catch (e: Exception) {
        closePartiallyCreated(label, *created.toTypedArray())
        throw e
    }
    return Triple(created[0], created[1], created[2])
}

/**
 * Закрыть сессии незавершённой загрузки (отмена/ошибка) — пучок в кэш
 * ещё не положен, другие пучки кэша не затрагиваются.
 */
internal fun closePartiallyCreated(label: String, vararg sessions: OrtSession?) {
    try {
        sessions.filterNotNull().forEach { it.close() }
    } catch (e: Exception) {
        Timber.e(e, "Ошибка при освобождении частично созданных $label-сессий")
    }
}

/**
 * Число слоёв encoder-decoder переводческой модели по именам выходов
 * декодера: все выходы, кроме logits, — четвёрки
 * `present.{layer}.{decoder,encoder}.{key,value}` (общая формула Marian
 * и IndicTrans).
 */
internal fun numLayersFromDecoderOutputs(decoder: OrtSession): Int =
    (decoder.outputNames.size - 1) / 4

/**
 * Закрыть сессии активного пучка модельного движка при его разгрузке
 * (ошибки — только в лог); общий release() пучков Marian и IndicTrans.
 */
internal fun closeBundleSessions(label: String, vararg sessions: OrtSession) {
    try {
        sessions.forEach { it.close() }
    } catch (e: Exception) {
        Timber.e(e, "Ошибка при разгрузке $label")
    }
}

/**
 * Общий catch-каркас загрузки ONNX-модели перевода (Marian, IndicTrans):
 * отмена загрузки кнопкой «Отмена» — не ошибка: освобождаем уже созданные
 * сессии этого пучка ([closeCreatedSessions]; пучки других направлений
 * остаются в кэше) и всплываем выше, не конвертируя отмену в LoadFailed;
 * прочие исключения — явный [RuntimeError.LoadFailed]. Тексты логов
 * строятся из [label]/[engineTag] байт-в-байт как в прежних ensureLoaded
 * движков. [block] возвращает null при успехе или RuntimeError раннего
 * возврата (проверки файлов/токенизаторов — остаются внутри block).
 */
internal inline fun guardOrtModelLoad(
    label: String,
    engineTag: String,
    path: String,
    closeCreatedSessions: () -> Unit,
    block: () -> RuntimeError?
): RuntimeError? = try {
    block()
} catch (e: CancellationException) {
    Timber.d("Загрузка $label-модели отменена: $path")
    closeCreatedSessions()
    throw e
} catch (e: Exception) {
    Timber.e(e, "Не удалось загрузить $label-модель $path")
    closeCreatedSessions()
    RuntimeError.LoadFailed(engineTag, e.message ?: "Неизвестная ошибка")
}

/**
 * Прогнать энкодер переводческой модели на `input_ids`/`attention_mask`
 * (общий путь Marian и IndicTrans) с гарантией закрытия входных тензоров:
 * закрываем их сразу после run — на любом пути. Если второй createTensor
 * упадёт — первый закрываем сразу, иначе его нативная память утечёт мимо
 * внешнего finally. Результат [OrtSession.Result] закрывает вызывающий.
 */
internal fun runEncoder(
    encoder: OrtSession,
    ortEnv: OrtEnvironment,
    inputIds: LongArray,
    attentionMask: LongArray,
): OrtSession.Result {
    val encInputIds = OnnxTensor.createTensor(
        ortEnv, LongBuffer.wrap(inputIds), longArrayOf(1L, inputIds.size.toLong())
    )
    val encAttnMask = try {
        OnnxTensor.createTensor(
            ortEnv, LongBuffer.wrap(attentionMask),
            longArrayOf(1L, attentionMask.size.toLong())
        )
    } catch (e: Exception) {
        encInputIds.close()
        throw e
    }
    try {
        return encoder.run(
            mapOf("input_ids" to encInputIds, "attention_mask" to encAttnMask)
        )
    } finally {
        encInputIds.close()
        encAttnMask.close()
    }
}
