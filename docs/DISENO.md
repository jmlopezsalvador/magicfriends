# Magic Friends — diseño (borrador inicial, 2026-11-18)

Servicio online + librería cliente para que **otra persona (el ayudante) modifique en remoto, en directo, los ajustes de
predicción de una app de magia** (Chronix, ListApp, Calculadora…) que lleva el mago. Es **agnóstico**: el servidor no
conoce los ajustes de ninguna app, solo guarda y reenvía JSON.

## Decisiones tomadas

1. **El controlador vive dentro de cada app** (no hay una app aparte). Una segunda app delataría el disfraz y la pantalla de
   edición es la propia pantalla de forzaje de cada app, que escribe en la sesión remota en vez de en los ajustes locales.
   Lo común va en una librería KMP (`client/`); cada app solo aporta su lista de claves remotas y su pantalla.
   Un mismo dispositivo puede ser mago en una sesión y ayudante en otra.
2. **Cambio en vivo hasta el último instante.** El ayudante puede forzar *después* de armar (en Chronix, modo cronómetro:
   hasta el primer Parar; modo vueltas: hasta cada vuelta). La app lee el ajuste efectivo en el momento de usarlo, no al armar.
3. **Sesiones con nombre y caducidad opcional.** El mago las crea con un nombre (para tenerlas controladas) y puede darles
   caducidad o no. **Solo una sesión cloud activa a la vez** por mago: solo una persona puede modificar sus ajustes. Que una
   sesión no esté activa **no** significa que haya caducado ni que esté revocada: está *desactivada* y se puede reactivar.
   Estados: `active` (como mucho una por mago), `paused`, `expired`, `revoked`. Activar una pausa la anterior.
4. **Infraestructura: Cloudflare Workers + Durable Objects (SQLite)**, plan gratuito. Cliente solo con Ktor (sin SDK de
   terceros), para minimizar el riesgo en el CI de iOS (el fallo solo se ve en Actions, ~20 min). Plan B: Supabase
   (ojo: los proyectos gratuitos se pausan tras 1 semana sin actividad). Firebase descartado (sin SDK KMP oficial).
   Vercel no sirve de servidor WebSocket. **Verificar límites vigentes en la web de Cloudflare antes de depender de ellos.**
5. **Repo propio** (`magicfriends`) y **sesión de Claude propia** para esta parte. Las apps solo integran.
6. **Protocolo v1** (validado 2026-11-18, detalle en `protocol/PROTOCOLO.md`): WebSocket JSON con `hello` y mensajes
   con `id`/`ref`; `POST /v1/join` para canjear el código. Identidad anónima `deviceId` + `deviceKey` (el servidor solo
   guarda el hash). Un **Directory DO singleton** traduce código → mago (no guarda ajustes ni sesiones; limita intentos
   por IP). `expired` es **reversible** (quitar o alargar la caducidad la devuelve a `paused`); `revoked` es definitivo.
   Se puede **re-emparejar** una sesión: un código nuevo, al aceptarlo, sustituye al ayudante anterior. Código de
   **Secreto compartido de las apps** (`appKey` en `hello`, `APP_SECRET` como secreto de Cloudflare): se exige solo para
   registrar un mago nuevo; evita altas casuales en el servidor público (protección débil, va dentro de las apps).
   Código de emparejamiento: 6 caracteres de un alfabeto de 31 símbolos (sin 0, O, 1, I, L), un solo uso, 10 min.

## Modelo de ajustes: local, remoto y efectivo (lado app)

- Cada app declara una **lista blanca de claves remotas**. En Chronix: `forceMode`, `forceTarget`, `forcedCs`,
  `stopSubmode`, `stopCentis`, `stopFullCs` (`showTotals` por decidir, condiciona si se puede forzar el total).
  Nunca remotos: aspecto, icono, nombre, `secretHoldMs`, guía.
- La capa remota se guarda **aparte** y nunca machaca los ajustes locales.
- **Efectivo** = si Magic Friends está activado en el móvil: `remoto[clave] ?: local[clave]`; si no, solo el local.
  Son dos interruptores distintos: el del móvil (usar o no lo remoto) y el de la sesión en el servidor (activa o pausada).
- Los cambios viajan como **JSON Merge Patch** con un `rev` creciente por sesión; `null` quita el override (vuelve al local).
- La **validación la hace cada app** (en Chronix ya hay `coerceIn` en `SettingsStore.load()`). El servidor guarda JSON
  opaco con límite de tamaño y de frecuencia.
