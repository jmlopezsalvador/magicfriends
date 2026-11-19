package com.magicfriends.client

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Cliente de Magic Friends para una app. Un cliente = un rol ([Role.MAGO] o [Role.HELPER]) = una conexión.
 *
 * **Mago:** lee los ajustes remotos con [remoteSettings] / [currentRemoteSettings] y gestiona las sesiones.
 * La app calcula el ajuste efectivo como `remoto[clave] ?: local[clave]` en el momento de usarlo.
 * **Ayudante:** se une con [join] (que NO conecta: la app decide a qué mago conectar creando un cliente con `magoId`),
 * observa [helperState], envía parches con [sendPatch] y se sale de una conexión con [leaveSession].
 * Un ayudante con varias conexiones (de uno o de varios magos) usa **un cliente por mago**, todos con el mismo
 * [KeyValueStore]: así comparten `deviceId`/`deviceKey`.
 *
 * Sin red el efecto no puede fallar: la última instantánea se guarda en [KeyValueStore] y nunca se espera a la red.
 * La conexión se reintenta sola; no hace falta que la app la vigile.
 *
 * @param serverUrl base HTTP(S) del servicio, p. ej. `https://…workers.dev` (se convierte a `wss://` para el WebSocket).
 * @param appKey secreto compartido de las apps; solo lo necesita el mago la primera vez (registro).
 * @param magoId (ayudante) mago al que conectar. `null`: ninguno (el cliente no conecta; sirve para [join]). Ignorado en el mago.
 * @param transport solo para tests; por defecto Ktor.
 */
