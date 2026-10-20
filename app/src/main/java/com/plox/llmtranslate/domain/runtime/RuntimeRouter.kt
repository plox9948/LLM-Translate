package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.domain.model.RuntimeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Роутер рантаймов: выбирает движок по типу модели.
 * UI и ViewModel работают только через этот роутер.
 */
@Singleton
class RuntimeRouter @Inject constructor(
    private val marianOnnxRuntime: MarianOnnxRuntime,
    private val indicTransOnnxRuntime: IndicTransOnnxRuntime,
    private val slimtRuntime: SlimtRuntime,
    private val sherpaSttEngine: SherpaSttEngine,
    private val piperTtsRuntime: PiperTtsRuntime,
    private val audioRecorder: AudioRecorder
) {

    /**
     * Реактивный список моделей, загруженных в ОЗУ прямо сейчас:
     * движки перевода (Marian, IndicTrans) + STT (Sherpa) + TTS (Piper).
     * Пуст, если всё разгружено; обновляется при каждой загрузке/разгрузке
     * движка.
     *
     * Контракт [releaseAll] (ревью R-N18/S-N21): при таймауте разгрузки
     * зависшего движка его пучки остаются в кэше — список может показывать
     * модель «загруженной в память» дольше releases; такой движок
     * повторно освобождается следующей [releaseAll] (или остаётся до
     * завершения процесса).
     */
    val loadedModels: Flow<List<LoadedModelInfo>> = LoadedModelsAggregator.merge(
        listOf(
            marianOnnxRuntime.loadedModels,
            indicTransOnnxRuntime.loadedModels,
            slimtRuntime.loadedModels,
            sherpaSttEngine.loadedModels,
            piperTtsRuntime.loadedModels
        )
    )

    /**
     * Движок перевода для типа модели.
     * Marian — для ONNX-моделей Marian (имортируемые opus-mt),
     * IndicTrans — для ne, slimt — для паков Firefox Translations (RU⇄EN).
     * Не-переводческие рантаймы (STT/TTS/движки синтеза) — явная sealed-ошибка
     * [RuntimeError.UnsupportedRuntime]: молчаливый fallback на Marian прятал
     * ошибочный тип модели за переводом чужим движком (ревью #79).
     */
    fun translationEngine(runtime: RuntimeType): TranslationEngine =
        translationEngineOrNull(runtime) ?: throw RuntimeError.UnsupportedRuntime(runtime.name)

    /**
     * Движок распознавания речи: sherpa-onnx — единственный STT-рантайм.
     */
    fun sttEngine(): SttEngine = sherpaSttEngine

    /**
     * Движок перевода по типу рантайма или null для не-переводческих рантаймов
     * (STT/TTS). Освобождение пучка не должно падать на ошибочной связке
     * «TRANSLATION-запись + непереводческий рантайм» (ревью: исключение из
     * [translationEngine] ломало delete/импорт таких записей).
     */
    private fun translationEngineOrNull(runtime: RuntimeType): TranslationEngine? = when (runtime) {
        RuntimeType.MARIAN_ONNX -> marianOnnxRuntime
        RuntimeType.INDICTRANS_ONNX -> indicTransOnnxRuntime
        RuntimeType.MARIAN_SLIMT -> slimtRuntime
        RuntimeType.SHERPA,
        RuntimeType.SYSTEM_TTS,
        RuntimeType.RHVOICE,
        RuntimeType.ESPEAK,
        RuntimeType.PIPER_ONNX -> null
    }

    /**
     * Убрать пучки удалённой модели из кэшей движков её категории (issue #40):
     * после удаления файлов запись в LRU-кэше с тем же id продолжала
     * «показывать» модель загруженной, а повторная загрузка того же id
     * молча использовала старый нативный handle. Под мьютексом движка
     * дожидается окончания активного инференса; Piper TTS — тот же паттерн
     * в [PiperTtsRuntime.releaseModel] (ревью #51: ранее TTS был no-op, и
     * удалённая/перескачанная Piper-модель оставалась в движке со
     * stale-нативным OfflineTts); встроенные системные TTS-модели в движке
     * не загружены — no-op по несовпадению id.
     *
     * TRANSLATION-запись с непереводческим рантаймом (битая связка) — warning
     * и no-op вместо [RuntimeError.UnsupportedRuntime]: очистка и промоут не
     * должны обрываться из-за записи, в кэше которой и так нет движка.
     */
    suspend fun releaseModel(model: InstalledModel) {
        when (model.category) {
            ModelCategory.TRANSLATION -> {
                val engine = translationEngineOrNull(model.runtime)
                if (engine == null) {
                    Timber.w(
                        "Модель ${model.id}: рантайм ${model.runtime.name} не переводческий — " +
                            "освобождение движка пропущено"
                    )
                } else {
                    engine.releaseModel(model.id)
                }
            }
            ModelCategory.STT -> sherpaSttEngine.releaseModel(model.id)
            ModelCategory.TTS -> piperTtsRuntime.releaseModel(model.id)
        }
    }

    /**
     * Разгрузить все рантаймы (при ON_STOP). Каждый движок под мьютексом
     * ждёт окончания активного инференса — это нормально для onStop-разгрузки,
     * но вызывать нужно вне главного потока. Последовательный вызов release()
     * разных движков безопасен: их мьютексы независимы, deadlock невозможен.
     * Запись с микрофона снимается первой: рекордер — app-scope синглтон,
     * без этого AudioRecord оставался бы захваченным при разгруженных
     * рантаймах (ревью).
     */
    suspend fun releaseAll() {
        audioRecorder.cancel()
        // Ревью: отмена внешней корутины не должна обрывать обход — каждый
        // release выполняется под NonCancellable, сохранённая (последняя)
        // отмена всплывает после завершения цикла.
        var deferredCancellation: CancellationException? = null
        releaseSafely("Marian") { marianOnnxRuntime.release() }?.let { deferredCancellation = it }
        releaseSafely("IndicTrans") { indicTransOnnxRuntime.release() }?.let { deferredCancellation = it }
        releaseSafely("slimt") { slimtRuntime.release() }?.let { deferredCancellation = it }
        releaseSafely("sherpa") { sherpaSttEngine.release() }?.let { deferredCancellation = it }
        // Ревью #83: Piper release() стал suspend и сериализован со своими
        // load/speak мьютексом движка — общий контур [releaseSafely]
        // (NonCancellable + таймаут + try/catch), отдельного try/catch не нужны.
        releaseSafely("Piper") { piperTtsRuntime.release() }?.let { deferredCancellation = it }
        deferredCancellation?.let { throw it }
    }

    /**
     * Один release() движка под таймаутом и try/catch (ревью): зависший
     * нативный инференс, удерживающий мьютекс движка, не должен блокировать
     * разгрузку остальных рантаймов — по образцу ограниченного дренажа
     * SlimtRuntime. Ошибка/таймаут одного движка логируются, остальные
     * двигаются безусловно; отмена внешней корутины не обрывает цикл
     * [releaseAll]: тело под NonCancellable, отмена возвращается вызывающему
     * коду и всплывает после обхода всех движков.
     * @return сохранённая отмена внешней корутины или null.
     */
    private suspend fun releaseSafely(label: String, release: suspend () -> Unit): CancellationException? {
        return try {
            withContext(NonCancellable) {
                val completed = withTimeoutOrNull(RELEASE_TIMEOUT_MS) { release() }
                if (completed == null) {
                    // Явная пометка (ревью R-N18/S-N21): движок остаётся
                    // кэшированным — UI «Загружено в память» честно вплоть
                    // до следующего releaseAll/завершения процесса.
                    Timber.w(
                        "Разгрузка рантайма $label не завершилась за ${RELEASE_TIMEOUT_MS} мс — " +
                            "пропущена; движок не разгружен и останется в памяти " +
                            "до releaseAll/следующей попытки"
                    )
                }
            }
            null
        } catch (e: CancellationException) {
            // Внутренняя отмена сгоревшего withTimeoutOrNull уже погашена им;
            // сюда доходит только отмена внешней корутины (withContext
            // всплывает ей и после штатного тела) — сохраняем до конца цикла.
            e
        } catch (e: Exception) {
            Timber.e(e, "Ошибка разгрузки рантайма $label")
            null
        }
    }

    private companion object {
        /** Лимит ожидания release() одного движка (по образцу дренажа slimt). */
        const val RELEASE_TIMEOUT_MS = 10_000L
    }
}
