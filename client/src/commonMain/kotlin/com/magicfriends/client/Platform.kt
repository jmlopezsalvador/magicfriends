package com.magicfriends.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.websocket.WebSockets

/** Bytes aleatorios criptográficamente seguros (identidad del dispositivo). */
internal expect fun secureRandomBytes(size: Int): ByteArray

/** Cliente Ktor con el motor de cada plataforma (OkHttp, Darwin, CIO). */
internal expect fun createHttpClient(): HttpClient

internal fun HttpClientConfig<*>.configureMagicFriends() {
    install(WebSockets) { pingIntervalMillis = 20_000 }
}

private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

/** Base64 «URL-safe» sin relleno (la clave del dispositivo debe cumplir `[A-Za-z0-9_-]`). */
internal fun base64Url(bytes: ByteArray): String {
    val out = StringBuilder()
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
        val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
        out.append(ALPHABET[b0 shr 2])
        out.append(ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
        if (i + 1 < bytes.size) out.append(ALPHABET[((b1 and 15) shl 2) or (b2 shr 6)])
        if (i + 2 < bytes.size) out.append(ALPHABET[b2 and 63])
        i += 3
    }
    return out.toString()
}