class MagicFriendsClient(
    val role: Role,
    serverUrl: String,
    private val store: KeyValueStore,
    private val scope: CoroutineScope,
    private val appKey: String? = null,
    magoId: String? = null,
    private val transport: MfTransport? = null,
    httpClient: HttpClient? = null,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val baseUrl = serverUrl.trimEnd('/')
    private val http: HttpClient by lazy { httpClient ?: createHttpClient() }
    private val tr: MfTransport by lazy { transport ?: KtorTransport(http) }

    // ---- Identidad anónima del dispositivo (una por rol) ----
    private val kDeviceId = "mf.${role.wire}.deviceId"
    private val kDeviceKey = "mf.${role.wire}.deviceKey"
    private val kLegacyMagoId = "mf.helper.magoId" // 0.1.x lo guardaba al hacer join; se lee solo si no se indica magoId
    private val kSnapshot = "mf.mago.snapshot"

    val deviceId: String = store.getString(kDeviceId) ?: ("dev-" + base64Url(secureRandomBytes(16))).also { store.putString(kDeviceId, it) }
    private val deviceKey: String = store.getString(kDeviceKey) ?: base64Url(secureRandomBytes(32)).also { store.putString(kDeviceKey, it) }

    /** Mago al que pertenece la conexión: el propio dispositivo (mago) o el indicado en el constructor (ayudante). */
    val magoId: String? = if (role == Role.MAGO) deviceId else (magoId ?: store.getString(kLegacyMagoId))

    // ---- Estado observable ----
    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    /** Sesiones visibles para este dispositivo (mago: todas; ayudante: las que controla). */
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingRequest>>(emptyList())
    /** (Mago) solicitudes de emparejamiento pendientes. (Ayudante) las suyas. */
    val pending: StateFlow<List<PendingRequest>> = _pending.asStateFlow()

    private val emptySettings = JsonObject(emptyMap())
    private var snapshot: Snapshot? = loadSnapshot()
    private val _remoteSettings = MutableStateFlow(effective(snapshot))
    /**
     * (Mago) Ajustes remotos de la sesión activa; vacío si no hay ninguna activa. Se restaura del almacenamiento al
     * crear el cliente, así que está disponible antes de conectar. Usa [currentRemoteSettings] al leer en el último instante.
     */
    val remoteSettings: StateFlow<JsonObject> = _remoteSettings.asStateFlow()

    private val _helperState = MutableStateFlow<Map<String, RemoteState>>(emptyMap())
    /** (Ayudante) último estado conocido de los ajustes de cada sesión que controla. */
    val helperState: StateFlow<Map<String, RemoteState>> = _helperState.asStateFlow()

    private val _events = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ClientEvent> = _events.asSharedFlow()

    /** (Mago) Ajustes remotos efectivos **ahora mismo**: comprueba la caducidad con el reloj, aunque no haya red. */
    fun currentRemoteSettings(): JsonObject = effective(snapshot)

    // ---- Ciclo de vida ----
    private var job: Job? = null
    private var sender: (suspend (String) -> Unit)? = null
    private var authFailed = false

    /** Inicia la conexión (y los reintentos). Idempotente. */
    fun start() {
        if (job?.isActive == true) return
        if (magoId == null) return // ayudante sin mago: nada a lo que conectar
        authFailed = false
        job = scope.launch { connectionLoop() }
    }

    /** Cierra la conexión y detiene los reintentos. */
    fun stop() {
        job?.cancel()
        job = null
        sender = null
        _connection.value = ConnectionState.Idle
    }

    private suspend fun connectionLoop() {
        var backoff = INITIAL_BACKOFF_MS
        while (true) {
            val mago = magoId ?: return
            _connection.value = ConnectionState.Connecting
            try {
                val url = baseUrl.replaceFirst("http", "ws") + "/v1/ws?m=$mago"
                tr.run(url, onOpen = { send ->
                    sender = send
                    send(helloMessage())
                }, onMessage = { text ->
                    if (handle(text)) backoff = INITIAL_BACKOFF_MS
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Sin red o servidor caído: se reintenta.
            }
            sender = null
            if (authFailed) {
                _connection.value = ConnectionState.AuthFailed
                return
            }
            _connection.value = ConnectionState.Disconnected
            delay(backoff)
            backoff = minOf(backoff * 2, MAX_BACKOFF_MS)
        }
    }

    private fun helloMessage(): String = clientMessage("hello") {
        put("role", role.wire)
        put("deviceId", deviceId)
        put("deviceKey", deviceKey)
        if (role == Role.MAGO && appKey != null) put("appKey", appKey)
        val revs = buildJsonObject {
            if (role == Role.MAGO) snapshot?.let { put(it.sessionId, it.rev) }
            else _helperState.value.forEach { (id, s) -> put(id, s.rev) }
        }
        if (revs.isNotEmpty()) put("knownRevs", revs)
    }

    // ---- Peticiones con respuesta ----
    private class Waiter(val expect: Set<String>, val reply: CompletableDeferred<ServerMessage> = CompletableDeferred())

    private val lock = Mutex()
    private val waiters = HashMap<String, Waiter>()
    private var counter = 0L

    private suspend fun request(type: String, expect: Set<String>, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): Result<ServerMessage> {
        val send = sender ?: return Result.failure(MfException("not_connected", "Sin conexión con el servidor"))
        val waiter = Waiter(expect)
        val id = lock.withLock { "c${++counter}".also { waiters[it] = waiter } }
        try {
            send(clientMessage(type, id, build))
            val reply = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { waiter.reply.await() }
                ?: return Result.failure(MfException("timeout", "El servidor no respondió"))
            return if (reply is ServerMessage.Error) Result.failure(MfException(reply.code, reply.message)) else Result.success(reply)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            lock.withLock { waiters.remove(id) }
        }
    }

    private inline fun <reified T : ServerMessage> Result<ServerMessage>.expect(): Result<T> =
        mapCatching { it as? T ?: throw MfException("bad_response", "Respuesta inesperada: ${it.type}") }

    // ---- Comandos del mago ----

    /** Crea una sesión (pausada) y devuelve su código de emparejamiento. */
    suspend fun createSession(name: String, expiresAt: Long? = null): Result<CreatedSession> =
        request("session.create", setOf("pairing")) { put("name", name); putNullable("expiresAt", expiresAt) }
            .expect<ServerMessage.Pairing>().mapCatching { p ->
                val s = _sessions.value.firstOrNull { it.id == p.sessionId } ?: throw MfException("bad_response", "Falta la sesión creada")
                CreatedSession(s, PairingCode(p.sessionId, p.code, p.expiresAt))
            }

    /** Código nuevo para una sesión existente (también sirve para re-emparejar: el nuevo ayudante sustituye al anterior). */
    suspend fun newPairingCode(sessionId: String): Result<PairingCode> =
        request("pairing.code", setOf("pairing")) { put("sessionId", sessionId) }
            .expect<ServerMessage.Pairing>().map { PairingCode(it.sessionId, it.code, it.expiresAt) }

    suspend fun acceptRequest(requestId: String): Result<Session> =
        request("pairing.accept", setOf("session")) { put("requestId", requestId) }
            .expect<ServerMessage.SessionMsg>().map { removePending(requestId); it.session }

    suspend fun rejectRequest(requestId: String): Result<Unit> =
        request("pairing.reject", setOf("ack")) { put("requestId", requestId) }.map { removePending(requestId) }

    /** Activa la sesión y pausa la que estuviera activa (solo hay una a la vez). */
    suspend fun activateSession(sessionId: String): Result<Session> = sessionCommand("session.activate", sessionId)
    suspend fun pauseSession(sessionId: String): Result<Session> = sessionCommand("session.pause", sessionId)
    suspend fun revokeSession(sessionId: String): Result<Session> = sessionCommand("session.revoke", sessionId)

    suspend fun renameSession(sessionId: String, name: String): Result<Session> =
        request("session.update", setOf("session")) { put("sessionId", sessionId); put("name", name) }
            .expect<ServerMessage.SessionMsg>().map { it.session }

    /** Cambia la caducidad; `null` la quita. Una sesión caducada vuelve a pausada si la nueva caducidad es futura o nula. */
    suspend fun setSessionExpiry(sessionId: String, expiresAt: Long?): Result<Session> =
        request("session.update", setOf("session")) { put("sessionId", sessionId); putNullable("expiresAt", expiresAt) }
            .expect<ServerMessage.SessionMsg>().map { it.session }

    suspend fun deleteSession(sessionId: String): Result<Unit> =
        request("session.delete", setOf("ack")) { put("sessionId", sessionId) }.map { forgetSession(sessionId) }

    private suspend fun sessionCommand(type: String, sessionId: String): Result<Session> =
        request(type, setOf("session")) { put("sessionId", sessionId) }.expect<ServerMessage.SessionMsg>().map { it.session }

    /** Pide una instantánea completa de los ajustes de una sesión. */
    suspend fun sync(sessionId: String): Result<Unit> =
        request("sync", setOf("state")) { put("sessionId", sessionId) }.map { }

    // ---- Comandos del ayudante ----

    /**
     * Canjea un código de emparejamiento (HTTP). Si el servidor lo acepta, la solicitud queda pendiente de que el mago
     * apruebe. **No cambia el mago de este cliente ni conecta**: la app decide a cuál conectar (normalmente creando un
     * cliente nuevo con `magoId = resultado.magoId` y el mismo [KeyValueStore]) y guarda su lista de conexiones.
     */
    suspend fun join(code: String, label: String? = null): Result<JoinResult> {
        check(role == Role.HELPER) { "join solo lo usa el ayudante" }
        return try {
            val body = buildJsonObject {
                put("code", code.trim().uppercase())
                put("deviceId", deviceId)
                put("deviceKey", deviceKey)
                if (label != null) put("label", label)
            }.toString()
            val response = http.post("$baseUrl/v1/join") {
                header(HttpHeaders.ContentType, "application/json")
                setBody(body)
            }
            val text = response.bodyAsText()
            val json = wireJson.parseToJsonElement(text).jsonObject
            if (response.status.value !in 200..299) {
                Result.failure(MfException(json["code"]?.jsonPrimitive?.contentOrNull ?: "http_${response.status.value}",
                    json["message"]?.jsonPrimitive?.contentOrNull ?: "Error del servidor"))
            } else {
                Result.success(JoinResult(
                    magoId = json.getValue("magoId").jsonPrimitive.content,
                    requestId = json.getValue("requestId").jsonPrimitive.content,
                    sessionId = json.getValue("sessionId").jsonPrimitive.content,
                    sessionName = json.getValue("sessionName").jsonPrimitive.content,
                ))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * (Ayudante) Se sale de la sesión: el servidor le quita el control y **borra los ajustes (la plantilla)**; la sesión
     * sigue existiendo para el mago. La sesión desaparece de [sessions] y se emite [ClientEvent.SessionRemoved] con
     * `reason = "left"`. Si era la última sesión con ese mago, la conexión ya no volverá a autenticarse: la app debe
     * hacer [stop] de este cliente y quitarlo de su lista.
     */
    suspend fun leaveSession(sessionId: String): Result<Unit> =
        request("session.leave", setOf("ack")) { put("sessionId", sessionId) }.map { forgetSession(sessionId) }

    /** Envía un JSON Merge Patch a la sesión activa que controlas. `null` en un valor quita el override. Devuelve la `rev`. */
    suspend fun sendPatch(sessionId: String, patch: JsonObject): Result<Long> {
        val reply = request("patch", setOf("ack")) { put("sessionId", sessionId); put("patch", patch) }.expect<ServerMessage.Ack>()
        reply.onSuccess { ack ->
            val rev = ack.rev
            if (rev != null) _helperState.update { m ->
                val cur = m[sessionId]
                m + (sessionId to RemoteState(rev, mergePatch(cur?.settings ?: emptySettings, patch)))
            }
        }
        return reply.map { it.rev ?: 0L }
    }

    // ---- Recepción ----

    /** Procesa un mensaje del servidor. Devuelve true si es `welcome` (conexión establecida). */
    private suspend fun handle(text: String): Boolean {
        val msg = parseServerMessage(text) ?: return false
        var welcomed = false
        when (msg) {
            is ServerMessage.Welcome -> {
                _sessions.value = msg.sessions
                _pending.value = msg.pending
                if (role == Role.MAGO) reconcileSnapshot()
                else {
                    _helperState.update { m -> m.filterKeys { id -> msg.sessions.any { it.id == id } } }
                    msg.sessions.forEach { syncIfMissing(it) }
                }
                _connection.value = ConnectionState.Connected
                welcomed = true
            }
            is ServerMessage.SessionMsg -> {
                _sessions.update { list -> if (list.any { it.id == msg.session.id }) list.map { if (it.id == msg.session.id) msg.session else it } else list + msg.session }
                if (role == Role.MAGO) reconcileSnapshot() else syncIfMissing(msg.session)
            }
            is ServerMessage.SessionRemoved -> {
                _sessions.update { list -> list.filter { it.id != msg.sessionId } }
                _helperState.update { it - msg.sessionId }
                if (role == Role.MAGO) reconcileSnapshot()
                _events.tryEmit(ClientEvent.SessionRemoved(msg.sessionId, msg.reason))
            }
            is ServerMessage.State -> {
                if (role == Role.MAGO) {
                    val s = _sessions.value.firstOrNull { it.id == msg.sessionId }
                    if (s == null || s.status == SessionStatus.ACTIVE) {
                        val before = snapshot?.takeIf { it.sessionId == msg.sessionId }?.settings ?: emptySettings
                        setSnapshot(Snapshot(msg.sessionId, msg.rev, s?.expiresAt, msg.settings))
                        if (before != msg.settings) _events.tryEmit(ClientEvent.SettingsChanged(msg.sessionId, msg.rev))
                    }
                } else {
                    _helperState.update { it + (msg.sessionId to RemoteState(msg.rev, msg.settings)) }
                }
            }
            is ServerMessage.Patch -> if (role == Role.MAGO) applyPatch(msg)
            is ServerMessage.PairingRequest -> {
                _pending.update { list -> if (list.any { it.requestId == msg.request.requestId }) list else list + msg.request }
                _events.tryEmit(ClientEvent.PairingRequested(msg.request))
            }
            is ServerMessage.PairingResult -> {
                _pending.update { list -> list.filter { it.requestId != msg.requestId } }
                _events.tryEmit(ClientEvent.PairingResult(msg.requestId, msg.accepted, msg.sessionId))
            }
            is ServerMessage.Error -> {
                if (msg.ref == null) _events.tryEmit(ClientEvent.ServerError(msg.code, msg.message))
                if (msg.code == "bad_auth") authFailed = true
            }
            is ServerMessage.Ack, is ServerMessage.Pairing, is ServerMessage.Pong, is ServerMessage.Unknown -> Unit
        }
        // Se completa la petición DESPUÉS de actualizar el estado, para que quien espera vea ya los cambios.
        val ref = msg.ref
        if (ref != null) {
            val w = lock.withLock { waiters[ref] }
            if (w != null && (msg is ServerMessage.Error || msg.type in w.expect)) w.reply.complete(msg)
        }
        return welcomed
    }

    private var autoSyncCounter = 0L

    /**
     * (Ayudante) El servidor empuja el `state` solo de la sesión activa; de las demás (p. ej. pausadas) se pide la
     * plantilla con `sync` para poder mostrarla y editarla sin esperar a activarla.
     */
    private suspend fun syncIfMissing(s: Session) {
        if (role != Role.HELPER || s.status == SessionStatus.ACTIVE || s.status == SessionStatus.REVOKED) return
        if (_helperState.value[s.id]?.rev == s.rev) return
        sender?.invoke(clientMessage("sync", "a${++autoSyncCounter}") { put("sessionId", s.id) })
    }

    /** Quita de la memoria una sesión que ya no existe para este dispositivo (borrada, o abandonada por el ayudante). */
    private fun forgetSession(sessionId: String) {
        _sessions.update { list -> list.filter { it.id != sessionId } }
        _pending.update { list -> list.filter { it.sessionId != sessionId } }
        _helperState.update { it - sessionId }
        if (role == Role.MAGO) reconcileSnapshot()
    }

    private suspend fun applyPatch(p: ServerMessage.Patch) {
        val cur = snapshot
        if (cur != null && cur.sessionId == p.sessionId && cur.rev == p.prevRev) {
            val merged = mergePatch(cur.settings, p.patch)
            setSnapshot(cur.copy(rev = p.rev, settings = merged))
            _events.tryEmit(ClientEvent.RemotePatch(p.sessionId, p.rev, p.patch))
        } else {
            // Hueco de revisiones (o parche de una sesión de la que aún no tenemos estado): se pide la instantánea completa.
            sender?.invoke(clientMessage("sync", "s${p.rev}") { put("sessionId", p.sessionId) })
        }
    }

    // ---- Instantánea del mago (persistida) ----

    @Serializable
    private data class Snapshot(val sessionId: String, val rev: Long, val expiresAt: Long? = null, val settings: JsonObject)

    private fun loadSnapshot(): Snapshot? {
        if (role != Role.MAGO) return null
        val text = store.getString(kSnapshot) ?: return null
        return try { wireJson.decodeFromString(Snapshot.serializer(), text) } catch (e: Exception) { null }
    }

    private fun effective(s: Snapshot?): JsonObject =
        if (s == null || (s.expiresAt != null && clock() >= s.expiresAt)) emptySettings else s.settings

    private fun setSnapshot(s: Snapshot?) {
        snapshot = s
        if (s == null) store.remove(kSnapshot) else store.putString(kSnapshot, wireJson.encodeToString(Snapshot.serializer(), s))
        _remoteSettings.value = effective(s)
    }

    /** Alinea la instantánea con la lista de sesiones: solo existe mientras su sesión siga activa. */
    private fun reconcileSnapshot() {
        val s = snapshot ?: return
        val active = _sessions.value.firstOrNull { it.status == SessionStatus.ACTIVE }
        when {
            active == null || active.id != s.sessionId -> setSnapshot(null)
            active.expiresAt != s.expiresAt -> setSnapshot(s.copy(expiresAt = active.expiresAt))
        }
    }

    private fun removePending(requestId: String) {
        _pending.update { list -> list.filter { it.requestId != requestId } }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 10_000L
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
