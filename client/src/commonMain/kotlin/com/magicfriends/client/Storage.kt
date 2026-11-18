package com.magicfriends.client

/**
 * Almacenamiento clave-valor que aporta cada app (SharedPreferences, NSUserDefaults…). La librería guarda aquí la
 * identidad anónima del dispositivo y la última instantánea de ajustes remotos (para que sin red el efecto no falle).
 * Las operaciones deben ser rápidas y no bloquear.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/** Almacenamiento en memoria (tests y herramientas). No sobrevive al proceso. */
class InMemoryKeyValueStore : KeyValueStore {
    private val map = HashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}
