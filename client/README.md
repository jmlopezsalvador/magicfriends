# Cliente KMP de Magic Friends

Librería Kotlin Multiplatform (Android, iOS y JVM para tests) para que una app hable con el servicio. Solo Ktor, sin SDK
de terceros. El contrato está en [`../protocol/PROTOCOLO.md`](../protocol/PROTOCOLO.md).

## Consumirla desde una app

En `settings.gradle.kts` de la app:

```kotlin
includeBuild("../magicfriends/client")
```

En el módulo KMP de la app (`commonMain`): `implementation("com.magicfriends:magicfriends-client")`.
Android necesita el permiso `INTERNET` **en la app** (la librería no lo declara). `ktor-client-core` es `api` (el constructor expone `HttpClient`).
Versión actual: **0.2.0** (cambios de API respecto a 0.1: `join` devuelve `JoinResult` y ya no conecta; ver «Uso (ayudante)»).

## Uso (mago)

```kotlin
val client = MagicFriendsClient(
    role = Role.MAGO,
    serverUrl = "https://…",            // base HTTP(S) del servicio
    store = miKeyValueStore,             // la app implementa KeyValueStore (SharedPreferences / NSUserDefaults)
    scope = miScope,
    appKey = "…",                        // secreto compartido de las apps (registro del mago)
)
client.start()                           // conecta y reintenta solo; sin red no pasa nada

// Ajuste efectivo, en el último instante y aunque no haya red:
val forzado = client.currentRemoteSettings()["forcedCs"]    // remoto[clave] ?: local[clave]

// Gestión de sesiones (suspend, devuelven Result):
val creada = client.createSession("Función del sábado", expiresAt = null).getOrThrow()
creada.pairing.code                      // se lo dicta al ayudante
client.acceptRequest(requestId); client.activateSession(id); client.pauseSession(id)

// Aviso discreto cuando llega un parche (vibración suave):
client.events.collect { if (it is ClientEvent.RemotePatch) vibrar() }
```

## Uso (ayudante con lista de conexiones)

Un ayudante puede estar unido a sesiones de uno o de varios magos. La librería usa **un cliente por mago**, todos con el
**mismo `KeyValueStore`** (así comparten `deviceId`/`deviceKey`). La lista de conexiones (magoId, sessionId, nombre) la guarda la app.

```kotlin
// 1. Unirse. join NO conecta ni elige mago: devuelve a qué mago y sesión se ha unido.
val joiner = MagicFriendsClient(Role.HELPER, serverUrl, store, scope)          // sin magoId: solo sirve para join
val j = joiner.join("K7QX4M", label = "Ayudante").getOrThrow()   // JoinResult(magoId, requestId, sessionId, sessionName)
miLista.add(Conexion(j.magoId, j.sessionId, j.sessionName))                        // la app decide qué guarda

// 2. Entrar en una conexión: cliente conectado a ESE mago.
val helper = MagicFriendsClient(Role.HELPER, serverUrl, store, scope, magoId = j.magoId)
helper.start()
helper.sessions                // sesiones que controlas con ese mago (estado: active/paused/expired…)
helper.helperState             // plantilla (ajustes guardados) por sesión; también de las pausadas (se pide sola con sync)
helper.sendPatch(sessionId, buildJsonObject { put("forcedCs", 4250) })   // null quita el override; vale también con la sesión pausada

// 3. Salirse (se borra la plantilla de esa sesión; la sesión sigue existiendo para el mago).
helper.leaveSession(sessionId).getOrThrow()   // evento SessionRemoved(reason = "left")
helper.stop()                                 // si era la última sesión con ese mago, quita también el cliente de tu lista
```

El estado de la solicitud llega por `ClientEvent.PairingResult` (aceptada/rechazada) en el cliente conectado a ese mago.

## Eventos

`ClientEvent`: `RemotePatch` (mago: parche aplicado; vibración discreta), `SettingsChanged` (mago: un `state` ha cambiado los ajustes de la
sesión activa, p. ej. al activarla con plantilla o al salirse el ayudante), `PairingRequested`, `PairingResult`, `SessionRemoved`
(`deleted`, `revoked`, `replaced`, `left`) y `ServerError`.

## Comportamiento garantizado

- **Offline:** la última instantánea remota se guarda en el `KeyValueStore` y se restaura al crear el cliente. Si la
  sesión tiene caducidad, `currentRemoteSettings()` la respeta con el reloj local aunque no haya red.
- **Una sola activa:** al pausar, caducar o revocar la sesión, `remoteSettings` queda vacío (solo valen los ajustes locales).
- **Plantilla en pausa:** el ayudante puede editar los ajustes de una sesión pausada; el mago no los ve hasta activarla (entonces recibe el estado completo).
- **Parches:** JSON Merge Patch con `rev`; si hay un hueco de revisiones, el cliente pide la instantánea completa.
- **Identidad:** anónima por dispositivo y rol (`deviceId`/`deviceKey` generados con aleatoriedad segura y guardados en el store).
- El cliente no valida los ajustes: lo hace cada app.

## Desarrollo

```
cd client
./gradlew jvmTest                       # tests comunes + fixtures (los mismos que el servidor)
./gradlew compileDebugKotlinAndroid     # compila el destino Android
MF_SERVER_URL=http://localhost:8787 ./gradlew jvmTest   # además, prueba de extremo a extremo (servidor en marcha: cd ../server && npx wrangler dev)
```

El destino iOS (`iosMain`: `SecRandomCopyBytes` y el motor Darwin) **solo se compila en un Mac** (Actions de la app).
`generateFixtures` convierte `../protocol/fixtures` en constantes de `commonTest`.
