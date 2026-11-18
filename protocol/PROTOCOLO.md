# Protocolo Magic Friends — v1

Fuente de verdad del contrato entre servidor y clientes. Los tipos exactos están en
[`schema/messages.schema.json`](schema/messages.schema.json); los ejemplos y escenarios, en [`fixtures/`](fixtures/).
**Cambios solo aditivos.** Los campos desconocidos se ignoran. Si hay que romper compatibilidad, se sube `v`.

## Roles

- **Mago** (`role: "mago"`): dueño de la app. Su `deviceId` es el `magoId` y da nombre a su Durable Object.
- **Ayudante** (`role: "helper"`): controlador de una sesión. Un mismo dispositivo puede ser mago en unas y ayudante en otras.
- Identidad **anónima por dispositivo**: `deviceId` (aleatorio, 16–64 caracteres `[A-Za-z0-9_-]`) y `deviceKey`
  (aleatoria, ≥ 32 caracteres, p. ej. 32 bytes en base64url). El servidor solo guarda `sha256(deviceKey)`.
- La primera conexión de un `magoId` registra su clave (*trust on first use*); a partir de ahí hay que presentar la misma.
- **Secreto compartido de las apps (`appKey`, opcional en `hello`).** Si el servidor tiene configurado `APP_SECRET`,
  exige `appKey` igual a él solo para **registrar un mago nuevo**; si no coincide, `error bad_auth`. Los magos ya
  registrados y los ayudantes no lo necesitan. Sin `APP_SECRET` (desarrollo local) no se exige. No es una protección
  fuerte (va dentro de las apps): solo evita altas casuales en un servidor público.

## Transporte

| Ruta | Descripción |
|---|---|
| `POST /v1/join` | El ayudante canjea un código de emparejamiento. Cuerpo `JoinRequestBody`, respuesta `JoinResponseBody`. |
| `GET /v1/ws?m=<magoId>` | Upgrade a WebSocket. Frames de texto JSON. Tras conectar, el primer mensaje debe ser `hello`. |

Errores HTTP: cuerpo `{code, message}` con el mismo `ErrorCode`. Estado: `bad_message` 400, `bad_code` 404,
`not_active`/`expired` 409, `rate_limited`/`limit_reached` 429.

Todos los mensajes WebSocket son `{ "v": 1, "t": "<tipo>", "id"?, "ref"?, ... }`. `id` lo pone quien pide algo;
la respuesta lleva `ref` con ese `id`. Los comandos del cliente (salvo `hello` y `ping`) exigen `id`.
Un mensaje inválido recibe `error` (`bad_message`; `unsupported_version` si `v` no es 1), sin cerrar la conexión.

## Estados de una sesión

`active` (como mucho una por mago) · `paused` · `expired` · `revoked`.

- Se crea en `paused`. Activar una pausa la activa anterior (invariante garantizada por la base de datos).
- `expired`: se alcanza `expiresAt` (la comprueba una alarma del Durable Object). **Reversible**: `session.update` con
  `expiresAt` nulo o futuro la devuelve a `paused`. No se puede activar ni parchear estando caducada.
- `revoked`: **definitivo**; el controlador pierde el acceso.
- Pausada no es caducada ni revocada: se puede reactivar.

## Mensajes del cliente

| `t` | Quién | Campos | Respuesta |
|---|---|---|---|
| `hello` | ambos | `role`, `deviceId`, `deviceKey`, `knownRevs?` | `welcome` (+ `state` de la sesión activa) o `error bad_auth` y cierre |
| `session.create` | mago | `name`, `expiresAt?` | `session` y `pairing` (ambos con `ref`) |
| `pairing.code` | mago | `sessionId` | `pairing` |
| `pairing.accept` / `pairing.reject` | mago | `requestId` | `session` (accept) / `ack` (reject) |
| `session.activate` | mago | `sessionId` | ver abajo |
| `session.pause` | mago | `sessionId` | `session` |
| `session.revoke` | mago | `sessionId` | `session` |
| `session.update` | mago | `sessionId`, `name?`, `expiresAt?` | `session` |
| `session.delete` | mago | `sessionId` | `ack` |
| `patch` | ayudante | `sessionId`, `patch` | `ack` con `rev` |
| `sync` | ambos | `sessionId` | `state` |
| `ping` | ambos | — | `pong` |

