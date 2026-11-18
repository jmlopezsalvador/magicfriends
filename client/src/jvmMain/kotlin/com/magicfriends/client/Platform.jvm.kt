package com.magicfriends.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.security.SecureRandom

internal actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

internal actual fun createHttpClient(): HttpClient = HttpClient(CIO) { configureMagicFriends() }
