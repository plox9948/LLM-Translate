package com.plox.llmtranslate.data.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Юнит-тесты классификации переполнения диска (ревью, п.3): распознавание
 * ENOSPC в цепочке причин и перевод [DownloadOutcome.InsufficientStorage]
 * в неустранимую ошибку установки (permanent — воркер ставит ERROR за
 * одну попытку без повтора).
 */
class InsufficientStorageClassificationTest {

    @Test
    fun `enospc messages recognized at any cause depth`() {
        assertTrue(isOutOfSpaceError(IOException("No space left on device")))
        // Формат сообщения Android ErrnoException.
        assertTrue(
            isOutOfSpaceError(IOException("write failed: ENOSPC (No space left on device)"))
        )
        // ENOSPC без расшифровки.
        assertTrue(isOutOfSpaceError(IOException("write failed: ENOSPC")))
        // Вложенная причина — цепочка просматривается целиком.
        assertTrue(
            isOutOfSpaceError(
                RuntimeException(
                    "failed to write part",
                    IllegalStateException(
                        IOException("No space left on device")
                    )
                )
            )
        )
    }

    @Test
    fun `other io errors are not out-of-space`() {
        assertFalse(isOutOfSpaceError(null))
        assertFalse(isOutOfSpaceError(IOException()))
        assertFalse(isOutOfSpaceError(IOException("Connection timed out")))
        assertFalse(isOutOfSpaceError(IOException("Connection reset by peer")))
        assertFalse(
            isOutOfSpaceError(RuntimeException("too many redirects", IOException("HTTP 503")))
        )
    }

    @Test
    fun `insufficient storage maps to permanent install failure`() {
        val failure = DownloadOutcome
            .InsufficientStorage("Недостаточно свободного места: model.onnx")
            .toInstallFailure()
        assertTrue(failure.permanent)
        assertTrue(failure.message.contains("model.onnx"))
    }
}
