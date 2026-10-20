package com.plox.llmtranslate.domain.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тесты гейта поколений озвучивания Piper TTS (ревью #83):
 * снятие прежней фразы не должно останавливать новую, резервированную ПОСЛЕ
 * stop-вызова (прежний boolean-флаг в этой гонке «успешно» глушил новую
 * фразу), а stop() обязан гасить поколение, активное на момент вызова.
 * stopThrough (замечание 2 ревью #83) — путь отмены: снимает своё поколение
 * и ранние, не задевая резервации новых фраз.
 */
class PiperStopGateTest {

    @Test
    fun `stop disables only generations active at call time`() {
        val gate = PiperStopGate()
        val first = gate.reserve()
        assertFalse(gate.isStopped(first))

        gate.stopAll()
        assertTrue(gate.isStopped(first))
    }

    @Test
    fun `phrase reserved after stop is not silent-killed by old cancellation`() {
        // Гонка из аудита: stop() отменённой прежней фразы (catch/поздний stop
        // из UI) приходит ПОСЛЕ старта новой фразы — новое поколение не
        // останавливается, потому что резервировано позже stopAll.
        val gate = PiperStopGate()
        val old = gate.reserve()
        gate.stopAll() // снятие прежней фразы

        val fresh = gate.reserve() // новая фраза стартует после stop
        assertFalse(gate.isStopped(fresh))
        assertTrue(gate.isStopped(old))
    }

    @Test
    fun `stop after new reservation disables the new playing phrase`() {
        // Осознанная семантика: пользовательский stop() гасит то, что играет
        // в момент вызова, — в том числе свежезарезервированное поколение.
        val gate = PiperStopGate()
        gate.reserve()
        val playing = gate.reserve()
        gate.stopAll()
        assertTrue(gate.isStopped(playing))
    }

    @Test
    fun `stopAll is idempotent and generations are monotonic`() {
        val gate = PiperStopGate()
        val a = gate.reserve()
        val b = gate.reserve()
        assertTrue(b > a)
        gate.stopAll()
        gate.stopAll()
        assertTrue(gate.isStopped(a))
        assertTrue(gate.isStopped(b))
        val c = gate.reserve()
        assertFalse(gate.isStopped(c))
    }

    // ── замечание 2 ревью #83: поgeneration-остановка на пути отмены ──

    @Test
    fun `stopThrough does not silence generation reserved after old cancel`() {
        // Штатный сценарий координатора: новая фраза резервируется, затем
        // speakJob.cancel() старой фразы приводит к stopThrough(eё поколение)
        // в catch. Прежний stopAll() снимал и поколение новой фразы —
        // озвучка выходила тихой.
        val gate = PiperStopGate()
        val old = gate.reserve()
        val fresh = gate.reserve()
        gate.stopThrough(old)
        assertTrue(gate.isStopped(old))
        assertFalse(gate.isStopped(fresh))
    }

    @Test
    fun `stopThrough removes own and earlier generations only`() {
        val gate = PiperStopGate()
        val a = gate.reserve()
        val b = gate.reserve()
        val c = gate.reserve()
        gate.stopThrough(b)
        assertTrue(gate.isStopped(a))
        assertTrue(gate.isStopped(b))
        assertFalse(gate.isStopped(c))
        // Повторное снятие более раннего поколения не откатывает границу.
        gate.stopThrough(a)
        assertTrue(gate.isStopped(a))
        assertTrue(gate.isStopped(b))
        assertFalse(gate.isStopped(c))
    }

    @Test
    fun `stopAll after stopThrough also removes later reservations`() {
        // stopThrough не мешает явному пользовательскому stopAll погасить всё.
        val gate = PiperStopGate()
        val old = gate.reserve()
        gate.stopThrough(old)
        val fresh = gate.reserve()
        gate.stopAll()
        assertTrue(gate.isStopped(old))
        assertTrue(gate.isStopped(fresh))
    }
}
