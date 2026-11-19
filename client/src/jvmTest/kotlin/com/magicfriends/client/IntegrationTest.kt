package com.magicfriends.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
            val helperStore = InMemoryKeyValueStore() // el dispositivo del ayudante: una identidad para todas sus conexiones
            val joiner = MagicFriendsClient(Role.HELPER, url, helperStore, scope) // sin magoId: solo sirve para join
            val magoEvents = mutableListOf<ClientEvent>()
            scope.launch { mago.events.collect { magoEvents += it } }

            mago.start()
            waitUntil("mago conectado") { mago.connection.value == ConnectionState.Connected }

            val created = mago.createSession("Prueba de integración").getOrThrow()
            assertEquals(SessionStatus.PAUSED, created.session.status)

            // join devuelve mago, sesión y nombre, y NO conecta el cliente.
            val joined = joiner.join(created.pairing.code, "Ayudante de prueba").getOrThrow()
            assertEquals(mago.deviceId, joined.magoId)
            assertEquals(created.session.id, joined.sessionId)
            assertEquals("Prueba de integración", joined.sessionName)
            assertEquals(ConnectionState.Idle, joiner.connection.value)

            // La app decide a qué mago conectar: un cliente por conexión, mismo almacén (misma identidad).
            val ayudante = MagicFriendsClient(Role.HELPER, url, helperStore, scope, magoId = joined.magoId)
            assertEquals(joiner.deviceId, ayudante.deviceId)
            ayudante.start()
            waitUntil("solicitud pendiente en el mago") { mago.pending.value.any { it.requestId == joined.requestId } }
            mago.acceptRequest(joined.requestId).getOrThrow()

            // En pausa el ayudante ya ve su plantilla (la pide solo) y puede editarla; el mago no recibe nada.
            waitUntil("el ayudante ve la sesión pausada con su plantilla") {
                ayudante.connection.value == ConnectionState.Connected &&
                    ayudante.sessions.value.any { it.id == created.session.id && it.status == SessionStatus.PAUSED } &&
                    ayudante.helperState.value.containsKey(created.session.id)
            }
            val revPausa = ayudante.sendPatch(created.session.id, obj("""{"forceMode":"target","forcedCs":9999}""")).getOrThrow()
            assertEquals(1L, revPausa)
            delay(300)
            assertEquals(JsonObject(emptyMap()), mago.remoteSettings.value, "En pausa el mago no aplica nada")
            assertTrue(magoEvents.none { it is ClientEvent.RemotePatch })

            // Activar: el mago recibe la plantilla completa y se le avisa del cambio.
            mago.activateSession(created.session.id).getOrThrow()
            waitUntil("el mago recibe la plantilla al activar") { mago.remoteSettings.value == obj("""{"forceMode":"target","forcedCs":9999}""") }
            assertTrue(magoEvents.any { it is ClientEvent.SettingsChanged && it.sessionId == created.session.id })
            waitUntil("ayudante con la sesión activa") { ayudante.sessions.value.any { it.id == created.session.id && it.status == SessionStatus.ACTIVE } }

            val rev2 = ayudante.sendPatch(created.session.id, obj("""{"forcedCs":4250}""")).getOrThrow()
            assertEquals(2L, rev2)
            waitUntil("el mago recibe el parche") { mago.remoteSettings.value == obj("""{"forceMode":"target","forcedCs":4250}""") }
            assertTrue(magoEvents.any { it is ClientEvent.RemotePatch && it.rev == 2L })

            ayudante.sendPatch(created.session.id, obj("""{"forcedCs":null,"stopSubmode":"centis"}""")).getOrThrow()
            waitUntil("el mago aplica la quita (null)") { mago.remoteSettings.value == obj("""{"forceMode":"target","stopSubmode":"centis"}""") }

            // Pausar: el mago vuelve a sus ajustes locales; el ayudante aún puede editar la plantilla.
            mago.pauseSession(created.session.id).getOrThrow()
            waitUntil("el mago queda sin ajustes remotos") { mago.remoteSettings.value.isEmpty() }
            ayudante.sendPatch(created.session.id, obj("""{"forcedCs":1}""")).getOrThrow()
            mago.activateSession(created.session.id).getOrThrow()
            waitUntil("el mago recupera el estado") { mago.remoteSettings.value == obj("""{"forceMode":"target","stopSubmode":"centis","forcedCs":1}""") }

            // Salir de la conexión: se borra la plantilla, el mago deja de aplicarla y la sesión sigue existiendo.
            val eventosAntes = magoEvents.filterIsInstance<ClientEvent.SettingsChanged>().size
            ayudante.leaveSession(created.session.id).getOrThrow()
            assertTrue(ayudante.sessions.value.none { it.id == created.session.id })
            waitUntil("el mago deja de aplicar la plantilla") { mago.remoteSettings.value.isEmpty() }
            waitUntil("el mago ve la sesión sin controlador") { mago.sessions.value.any { it.id == created.session.id && !it.hasController } }
            assertTrue(magoEvents.filterIsInstance<ClientEvent.SettingsChanged>().size > eventosAntes)
            val rejected = ayudante.sendPatch(created.session.id, obj("""{"forcedCs":2}""")).exceptionOrNull() as MfException
            assertEquals("forbidden", rejected.code)
            assertEquals("forbidden", (ayudante.leaveSession(created.session.id).exceptionOrNull() as MfException).code)

            // Revocar y borrar siguen funcionando.
            mago.revokeSession(created.session.id).getOrThrow()
            mago.deleteSession(created.session.id).getOrThrow()
            assertTrue(mago.sessions.value.none { it.id == created.session.id })

            // Lista de conexiones de varios magos: el mismo dispositivo ayudante se une a un segundo mago.
            val mago2 = MagicFriendsClient(Role.MAGO, url, InMemoryKeyValueStore(), scope, appKey = appKey)
            mago2.start()
            waitUntil("segundo mago conectado") { mago2.connection.value == ConnectionState.Connected }
            val created2 = mago2.createSession("Otro mago").getOrThrow()
            val joined2 = joiner.join(created2.pairing.code, "Ayudante de prueba").getOrThrow()
            assertTrue(joined2.magoId != joined.magoId)
            val ayudante2 = MagicFriendsClient(Role.HELPER, url, helperStore, scope, magoId = joined2.magoId)
            ayudante2.start()
            waitUntil("solicitud pendiente en el segundo mago") { mago2.pending.value.any { it.requestId == joined2.requestId } }
            mago2.acceptRequest(joined2.requestId).getOrThrow()
            waitUntil("el ayudante ve la sesión del segundo mago") { ayudante2.sessions.value.any { it.id == created2.session.id } }
            mago2.deleteSession(created2.session.id).getOrThrow()
        } finally {
            scope.cancel()
        }
    }
}
