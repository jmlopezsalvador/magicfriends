package com.magicfriends.client

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal const val PROTOCOL_VERSION = 1

internal val wireJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/** Mensajes del servidor (protocolo v1, ver protocol/PROTOCOLO.md). Los campos desconocidos se ignoran. */
internal sealed class ServerMessage {
    abstract val type: String
    abstract val ref: String?

    class Welcome(override val ref: String?, val role: String, val serverTime: Long, val sessions: List<Session>, val pending: List<PendingRequest>) : ServerMessage() { override val type = "welcome" }
    class Ack(override val ref: String?, val rev: Long?) : ServerMessage() { override val type = "ack" }
    class Error(override val ref: String?, val code: String, val message: String) : ServerMessage() { override val type = "error" }
    class State(override val ref: String?, val sessionId: String, val rev: Long, val settings: JsonObject) : ServerMessage() { override val type = "state" }
    class Patch(override val ref: String?, val sessionId: String, val rev: Long, val prevRev: Long, val patch: JsonObject) : ServerMessage() { override val type = "patch" }
    class SessionMsg(override val ref: String?, val session: Session) : ServerMessage() { override val type = "session" }
    class SessionRemoved(override val ref: String?, val sessionId: String, val reason: String) : ServerMessage() { override val type = "session.removed" }
    class Pairing(override val ref: String?, val sessionId: String, val code: String, val expiresAt: Long) : ServerMessage() { override val type = "pairing" }
    class PairingRequest(override val ref: String?, val request: PendingRequest) : ServerMessage() { override val type = "pairing.request" }
    class PairingResult(override val ref: String?, val requestId: String, val accepted: Boolean, val sessionId: String?) : ServerMessage() { override val type = "pairing.result" }
    class Pong(override val ref: String?) : ServerMessage() { override val type = "pong" }
    /** Tipo que esta versión del cliente no conoce (compatibilidad hacia delante). */
    class Unknown(override val type: String, override val ref: String?) : ServerMessage()
}

/** Devuelve null si el texto no es un mensaje bien formado (campos obligatorios ausentes o de tipo erróneo). */
internal fun parseServerMessage(text: String): ServerMessage? {
    return try {
        val o = wireJson.parseToJsonElement(text).jsonObject
        val t = o.getValue("t").jsonPrimitive.content
        val ref = o["ref"]?.jsonPrimitive?.contentOrNull
        when (t) {
            "welcome" -> ServerMessage.Welcome(
                ref, o.getValue("role").jsonPrimitive.content, o.getValue("serverTime").jsonPrimitive.long,
                wireJson.decodeFromJsonElement(ListSerializer(Session.serializer()), o.getValue("sessions")),
                wireJson.decodeFromJsonElement(ListSerializer(PendingRequest.serializer()), o.getValue("pending")),
            )
            "ack" -> ServerMessage.Ack(ref, o["rev"]?.jsonPrimitive?.long)
            "error" -> ServerMessage.Error(ref, o.getValue("code").jsonPrimitive.content, o.getValue("message").jsonPrimitive.content)
            "state" -> ServerMessage.State(ref, o.str("sessionId"), o.getValue("rev").jsonPrimitive.long, o.getValue("settings").jsonObject)
            "patch" -> ServerMessage.Patch(ref, o.str("sessionId"), o.getValue("rev").jsonPrimitive.long, o.getValue("prevRev").jsonPrimitive.long, o.getValue("patch").jsonObject)
            "session" -> ServerMessage.SessionMsg(ref, wireJson.decodeFromJsonElement(Session.serializer(), o.getValue("session")))
            "session.removed" -> ServerMessage.SessionRemoved(ref, o.str("sessionId"), o.str("reason"))
            "pairing" -> ServerMessage.Pairing(ref, o.str("sessionId"), o.str("code"), o.getValue("expiresAt").jsonPrimitive.long)
            "pairing.request" -> ServerMessage.PairingRequest(ref, wireJson.decodeFromJsonElement(PendingRequest.serializer(), o.getValue("request")))
            "pairing.result" -> ServerMessage.PairingResult(ref, o.str("requestId"), o.getValue("accepted").jsonPrimitive.boolean, o["sessionId"]?.jsonPrimitive?.contentOrNull)
            "pong" -> ServerMessage.Pong(ref)
            else -> ServerMessage.Unknown(t, ref)
        }
    } catch (e: Exception) {
        null
    }
}

private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

/** Construye un mensaje del cliente: `{"v":1,"t":tipo,"id":id,...}`. */
internal fun clientMessage(type: String, id: String? = null, build: JsonObjectBuilder.() -> Unit = {}): String =
    buildJsonObject {
        put("v", PROTOCOL_VERSION)
        put("t", type)
        if (id != null) put("id", id)
        build()
    }.toString()

internal fun JsonObjectBuilder.putNullable(key: String, value: Long?) {
    if (value == null) put(key, kotlinx.serialization.json.JsonNull) else put(key, JsonPrimitive(value))
}
