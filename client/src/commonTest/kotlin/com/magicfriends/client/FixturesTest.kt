package com.magicfriends.client

import com.magicfriends.client.fixtures.Fixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Los mismos fixtures que prueba el servidor (protocol/fixtures), ahora contra el cliente. */
class FixturesTest {
    private class Case(val name: String, val def: String, val valid: Boolean, val message: JsonObject)

    private val cases: List<Case> = wireJson.parseToJsonElement(Fixtures.messages).jsonObject.getValue("cases").jsonArray.map {
        val o = it.jsonObject
        Case(o.getValue("name").jsonPrimitive.content, o.getValue("def").jsonPrimitive.content, o.getValue("valid").jsonPrimitive.boolean, o.getValue("message").jsonObject)
    }

    @Test
    fun hayCasosDeCadaTipo() {
        assertTrue(cases.size > 30)
        assertTrue(cases.any { it.def == "ServerMessage" && it.valid })
    }

    @Test
    fun losMensajesValidosDelServidorSeDecodifican() {
        for (c in cases.filter { it.def == "ServerMessage" && it.valid }) {
            val msg = parseServerMessage(c.message.toString())
            assertNotNull(msg, "No se pudo decodificar «${c.name}»")
            assertEquals(c.message.getValue("t").jsonPrimitive.content, msg.type, c.name)
        }
    }

    @Test
    fun losMensajesInvalidosDelServidorNoRompenNada() {
        // El cliente es tolerante (ignora campos extra), pero nunca debe lanzar excepciones.
        for (c in cases.filter { it.def == "ServerMessage" && !it.valid }) parseServerMessage(c.message.toString())
    }

    @Test
    fun faltaUnCampoObligatorioDevuelveNull() {
        val sinPrevRev = cases.first { it.name == "patch del servidor sin prevRev" }
        assertEquals(null, parseServerMessage(sinPrevRev.message.toString()))
        val estadoInvalido = cases.first { it.name == "welcome con estado inválido" }
        assertEquals(null, parseServerMessage(estadoInvalido.message.toString()))
    }

    @Test
    fun tipoDesconocidoSeIgnoraSinFallar() {
        val m = parseServerMessage("""{"v":1,"t":"algo_del_futuro","x":1}""")
        assertTrue(m is ServerMessage.Unknown)
    }

    @Test
    fun losMensajesDelClienteCoincidenConLosFixtures() {
        fun fixture(name: String) = cases.first { it.name == name }.message
        assertEquals(fixture("session.create con caducidad").withoutKeys("id"),
            wireJson.parseToJsonElement(clientMessage("session.create", "x") { put("name", "Función del sábado"); putNullable("expiresAt", 1893456000000) }).jsonObject.withoutKeys("id"))
        assertEquals(fixture("session.create sin caducidad (null)").withoutKeys("id"),
            wireJson.parseToJsonElement(clientMessage("session.create", "x") { put("name", "Sin caducidad"); putNullable("expiresAt", null) }).jsonObject.withoutKeys("id"))
        assertEquals(fixture("session.update renombrar y quitar caducidad").withoutKeys("id"),
            wireJson.parseToJsonElement(clientMessage("session.update", "x") { put("sessionId", "sess-0001-aaaa"); put("name", "Nuevo nombre"); putNullable("expiresAt", null) }).jsonObject.withoutKeys("id"))
        assertEquals(fixture("pairing.accept").withoutKeys("id"),
            wireJson.parseToJsonElement(clientMessage("pairing.accept", "x") { put("requestId", "req-0001-aaaa") }).jsonObject.withoutKeys("id"))
    }

    @Test
    fun losEscenariosSeCargan() {
        assertTrue(Fixtures.scenarios.size >= 5)
        for ((name, text) in Fixtures.scenarios) {
            val steps = wireJson.parseToJsonElement(text).jsonObject.getValue("steps").jsonArray
            assertTrue(steps.isNotEmpty(), name)
        }
    }

    private fun JsonObject.withoutKeys(vararg keys: String) = JsonObject(filterKeys { it !in keys })
}
