package com.magicfriends.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class MergePatchTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun fusionaObjetosQuitaConNullYSustituyeArrays() {
        val target = obj("""{"a":1,"b":{"c":2,"d":3},"e":[1,2]}""")
        val patch = obj("""{"b":{"c":null,"x":9},"e":[3],"f":null}""")
        assertEquals(obj("""{"a":1,"b":{"d":3,"x":9},"e":[3]}"""), mergePatch(target, patch))
    }

    @Test
    fun noModificaElOriginal() {
        val target = obj("""{"a":1}""")
        mergePatch(target, obj("""{"a":null}"""))
        assertEquals(obj("""{"a":1}"""), target)
    }

    @Test
    fun unValorNoObjetoSustituyeTodo() {
        assertEquals(Json.parseToJsonElement("5"), mergePatch(obj("""{"a":1}"""), Json.parseToJsonElement("5")))
    }
}
