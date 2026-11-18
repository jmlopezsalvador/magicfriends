package com.magicfriends.client

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * JSON Merge Patch (RFC 7396): `null` quita la clave, los objetos se fusionan y cualquier otro valor
 * (incluidos los arrays) sustituye al anterior. Misma semántica que el servidor.
 */
fun mergePatch(target: JsonElement?, patch: JsonElement): JsonElement {
    if (patch !is JsonObject) return patch
    val result = LinkedHashMap<String, JsonElement>()
    if (target is JsonObject) result.putAll(target)
    for ((key, value) in patch) {
        if (value is JsonNull) result.remove(key) else result[key] = mergePatch(result[key], value)
    }
    return JsonObject(result)
}

fun mergePatch(target: JsonObject, patch: JsonObject): JsonObject = mergePatch(target as JsonElement, patch as JsonElement) as JsonObject
