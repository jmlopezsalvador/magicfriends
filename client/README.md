# Cliente KMP de Magic Friends

Librería Kotlin Multiplatform (Android, iOS y JVM para tests) para que una app hable con el servicio. Solo Ktor, sin SDK
de terceros. El contrato está en [`../protocol/PROTOCOLO.md`](../protocol/PROTOCOLO.md).

## Consumirla desde una app

En `settings.gradle.kts` de la app:

```kotlin
includeBuild("../magicfriends/client")
```

En el módulo KMP de la app (`commonMain`): `implementation("com.magicfriends:magicfriends-client")`.
Android necesita el permiso `INTERNET` **en la app** (la librería no lo declara).

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

## Uso (ayudante)

```kotlin
val helper = MagicFriendsClient(Role.HELPER, serverUrl, store, scope)
helper.join("K7QX4M", label = "Ayudante").getOrThrow()      // queda pendiente de que el mago acepte
helper.sendPatch(sessionId, buildJsonObject { put("forcedCs", 4250) })   // null quita el override
helper.helperState                                           // último estado conocido por sesión
```

## Comportamiento garantizado

- **Offline:** la última instantánea remota se guarda en el `KeyValueStore` y se restaura al crear el cliente. Si la
  sesión tiene caducidad, `currentRemoteSettings()` la respeta con el reloj local aunque no haya red.
- **Una sola activa:** al pausar, caducar o revocar la sesión, `remoteSettings` queda vacío (solo valen los ajustes locales).
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
