package com.plox.llmtranslate.data.download

import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * Тестовый mini-HTTP-сервер (только loopback 127.0.0.1): раздаёт фикстуры из
 * памяти по GET — INSTRUMENTATION-тесты инсталляторов работают без внешней
 * сети и без реальных моделей. Трафик на loopback не подпадает под
 * ограничение cleartext (targetSdk 28+).
 *
 * Отвечает только 200 + Content-Length: [.part] в тестовых каталогах
 * изначально нет, поэтому запрос Range [BaseDownloader] не отправляет.
 * [files] — соответствие путь → содержимое (путь из запроса, например "/encoder.onnx").
 */
class LocalTestHttpServer(private val files: Map<String, ByteArray>) : Closeable {

    private val serverSocket = ServerSocket(0)

    /** База для URL фикстур: http://127.0.0.1:<port> */
    val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}"

    init {
        Thread(
            {
                try {
                    while (!serverSocket.isClosed) {
                        serverSocket.accept().use(::serve)
                    }
                } catch (_: IOException) {
                    // close() сервера — штатный выход из цикла accept.
                }
            },
            "local-test-http"
        ).apply { isDaemon = true }.start()
    }

    private fun serve(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        val path = requestLine.split(" ").getOrNull(1) ?: return
        // Скроуить заголовки до пустой строки — иначе клиент не получит тело.
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val out = socket.getOutputStream()
        val body = files[path]
        if (body == null) {
            out.write(
                "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .toByteArray()
            )
        } else {
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
            )
            out.write(body)
        }
        out.flush()
    }

    override fun close() {
        serverSocket.close()
    }
}
