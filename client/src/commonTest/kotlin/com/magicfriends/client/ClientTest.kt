package com.magicfriends.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Transporte falso: el test escribe en [inbox] lo que «envía el servidor» y lee en [sent] lo que envía el cliente. */
private class FakeTransport : MfTransport {
    val sent = mutableListOf<String>()
    val urls = mutableListOf<String>()
    var inbox = Channel<String>(Channel.UNLIMITED)
    var connects = 0
    var failConnect = false

    override suspend fun run(url: String, onOpen: suspend (suspend (String) -> Unit) -> Unit, onMessage: suspend (String) -> Unit) {
        connects++
        urls += url
        if (failConnect) throw RuntimeException("sin red")
        onOpen { sent += it }
        for (m in inbox) onMessage(m)
    }

    fun lastSent(): JsonObject = Json.parseToJsonElement(sent.last()).jsonObject
}

private fun session(id: String = "sess-0001-aaaa", status: String = "active", expiresAt: Long? = null, rev: Long = 0, controller: Boolean = true) =
    """{"id":"$id","name":"Función","status":"$status","expiresAt":${expiresAt ?: "null"},"hasController":$controller,"controllerLabel":"Ayudante","rev":$rev,"createdAt":1,"updatedAt":1}"""

private fun welcome(vararg sessions: String, pending: String = "") =
    """{"v":1,"t":"welcome","role":"mago","serverTime":1000,"sessions":[${sessions.joinToString(",")}],"pending":[$pending]}"""

private fun state(rev: Long, settings: String, id: String = "sess-0001-aaaa") = """{"v":1,"t":"state","sessionId":"$id","rev":$rev,"settings":$settings}"""
private fun patch(rev: Long, prev: Long, patch: String, id: String = "sess-0001-aaaa") = """{"v":1,"t":"patch","sessionId":"$id","rev":$rev,"prevRev":$prev,"patch":$patch}"""
private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

@OptIn(ExperimentalCoroutinesApi::class)
class ClientTest {
    private fun TestScope.mago(
        t: FakeTransport,
        store: KeyValueStore = InMemoryKeyValueStore(),
        now: () -> Long = { 5_000L },
        scope: CoroutineScope = backgroundScope,
    ) = MagicFriendsClient(Role.MAGO, "https://example.test", store, scope, appKey = "secreto", transport = t, clock = now)

    private fun TestScope.helper(
        t: FakeTransport,
        store: KeyValueStore = InMemoryKeyValueStore(),
        magoId: String? = "dev-mago-0000000001",
        http: HttpClient? = null,
    ) = MagicFriendsClient(Role.HELPER, "https://example.test", store, backgroundScope, magoId = magoId, transport = t, httpClient = http, clock = { 1L })

    private fun helperWelcome(vararg sessions: String) =
        """{"v":1,"t":"welcome","role":"helper","serverTime":1,"sessions":[${sessions.joinToString(",")}],"pending":[]}"""