- Cambios necesarios en Chronix (sesión de Chronix, fase posterior): `Stopwatch.resetAndArm(plan)` copia el plan a campos
  privados al armar; hay que poder **actualizar el plan armado** mientras no se haya consumido, y que el solapado entre en
  `AppSettings.forcePlan()` (único sitio donde se construye el `ForcePlan`).

## Emparejamiento y seguridad

1. El mago crea una sesión (nombre, caducidad opcional) → código corto de un solo uso (caduca en unos 10 min) y QR.
2. El ayudante lo introduce o escanea → solicitud pendiente.
3. El mago la acepta → el ayudante pasa a ser controlador de esa sesión.
4. El mago puede pausar, reactivar, revocar o borrar; las sesiones con caducidad expiran solas.

- Identidad **anónima por dispositivo**, sin cuentas. Permisos aplicados en el servidor: solo el controlador de la sesión
  *activa* escribe; solo el mago aprueba, activa y revoca.
- Límite de intentos sobre el código. El payload son números; no hace falta cifrado extremo a extremo.
- **Nunca subir secretos al repo** (tokens de Cloudflare, `.dev.vars`): el repo es público de forma temporal.

## Arquitectura del servidor (validada en la fase 0)

**Un Durable Object por mago** (`MagoDO`, no por sesión). Contiene en su SQLite la lista de sesiones (nombre, caducidad,
estado, controlador) y el último ajuste de cada una. La regla «una sola activa» es una invariante local, garantizada por
un índice único parcial (`WHERE status = 'active'`). Usa la API de *WebSocket Hibernation* y una `alarm()` para la
caducidad. Un solo mago conectado y como mucho 3 conexiones de ayudante (una por dispositivo; una nueva sustituye a la anterior).
Además, un `DirectoryDO` singleton para los códigos de emparejamiento (ver decisión 6).
Verificado: tests en el runtime de Workers (`server/test`, Miniflare) y `wrangler dev` con el CLI de consola.

## Estructura del repo y compatibilidad

```
protocol/   especificación, JSON Schemas y fixtures (mensajes de ejemplo)  ← fuente de verdad
server/     Cloudflare Worker + Durable Objects (TypeScript, wrangler)
client/     librería KMP (commonMain con Ktor; androidMain/iosMain solo si hace falta)
tools/cli/  cliente de pruebas por consola (Node, sin dependencias)
docs/
```

- Los **mismos fixtures** se prueban en los tests del servidor y en los `commonTest` del cliente.
- Cada conexión lleva `v: N` de protocolo. Cambios solo aditivos; los campos desconocidos se ignoran.
- Las apps consumen `client/` con `includeBuild("../magicfriends/client")` en local. Para el workflow de iOS en GitHub
  Actions hay que decidir: publicar la librería (GitHub Packages) o hacer checkout de ambos repos.
- Versiones del cliente alineadas con las de las apps: Kotlin 2.0.21, Compose Multiplatform 1.7.1, JDK 17, iOS 15.

## Riesgos propios de un efecto de magia

- **Sin red, el efecto no puede fallar:** se guarda la última instantánea remota y nunca se espera a la red. Los
  indicadores de conexión solo existen en los ajustes secretos.
- **Aviso discreto:** vibración suave cuando llega un parche, para que el mago sepa que el ayudante ha enviado el valor.
- **Disfraz:** la app solo abre la red con Magic Friends activado. Android necesitará el permiso `INTERNET`.
- Las apps tienen la política «sin red ni cuentas salvo que el SPEC lo diga»: al integrar, actualizar `SPEC.md`,
  `CLAUDE.md`, la pantalla de Instrucciones y la guía de inicio de cada app.

## Fases

0. Protocolo + servidor + cliente de pruebas de línea de comandos (sin móviles). Validar el Durable Object por mago.
1. Librería KMP + Chronix como mago: recibe, solapa y activa/desactiva.
2. Chronix como ayudante (reutilizando la pantalla de forzaje) y lectura del ajuste efectivo en el último instante.
3. Pausa/reactivación, revocación, caducidad, caché offline y QR.
4. Segunda app (Calculadora o ListApp) para demostrar que es agnóstico.

## Pendiente de decidir

- Publicar la librería o hacer checkout doble en el CI de iOS. Ambos repos son **públicos de forma temporal** (decidido
  2026-11-18: el Actions de iOS de Chronix es gratuito solo en repos públicos), así que el checkout no necesita
  credenciales. **Al terminar, pasar a privado Chronix y magicfriends a la vez**; a partir de ahí un workflow de Chronix
  necesitaría un token de solo lectura (PAT o deploy key, como secreto del repo) para clonar `magicfriends`.
- `showTotals`: ¿remoto o no?