El mago solo ve sus sesiones; el ayudante, solo aquellas de las que es controlador.

## Mensajes del servidor

`welcome`, `ack`, `error`, `state`, `patch`, `session`, `session.removed`, `pairing`, `pairing.request`,
`pairing.result`, `pong`. Orden de los envíos derivados de cada comando (lo fijan los escenarios):

- `hello`: `welcome` y después un `state` por cada sesión **activa** cuya `rev` difiera de `knownRevs`.
- `pairing.accept`: mago ← `session`. Ayudante nuevo ← `pairing.result`, `session` (y `state` si la sesión está activa).
  Ayudante anterior (re-emparejado) ← `session.removed` (`replaced`).
- `session.activate`: mago ← `session` de la sesión pausada (si había), `session` de la activada (con `ref`), `state`.
  Cada controlador afectado ← su `session` (y `state` el de la activada).
- `session.revoke`: mago ← `session`; controlador ← `session.removed` (`revoked`).
- `session.delete`: mago ← `ack`; controlador ← `session.removed` (`deleted`).
- `patch` válido: ayudante ← `ack`; mago ← `patch`.
- Caducidad: mago y controlador ← `session` con `status: "expired"`.

## Ajustes y parches

- El servidor guarda un objeto JSON opaco por sesión y nunca interpreta sus claves.
- `patch` es un **JSON Merge Patch (RFC 7396)**: `null` quita la clave (la app vuelve al valor local), los objetos se
  fusionan y los arrays se sustituyen enteros. Cada parche sube `rev` en 1.
- El mago aplica `patch` si `prevRev` coincide con su `rev` local; si no, envía `sync` y sustituye todo por el `state`.
- Solo se acepta `patch` del controlador de una sesión `active`: si no, `forbidden` (no es el controlador),
  `not_active` o `expired`.

## Emparejamiento

1. El mago crea la sesión (`session.create`) → `pairing` con un código de 6 caracteres del alfabeto
   `23456789ABCDEFGHJKMNPQRSTUVWXYZ` (sin 0, O, 1, I, L), de un solo uso, que caduca a los 10 minutos.
   `pairing.code` genera otro para una sesión existente (también sirve para **re-emparejar**: al aceptar, el nuevo
   ayudante sustituye al anterior). Un código nuevo invalida el anterior de esa sesión.
2. El ayudante hace `POST /v1/join` con el código (se normaliza a mayúsculas), su `deviceId`, `deviceKey` y una etiqueta.
   Recibe `magoId` y `requestId`; el código queda consumido. Puede conectar por WebSocket mientras la solicitud está pendiente.
3. El mago recibe `pairing.request` (o la ve en `welcome.pending` si estaba desconectado) y envía `pairing.accept`/`reject`.

## Límites

| Concepto | Límite |
|---|---|
| Tamaño de un `patch` / de los `settings` completos | 4 KB / 16 KB |
| Frecuencia de `patch` | 10 por segundo y conexión (`rate_limited`) |
| Sesiones por mago | 20 (`limit_reached`) |
| Solicitudes de emparejamiento pendientes por sesión | 5 |
| Intentos de código fallidos | 10 por IP cada 10 minutos (`rate_limited`) |
| Conexiones simultáneas | un mago; ayudantes: una por dispositivo, máximo 3. Una nueva del mismo rol/dispositivo sustituye a la anterior |

## Compatibilidad y pruebas

- `messages.json` (casos válidos e inválidos) y `scenarios/*.json` (conversaciones completas) los ejecutan el servidor
  (`server/test`) y, más adelante, `commonTest` del cliente KMP.
- Formato de escenario: `actors` (`role`, y opcionalmente `as`, `key`, `mago`), y `steps` con `hello`, `send`, `expect`,
  `join` + `expectHttp`, `close`, `expectClose`, `expectNothing`, `sleep`. En los valores: `"$nombre"` sustituye una
  captura, `"$>nombre"` captura el valor recibido, `"$any"` acepta cualquier valor, `"$absent"` exige que la clave no
  exista, `"$now+N"` es la hora actual más N ms. Los objetos esperados se comparan como subconjunto.
  Cada mensaje recibido debe consumirse con un `expect`, en orden.
