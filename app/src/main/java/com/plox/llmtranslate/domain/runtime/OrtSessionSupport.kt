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
 * ещё не положен, другие пучки кэша не затрагиваются. Закрытие ПОШТУЧНОЕ
 * (ревью #83): исключение от close() одной сессии не должно терять
 * остальные — каждая утечёт в native-память ORT. Параметр — [AutoCloseable]
 * (OrtSession реализует его), чтобы путь тестировался фейками без ORT.
 */
internal fun closePartiallyCreated(label: String, vararg sessions: AutoCloseable?) {
    sessions.filterNotNull().forEach { session ->
        try {
            session.close()
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при освобождении частично созданных $label-сессий")
        }
    }
}

/**
 * Число слоёв encoder-decoder переводческой модели по именам выходов
 * декодера: все выходы, кроме logits, — четвёрки
 * `present.{layer}.{decoder,encoder}.{key,value}` (общая формула Marian
 * и IndicTrans).
 *
 * Аномальная структура выходов (хвост не кратен четвёркам или слоёв ноль) —
 * явное исключение: вызывающий ensureLoaded оборачивает его в
 * [RuntimeError.LoadFailed] ([guardModelLoad]); прежняя целочисленная
 * формула молча давала 0/неверное число слоёв, и инференс падал на пустых
 * past_key_values (ревью).
 */
internal fun numLayersFromDecoderOutputs(decoder: OrtSession): Int {
    val pastOutputs = decoder.outputNames.size - 1
    val layers = pastOutputs / 4
    if (pastOutputs % 4 != 0 || layers <= 0) {
        throw IllegalStateException(
            "Некорректная структура выходов декодера: ${decoder.outputNames.size} " +
                "выходов (ожидались logits + четвёрки past_key_values)"
        )
    }
    return layers
}

/**
 * Закрыть сессии активного пучка модельного движка при его разгрузке
 * (ошибки — только в лог); общий release() пучков Marian и IndicTrans.
 * Закрытие ПОШТУЧНОЕ (ревью #83): бой одной сессии не обрывает закрытие
 * остальных (утечка нативной памяти ORT). Параметр — [AutoCloseable]
 * (OrtSession реализует его), чтобы путь тестировался фейками без ORT.
 */
internal fun closeBundleSessions(label: String, vararg sessions: AutoCloseable) {
    sessions.forEach { session ->
        try {
            session.close()
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при разгрузке $label")
        }
    }
}

/**
 * Общий catch-каркас загрузки модели движка (Marian, IndicTrans — ONNX-сессии;
 * sherpa STT — распознаватель строится одним вызовом, частично созданных
 * ресурсов нет): отмена загрузки кнопкой «Отмена» — не ошибка: освобождаем
 * уже созданные ресурсы этого пучка ([closePartial]; пучки других направлений
 * остаются в кэше) и всплываем выше, не конвертируя отмену в LoadFailed;
 * прочие исключения и ошибки нативного слоя ([Error]: UnsatisfiedLinkError
 * из статик-инициализации OrtEnvironment) — явный [RuntimeError.LoadFailed]
 * (ревью #83). Тексты логов
 * строятся из [label]/[engineTag] байт-в-байт как в прежних ensureLoaded
 * движков. [block] возвращает null при успехе или RuntimeError раннего
 * возврата (проверки файлов/токенизаторов — остаются внутри block).
 */
internal inline fun guardModelLoad(
    label: String,
    engineTag: String,
    path: String,
    closePartial: () -> Unit,
    block: () -> RuntimeError?
): RuntimeError? = try {
    block()
} catch (e: CancellationException) {
    Timber.d("Загрузка $label-модели отменена: $path")
    closePartial()
    throw e
} catch (e: Exception) {
    Timber.e(e, "Не удалось загрузить $label-модель $path")
    closePartial()
    RuntimeError.LoadFailed(engineTag, e.message ?: "Неизвестная ошибка")
} catch (e: Error) {
    // UnsatisfiedLinkError / NoClassDefFoundError / ExceptionInInitializerError
    // из статик-инициализации нативного слоя ORT (OrtEnvironment) — это Error,
    // а не Exception: без этой ветки они роняли процесс вместо явной
    // LoadFailed (ревью #83, по образцу SlimtRuntime, ревью #79).
    // CancellationException (Exception) перехвачен выше и не глотается.
    Timber.e(e, "Критическая ошибка нативного слоя $label при загрузке $path")
    closePartial()
    RuntimeError.LoadFailed(engineTag, e.message ?: "Ошибка нативной библиотеки")
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

/**
 * Собрать past-входы декодера `past_key_values.<layer>.<suffix>` из
 * past-выходов `present.<layer>.<suffix>` с fail-fast (ревью #83):
 * отсутствующий вход — рассинхрон имён графа, его молчаливый пропуск
 * маскировал рассинхрон: инференс падал невнязой ORT-ошибкой посреди генерации вместо явного
 * [IllegalStateException] у источника. [tensorOf] — извлечение тензора по
 * имени (в рантаймах — [ortTensor], в тестах — фейковый коллбэк); тип
 * значения обобщён, чтобы функция тестировалась без ONNX.
 */
internal fun <V> collectPastInputs(
    numLayers: Int,
    suffixes: List<String>,
    tensorOf: (name: String) -> V?,
): Map<String, V> {
    val inputs = LinkedHashMap<String, V>(numLayers * suffixes.size)
    for (layer in 0 until numLayers) {
        for (suffix in suffixes) {
            val outName = "present.$layer.$suffix"
            inputs["past_key_values.$layer.$suffix"] =
                tensorOf(outName) ?: error("Нет входа $outName")
        }
    }
    return inputs
}

/**
 * Оценка native resident-объёма ONNX-пучка: размеры файлов графов плюс
 * внешние файлы весов (`<graph-base>.*`, включая `<graph>.onnx.data`,
 * которые ORT подгружает рядом с моделью). Только учёт в байтовом бюджете
 * кэша движков (ревью #83) — верхняя оценка, точный профиль ОЗУ не нужен.
 */
internal fun estimateGraphBytes(graphs: List<File>): Long {
    val siblings = graphs.firstNotNullOfOrNull { it.parentFile }?.listFiles()
        ?: emptyArray()
    var total = 0L
    for (graph in graphs) {
        total += graph.length()
        val base = graph.name.removeSuffix(".onnx")
        total += siblings.filter { it.isFile && it != graph && it.name.startsWith("$base.") }
            .sumOf { it.length() }
    }
    return total
}
