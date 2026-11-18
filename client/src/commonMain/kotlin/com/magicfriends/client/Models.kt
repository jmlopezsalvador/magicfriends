package com.magicfriends.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Rol de este dispositivo en una conexión. Un mismo dispositivo puede usar un cliente de cada rol. */
enum class Role(internal val wire: String) { MAGO("mago"), HELPER("helper") }

@Serializable
enum class SessionStatus {
    @SerialName("active") ACTIVE,
    @SerialName("paused") PAUSED,
    @SerialName("expired") EXPIRED,
    @SerialName("revoked") REVOKED,
}

/** Sesión de control (nombre, estado, caducidad opcional y controlador). Las fechas son epoch en milisegundos. */
@Serializable
data class Session(
    val id: String,
    val name: String,
    val status: SessionStatus,
    val expiresAt: Long? = null,
    val hasController: Boolean,
    val controllerLabel: String? = null,
    val rev: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Solicitud de emparejamiento pendiente de que el mago la acepte. */
@Serializable
data class PendingRequest(
    val requestId: String,
    val sessionId: String,
    val label: String? = null,
    val at: Long,
)

/** Código de un solo uso para que el ayudante se una a una sesión. */
data class PairingCode(val sessionId: String, val code: String, val expiresAt: Long)

data class CreatedSession(val session: Session, val pairing: PairingCode)

/** Instantánea de los ajustes de una sesión tal como la guarda el servidor. */
data class RemoteState(val rev: Long, val settings: kotlinx.serialization.json.JsonObject)

sealed interface ConnectionState {
    /** Sin iniciar, o un ayudante que aún no se ha emparejado con ningún mago. */
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    /** Conexión caída; se reintenta sola con espera creciente. */
    data object Disconnected : ConnectionState
    /** El servidor rechazó las credenciales (p. ej. ayudante revocado o `appKey` incorrecta). No se reintenta. */
    data object AuthFailed : ConnectionState
}

sealed interface ClientEvent {
    /** (Mago) Se aplicó un parche del ayudante: momento para la vibración discreta. */
    data class RemotePatch(val sessionId: String, val rev: Long, val patch: kotlinx.serialization.json.JsonObject) : ClientEvent
    /** (Mago) Un ayudante pide unirse a una sesión. */
    data class PairingRequested(val request: PendingRequest) : ClientEvent
    /** (Ayudante) El mago aceptó o rechazó la solicitud. */
    data class PairingResult(val requestId: String, val accepted: Boolean, val sessionId: String?) : ClientEvent
    /** La sesión ya no es visible para este dispositivo (`deleted`, `revoked` o `replaced`). */
    data class SessionRemoved(val sessionId: String, val reason: String) : ClientEvent
    /** Error no asociado a una petición concreta. */
    data class ServerError(val code: String, val message: String) : ClientEvent
}

/** Error devuelto por el servidor (`code` del protocolo) o local (`not_connected`, `timeout`, `bad_response`). */
class MfException(val code: String, message: String) : Exception("$code: $message")
