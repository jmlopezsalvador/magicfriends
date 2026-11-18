package com.magicfriends.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Prueba de extremo a extremo con Ktor y WebSocket reales contra un servidor en marcha.
 * Solo se ejecuta si existe la variable de entorno MF_SERVER_URL (p. ej. `npx wrangler dev` → http://localhost:8787);
 * MF_APP_KEY es opcional (solo si el servidor tiene APP_SECRET).
 */
class IntegrationTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private suspend fun waitUntil(what: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!condition()) delay(25) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Tiempo agotado esperando: $what")
        }
    }

    @Test
    fun magoYAyudanteDeExtremoAExtremo() = runBlocking {
        val url = System.getenv("MF_SERVER_URL") ?: return@runBlocking // sin servidor, se omite
        val appKey = System.getenv("MF_APP_KEY")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val mago = MagicFriendsClient(Role.MAGO, url, InMemoryKeyValueStore(), scope, appKey = appKey)
            val ayudante = MagicFriendsClient(Role.HELPER, url, InMemoryKeyValueStore(), scope)
            val magoEvents = mutableListOf<ClientEvent>()
            scope.launch { mago.events.collect { magoEvents += it } }

            mago.start()
            waitUntil("mago conectado") { mago.connection.value == ConnectionState.Connected }

            val created = mago.createSession("Prueba de integración").getOrThrow()
            assertEquals(SessionStatus.PAUSED, created.session.status)

            val requestId = ayudante.join(created.pairing.code, "Ayudante de prueba").getOrThrow()
            waitUntil("solicitud pendiente en el mago") { mago.pending.value.any { it.requestId == requestId } }
            mago.acceptRequest(requestId).getOrThrow()
            mago.activateSession(created.session.id).getOrThrow()

            waitUntil("ayudante conectado y con la sesión activa") {
                ayudante.connection.value == ConnectionState.Connected &&
                    ayudante.sessions.value.any { it.id == created.session.id && it.status == SessionStatus.ACTIVE } &&
                    ayudante.helperState.value.containsKey(created.session.id)
            }

            val rev1 = ayudante.sendPatch(created.session.id, obj("""{"forceMode":"target","forcedCs":4250}""")).getOrThrow()
            assertEquals(1L, rev1)
            waitUntil("el mago recibe el parche") { mago.remoteSettings.value == obj("""{"forceMode":"target","forcedCs":4250}""") }
            assertTrue(magoEvents.any { it is ClientEvent.RemotePatch && it.rev == 1L })

            ayudante.sendPatch(created.session.id, obj("""{"forcedCs":null,"stopSubmode":"centis"}""")).getOrThrow()
            waitUntil("el mago aplica la quita (null)") { mago.remoteSettings.value == obj("""{"forceMode":"target","stopSubmode":"centis"}""") }

            // Pausar: el mago vuelve a sus ajustes locales; el ayudante ya no puede escribir.
            mago.pauseSession(created.session.id).getOrThrow()
            waitUntil("el mago queda sin ajustes remotos") { mago.remoteSettings.value.isEmpty() }
            val rejected = ayudante.sendPatch(created.session.id, obj("""{"forcedCs":1}""")).exceptionOrNull() as MfException
            assertEquals("not_active", rejected.code)

            // Reactivar: el estado vuelve a llegar completo.
            mago.activateSession(created.session.id).getOrThrow()
            waitUntil("el mago recupera el estado") { mago.remoteSettings.value == obj("""{"forceMode":"target","stopSubmode":"centis"}""") }

            // Revocar: el ayudante pierde la sesión.
            mago.revokeSession(created.session.id).getOrThrow()
            waitUntil("el ayudante pierde la sesión") { ayudante.sessions.value.none { it.id == created.session.id } }
            mago.deleteSession(created.session.id).getOrThrow()
        } finally {
            scope.cancel()
        }
    }
}