    private fun jsonResponse(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) })

    @Test
    fun elHelloLlevaIdentidadYAppKey() = runTest {
        val t = FakeTransport()
        val c = mago(t)
        c.start(); runCurrent()
        val hello = t.lastSent()
        assertEquals("hello", hello.getValue("t").jsonPrimitive.content)
        assertEquals("mago", hello.getValue("role").jsonPrimitive.content)
        assertEquals(c.deviceId, hello.getValue("deviceId").jsonPrimitive.content)
        assertEquals("secreto", hello.getValue("appKey").jsonPrimitive.content)
        assertTrue(Regex("^[A-Za-z0-9_-]{32,128}$").matches(hello.getValue("deviceKey").jsonPrimitive.content))
        assertTrue(Regex("^[A-Za-z0-9_-]{16,64}$").matches(c.deviceId))
    }

    @Test
    fun laIdentidadSePersiste() = runTest {
        val store = InMemoryKeyValueStore()
        val a = mago(FakeTransport(), store)
        val b = mago(FakeTransport(), store)
        assertEquals(a.deviceId, b.deviceId)
    }

    @Test
    fun welcomeYStateDejanLosAjustesRemotos() = runTest {
        val t = FakeTransport()
        val c = mago(t)
        c.start(); runCurrent()
        t.inbox.send(welcome(session(rev = 2)))
        t.inbox.send(state(2, """{"forceMode":"target","forcedCs":4250}"""))
        runCurrent()
        assertEquals(ConnectionState.Connected, c.connection.value)
        assertEquals(obj("""{"forceMode":"target","forcedCs":4250}"""), c.remoteSettings.value)
    }

    @Test
    fun unParcheConsecutivoSeAplicaYAvisa() = runTest {
        val t = FakeTransport()
        val c = mago(t)
        val events = mutableListOf<ClientEvent>()
        backgroundScope.launch { c.events.collect { events += it } }
        c.start(); runCurrent()
        t.inbox.send(welcome(session(rev = 1))); t.inbox.send(state(1, """{"forcedCs":4250,"stopSubmode":"centis"}"""))
        t.inbox.send(patch(2, 1, """{"forcedCs":null,"forceMode":"target"}"""))
        runCurrent()
        assertEquals(obj("""{"stopSubmode":"centis","forceMode":"target"}"""), c.remoteSettings.value)
        assertTrue(events.any { it is ClientEvent.RemotePatch && it.rev == 2L })
    }

    @Test
    fun unHuecoDeRevisionesPideSync() = runTest {
        val t = FakeTransport()
        val c = mago(t)
        c.start(); runCurrent()
        t.inbox.send(welcome(session(rev = 1))); t.inbox.send(state(1, """{"a":1}"""))
        t.inbox.send(patch(5, 3, """{"a":2}"""))
        runCurrent()
        assertEquals(obj("""{"a":1}"""), c.remoteSettings.value, "El parche con hueco no se aplica")
        assertEquals("sync", t.lastSent().getValue("t").jsonPrimitive.content)
        t.inbox.send(state(5, """{"a":2}"""))
        runCurrent()
        assertEquals(obj("""{"a":2}"""), c.remoteSettings.value)
    }

    @Test
    fun pausarLaSesionDejaSoloLosAjustesLocales() = runTest {
        val t = FakeTransport()
        val c = mago(t)
        c.start(); runCurrent()
        t.inbox.send(welcome(session(rev = 1))); t.inbox.send(state(1, """{"a":1}"""))
        runCurrent()
        t.inbox.send("""{"v":1,"t":"session","session":${session(status = "paused", rev = 1)}}""")
        runCurrent()
        assertEquals(JsonObject(emptyMap()), c.remoteSettings.value)
    }

    @Test
    fun sinSesionActivaEnElWelcomeSeLimpia() = runTest {
        val store = InMemoryKeyValueStore()
        val t1 = FakeTransport()
        val c1 = mago(t1, store)
        c1.start(); runCurrent()
        t1.inbox.send(welcome(session(rev = 1))); t1.inbox.send(state(1, """{"a":1}"""))
        runCurrent()
        c1.stop()

        val t2 = FakeTransport()
        val c2 = mago(t2, store)
        assertEquals(obj("""{"a":1}"""), c2.remoteSettings.value, "Restaurada antes de conectar")
        c2.start(); runCurrent()
        t2.inbox.send(welcome(session(status = "paused", rev = 1)))
        runCurrent()
        assertEquals(JsonObject(emptyMap()), c2.remoteSettings.value)
    }

    @Test
    fun sinRedSeUsaLaUltimaInstantanea() = runTest {
        val store = InMemoryKeyValueStore()
        val t1 = FakeTransport()
        val c1 = mago(t1, store)
        c1.start(); runCurrent()
        t1.inbox.send(welcome(session(rev = 3))); t1.inbox.send(state(3, """{"forcedCs":777}"""))
        runCurrent()
        c1.stop()

        val t2 = FakeTransport().also { it.failConnect = true }
        val c2 = mago(t2, store)
        c2.start(); runCurrent()
        assertEquals(obj("""{"forcedCs":777}"""), c2.currentRemoteSettings(), "Offline: sigue la última instantánea")
        assertEquals(ConnectionState.Disconnected, c2.connection.value)
    }

    @Test
    fun laCaducidadLocalAnulaLaInstantaneaSinRed() = runTest {
        val store = InMemoryKeyValueStore()
        var now = 1_000L
        val t1 = FakeTransport()
        val c1 = mago(t1, store, now = { now })
        c1.start(); runCurrent()
        t1.inbox.send(welcome(session(rev = 1, expiresAt = 2_000)))
        t1.inbox.send(state(1, """{"a":1}"""))
        runCurrent()
        assertEquals(obj("""{"a":1}"""), c1.currentRemoteSettings())
        now = 2_000
        assertEquals(JsonObject(emptyMap()), c1.currentRemoteSettings(), "Pasada la caducidad vale lo local")
        assertEquals(JsonObject(emptyMap()), mago(FakeTransport(), store, now = { now }).currentRemoteSettings())
    }

    @Test
    fun reconectaSolaConEsperaCreciente() = runTest {
        val t = FakeTransport().also { it.failConnect = true }
        val c = mago(t)
        c.start(); runCurrent()
        assertEquals(1, t.connects)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, t.connects)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(3, t.connects)
    }

    @Test
    fun badAuthDetieneLosReintentos() = runTest {
        val t = FakeTransport()
        val c = mago(t)
        c.start(); runCurrent()
        t.inbox.send("""{"v":1,"t":"error","code":"bad_auth","message":"no"}""")
        t.inbox.close()
        runCurrent()
        assertEquals(ConnectionState.AuthFailed, c.connection.value)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, t.connects)
    }

    @Test
    fun hastaLaReconexionElHelloLlevaLaRevConocida() = runTest {
        val t = FakeTransport()
        val c = mago(t)
        c.start(); runCurrent()
        t.inbox.send(welcome(session(rev = 4))); t.inbox.send(state(4, """{"a":1}"""))
        runCurrent()
        t.inbox.close()          // se cae la conexión
        advanceTimeBy(1_001)
        t.inbox = Channel(Channel.UNLIMITED)
        runCurrent()
        val known = t.lastSent().getValue("knownRevs").jsonObject
        assertEquals("4", known.getValue("sess-0001-aaaa").jsonPrimitive.content)
    }

    @Test
    fun crearSesionCorrelacionaLaRespuestaPorRef() = runTest {
        val t = FakeTransport()
        val c = mago(t)
        c.start(); runCurrent()
        t.inbox.send(welcome())
        runCurrent()

        var result: Result<CreatedSession>? = null
        backgroundScope.launch { result = c.createSession("Función", expiresAt = null) }
        runCurrent()
        val req = t.lastSent()
        assertEquals("session.create", req.getValue("t").jsonPrimitive.content)
        assertEquals("null", req.getValue("expiresAt").toString())
        val id = req.getValue("id").jsonPrimitive.content
        t.inbox.send("""{"v":1,"t":"session","ref":"$id","session":${session(status = "paused", controller = false)}}""")
        t.inbox.send("""{"v":1,"t":"pairing","ref":"$id","sessionId":"sess-0001-aaaa","code":"K7QX4M","expiresAt":9000}""")
        runCurrent()
        val created = result!!.getOrThrow()
        assertEquals("K7QX4M", created.pairing.code)
        assertEquals("sess-0001-aaaa", created.session.id)
        assertEquals(1, c.sessions.value.size)
    }

    @Test
    fun unErrorDelServidorLlegaComoFallo() = runTest {
        val t = FakeTransport()
        val c = mago(t)
        c.start(); runCurrent()
        t.inbox.send(welcome(session(status = "paused")))
        runCurrent()
        var result: Result<Session>? = null
        backgroundScope.launch { result = c.activateSession("sess-0001-aaaa") }
        runCurrent()
        val id = t.lastSent().getValue("id").jsonPrimitive.content
        t.inbox.send("""{"v":1,"t":"error","ref":"$id","code":"expired","message":"caducada"}""")
        runCurrent()
        val e = result!!.exceptionOrNull() as MfException
        assertEquals("expired", e.code)
    }

    @Test
    fun sinConexionLosComandosFallanSinColgarse() = runTest {
        val c = mago(FakeTransport())
        val e = c.activateSession("sess-0001-aaaa").exceptionOrNull() as MfException
        assertEquals("not_connected", e.code)
    }

    @Test
    fun laSolicitudDeEmparejamientoSeAnotaYSeQuitaAlAceptar() = runTest {
        val t = FakeTransport()
        val c = mago(t)
        val events = mutableListOf<ClientEvent>()
        backgroundScope.launch { c.events.collect { events += it } }
        c.start(); runCurrent()
        t.inbox.send(welcome(session(status = "paused", controller = false)))
        t.inbox.send("""{"v":1,"t":"pairing.request","request":{"requestId":"req-0001-aaaa","sessionId":"sess-0001-aaaa","label":"Ayudante","at":7}}""")
        runCurrent()
        assertEquals(1, c.pending.value.size)
        assertTrue(events.any { it is ClientEvent.PairingRequested })

        var result: Result<Session>? = null
        backgroundScope.launch { result = c.acceptRequest("req-0001-aaaa") }
        runCurrent()
        val id = t.lastSent().getValue("id").jsonPrimitive.content
        t.inbox.send("""{"v":1,"t":"session","ref":"$id","session":${session(status = "paused")}}""")
        runCurrent()
        assertEquals(true, result!!.getOrThrow().hasController)
        assertEquals(0, c.pending.value.size)
    }

    @Test
    fun elAyudanteEnviaParchesYRecuerdaElEstado() = runTest {
        val t = FakeTransport()
        val c = helper(t, magoId = "dev-mago-0000000001")
        c.start(); runCurrent()
        assertEquals("helper", t.lastSent().getValue("role").jsonPrimitive.content)
        t.inbox.send("""{"v":1,"t":"welcome","role":"helper","serverTime":1,"sessions":[${session(rev = 1)}],"pending":[]}""")
        t.inbox.send(state(1, """{"a":1}"""))
        runCurrent()

        var result: Result<Long>? = null
        backgroundScope.launch { result = c.sendPatch("sess-0001-aaaa", obj("""{"a":null,"b":2}""")) }
        runCurrent()
        val id = t.lastSent().getValue("id").jsonPrimitive.content
        t.inbox.send("""{"v":1,"t":"ack","ref":"$id","rev":2}""")
        runCurrent()
        assertEquals(2L, result!!.getOrThrow())
        assertEquals(RemoteState(2, obj("""{"b":2}""")), c.helperState.value["sess-0001-aaaa"])
    }

    @Test
    fun unAyudanteSinEmparejarNoConecta() = runTest {
        val t = FakeTransport()
        val c = MagicFriendsClient(Role.HELPER, "https://example.test", InMemoryKeyValueStore(), backgroundScope, transport = t)
        c.start(); runCurrent()
        assertEquals(0, t.connects)
        assertEquals(ConnectionState.Idle, c.connection.value)
    }

    // ---- v0.2: ayudante con lista de conexiones ----

    @Test
    fun elAyudanteConectaAlMagoIndicado() = runTest {
        val t = FakeTransport()
        val c = helper(t, magoId = "dev-mago-0000000042")
        c.start(); runCurrent()
        assertTrue(t.urls.single().endsWith("/v1/ws?m=dev-mago-0000000042"), t.urls.single())
        assertEquals("dev-mago-0000000042", c.magoId)
    }

    @Test
    fun variosClientesDelAyudanteCompartenIdentidadSiCompartenAlmacen() = runTest {
        val store = InMemoryKeyValueStore()
        val a = helper(FakeTransport(), store, magoId = "dev-mago-0000000001")
        val b = helper(FakeTransport(), store, magoId = "dev-mago-0000000002")
        assertEquals(a.deviceId, b.deviceId)
    }

    @Test
    fun joinDevuelveElResultadoYNoCambiaNiConectaElCliente() = runTest {
        val t = FakeTransport()
        val c = helper(t, magoId = null, http = jsonResponse(
            """{"magoId":"dev-mago-0000000007","requestId":"req-0001-aaaa","sessionId":"sess-0001-aaaa","sessionName":"Función del sábado"}"""))
        val r = c.join(" k7qx4m ", "Ayudante").getOrThrow()
        assertEquals(JoinResult("dev-mago-0000000007", "req-0001-aaaa", "sess-0001-aaaa", "Función del sábado"), r)
        runCurrent()
        assertEquals(null, c.magoId, "join no elige el mago por su cuenta")
        assertEquals(0, t.connects, "join no conecta")
        c.start(); runCurrent()
        assertEquals(0, t.connects, "sin mago no hay a qué conectar")
    }

    @Test
    fun joinConUnCodigoMalDevuelveElErrorDelServidor() = runTest {
        val c = helper(FakeTransport(), magoId = null, http = jsonResponse("""{"code":"bad_code","message":"no"}""", HttpStatusCode.NotFound))
        assertEquals("bad_code", (c.join("AAAAAA").exceptionOrNull() as MfException).code)
    }

    @Test
    fun salirDeUnaSesionLaQuitaDeLaListaYAvisa() = runTest {
        val t = FakeTransport()
        val c = helper(t)
        val events = mutableListOf<ClientEvent>()
        backgroundScope.launch { c.events.collect { events += it } }
        c.start(); runCurrent()
        t.inbox.send(helperWelcome(session(rev = 1), session(id = "sess-0002-bbbb", rev = 0)))
        t.inbox.send(state(1, """{"a":1}"""))
        runCurrent()

        var result: Result<Unit>? = null
        backgroundScope.launch { result = c.leaveSession("sess-0001-aaaa") }
        runCurrent()
        val req = t.lastSent()
        assertEquals("session.leave", req.getValue("t").jsonPrimitive.content)
        assertEquals("sess-0001-aaaa", req.getValue("sessionId").jsonPrimitive.content)
        val id = req.getValue("id").jsonPrimitive.content
        t.inbox.send("""{"v":1,"t":"ack","ref":"$id"}""")
        t.inbox.send("""{"v":1,"t":"session.removed","sessionId":"sess-0001-aaaa","reason":"left"}""")
        runCurrent()
        result!!.getOrThrow()
        assertEquals(listOf("sess-0002-bbbb"), c.sessions.value.map { it.id })
        assertTrue("sess-0001-aaaa" !in c.helperState.value)
        assertTrue(events.any { it is ClientEvent.SessionRemoved && it.sessionId == "sess-0001-aaaa" && it.reason == "left" })
    }

    @Test
    fun salirSinSerControladorFallaConForbidden() = runTest {
        val t = FakeTransport()
        val c = helper(t)
        c.start(); runCurrent()
        t.inbox.send(helperWelcome(session()))
        runCurrent()
        var result: Result<Unit>? = null
        backgroundScope.launch { result = c.leaveSession("sess-0001-aaaa") }
        runCurrent()
        val id = t.lastSent().getValue("id").jsonPrimitive.content
        t.inbox.send("""{"v":1,"t":"error","ref":"$id","code":"forbidden","message":"no"}""")
        runCurrent()
        assertEquals("forbidden", (result!!.exceptionOrNull() as MfException).code)
        assertEquals(1, c.sessions.value.size, "Si falla, la sesión se conserva")
    }

    @Test
    fun elAyudantePideLaPlantillaDeLasSesionesNoActivas() = runTest {
        val t = FakeTransport()
        val c = helper(t)
        c.start(); runCurrent()
        t.inbox.send(helperWelcome(session(status = "paused", rev = 3)))
        runCurrent()
        val sync = t.lastSent()
        assertEquals("sync", sync.getValue("t").jsonPrimitive.content)
        assertEquals("sess-0001-aaaa", sync.getValue("sessionId").jsonPrimitive.content)
        t.inbox.send("""{"v":1,"t":"state","ref":"${sync.getValue("id").jsonPrimitive.content}","sessionId":"sess-0001-aaaa","rev":3,"settings":{"forcedCs":4250}}""")
        runCurrent()
        assertEquals(RemoteState(3, obj("""{"forcedCs":4250}""")), c.helperState.value["sess-0001-aaaa"])

        // Con la plantilla ya al día, un nuevo mensaje de sesión no repite el sync.
        val enviados = t.sent.size
        t.inbox.send("""{"v":1,"t":"session","session":${session(status = "paused", rev = 3)}}""")
        runCurrent()
        assertEquals(enviados, t.sent.size)
    }

    @Test
    fun alEditarEnPausaElAyudanteVeSuPlantilla() = runTest {
        val t = FakeTransport()
        val c = helper(t)
        c.start(); runCurrent()
        t.inbox.send(helperWelcome(session(status = "paused", rev = 0)))
        t.inbox.send(state(0, "{}"))
        runCurrent()
        var result: Result<Long>? = null
        backgroundScope.launch { result = c.sendPatch("sess-0001-aaaa", obj("""{"forcedCs":4250}""")) }
        runCurrent()
        t.inbox.send("""{"v":1,"t":"ack","ref":"${t.lastSent().getValue("id").jsonPrimitive.content}","rev":1}""")
        runCurrent()
        assertEquals(1L, result!!.getOrThrow())
        assertEquals(RemoteState(1, obj("""{"forcedCs":4250}""")), c.helperState.value["sess-0001-aaaa"])
    }

    @Test
    fun borrarUnaSesionLaQuitaDeLaListaYLimpiaLaInstantanea() = runTest {
        val t = FakeTransport()
        val c = mago(t)
        c.start(); runCurrent()
        t.inbox.send(welcome(session(rev = 1))); t.inbox.send(state(1, """{"a":1}"""))
        runCurrent()
        assertEquals(obj("""{"a":1}"""), c.currentRemoteSettings())

        var result: Result<Unit>? = null
        backgroundScope.launch { result = c.deleteSession("sess-0001-aaaa") }
        runCurrent()
        t.inbox.send("""{"v":1,"t":"ack","ref":"${t.lastSent().getValue("id").jsonPrimitive.content}"}""")
        runCurrent()
        result!!.getOrThrow()
        assertEquals(0, c.sessions.value.size)
        assertEquals(JsonObject(emptyMap()), c.currentRemoteSettings(), "Sin sesión no quedan ajustes remotos")
    }

    @Test
    fun unStateQueCambiaLosAjustesDeLaSesionActivaEmiteEvento() = runTest {
        val t = FakeTransport()
        val c = mago(t)
        val events = mutableListOf<ClientEvent>()
        backgroundScope.launch { c.events.collect { events += it } }
        c.start(); runCurrent()
        t.inbox.send(welcome(session(rev = 2)))
        t.inbox.send(state(2, """{"forcedCs":4250}"""))
        runCurrent()
        assertEquals(listOf(ClientEvent.SettingsChanged("sess-0001-aaaa", 2)), events.filterIsInstance<ClientEvent.SettingsChanged>())

        // El mismo contenido (p. ej. al reconectar) no vuelve a avisar.
        t.inbox.send(state(2, """{"forcedCs":4250}"""))
        runCurrent()
        assertEquals(1, events.filterIsInstance<ClientEvent.SettingsChanged>().size)

        // El ayudante se sale de la sesión activa: state vacío → el mago deja de aplicar y se le avisa.
        t.inbox.send("""{"v":1,"t":"session","session":${session(rev = 3, controller = false)}}""")
        t.inbox.send(state(3, "{}"))
        runCurrent()
        assertEquals(JsonObject(emptyMap()), c.remoteSettings.value)
        assertEquals(ClientEvent.SettingsChanged("sess-0001-aaaa", 3), events.filterIsInstance<ClientEvent.SettingsChanged>().last())
        assertTrue(events.none { it is ClientEvent.RemotePatch })
    }
}
