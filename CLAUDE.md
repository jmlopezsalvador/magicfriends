# Magic Friends

Servicio online (Cloudflare Workers + Durable Objects) y librería cliente KMP para que un ayudante modifique en remoto,
en directo, los ajustes de predicción de las apps de magia del usuario (Chronix, ListApp, Calculadora…).
Repo: https://github.com/jmlopezsalvador/magicfriends.git
El usuario habla español: responde y comenta el código en español.

## Punto de partida
Lee **`docs/DISENO.md`**: recoge las decisiones ya tomadas (controlador dentro de cada app, cambio en vivo hasta el último
instante, sesiones con nombre y caducidad opcional con una sola activa, Cloudflare como infraestructura, fases).
No re-discutir lo decidido; ante un choque, consultar al usuario.

## Reglas para Claude
- **No hacer commit ni push sin que el usuario lo pida.** No commitear si algo no compila o los tests fallan.
- **El repo es público de forma temporal** (se pasará a privado junto con Chronix al terminar): **nunca escribir secretos**
  (tokens de Cloudflare, claves, `.dev.vars`). Comprobar el `.gitignore` antes de commitear.
- **No desplegar a producción (Cloudflare) sin que el usuario lo pida.**
- `protocol/` es la fuente de verdad del contrato. Cambios solo aditivos; si hay que romper compatibilidad, subir la versión
  de protocolo y preguntar. Servidor y cliente se prueban con los mismos fixtures de `protocol/fixtures/`.
- El servidor es **agnóstico**: guarda y reenvía JSON opaco, nunca conoce los ajustes de ninguna app.
- No inventar funcionalidad: la fuente de verdad es `docs/DISENO.md` y lo que pida el usuario. Ante la duda, preguntar.
- Mantener este `CLAUDE.md` y `docs/DISENO.md` al día en la misma tarea en que cambien las decisiones.

## Relación con las apps
Cada app (carpeta hermana, p. ej. `C:\Users\jmlop\Chronix`) consume `client/` con `includeBuild("../magicfriends/client")`.
La integración en cada app (solapado de ajustes, pantalla de controlador, instrucciones) se hace en la sesión de esa app,
no aquí. Aquí solo se desarrolla el servicio, el protocolo y la librería.

## Reglas de código (cliente KMP)
- `commonMain`: nada de `java.*` ni `android.*`; Ktor para red y WebSocket; `kotlinx-datetime`; sin `String.format`/`Locale`.
- Lo del sistema va con `expect`/`actual`. El cliente debe compilar para iOS (el fallo solo se ve en Actions, ~20 min).
- Versiones alineadas con las apps: Kotlin 2.0.21, Compose Multiplatform 1.7.1, JDK 17, iOS mínimo 15.0.
- En esta máquina (Windows) los heredocs de Bash con contenido complejo fallan: escribe los scripts con la herramienta Write.
