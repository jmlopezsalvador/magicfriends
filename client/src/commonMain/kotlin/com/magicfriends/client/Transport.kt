package com.magicfriends.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText

/**
 * Canal WebSocket abstracto. La implementación real usa Ktor; los tests inyectan una falsa.
 * `run` abre la conexión, llama a [onOpen] con la función de envío, entrega cada mensaje de texto a [onMessage]
 * y **vuelve cuando la conexión se cierra** (o lanza si no pudo conectar).
 */
interface MfTransport {
    suspend fun run(url: String, onOpen: suspend (send: suspend (String) -> Unit) -> Unit, onMessage: suspend (String) -> Unit)
}

internal class KtorTransport(private val http: HttpClient) : MfTransport {
    override suspend fun run(url: String, onOpen: suspend (send: suspend (String) -> Unit) -> Unit, onMessage: suspend (String) -> Unit) {
        http.webSocket(urlString = url) {
            onOpen { text -> send(Frame.Text(text)) }
            for (frame in incoming) {
                if (frame is Frame.Text) onMessage(frame.readText())
            }
        }
    }
}
