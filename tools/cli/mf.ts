// Cliente de pruebas por consola de Magic Friends (fase 0). Sin dependencias: Node ≥ 22.18 (ejecuta .ts directamente).
//
//   node tools/cli/mf.ts mago     [--perfil mago]     [--url http://localhost:8787]
//   node tools/cli/mf.ts ayudante [--perfil ayudante] [--url http://localhost:8787]
//
// La identidad (deviceId + deviceKey) de cada perfil se guarda en ~/.magicfriends-cli/<perfil>.json (fuera del repo).
import { createInterface } from "node:readline/promises";
import { randomBytes, randomUUID } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import type { SessionInfo } from "../../server/src/protocol.ts";
import { mergePatch } from "../../server/src/util.ts";

type Msg = { t: string; [k: string]: any }; // eslint-disable-line @typescript-eslint/no-explicit-any
interface Profile { deviceId: string; deviceKey: string; magoId?: string }

// ---- Argumentos y perfil -------------------------------------------------------------------

const [, , rolArg, ...rest] = process.argv;
const flag = (name: string, def: string) => {
  const i = rest.indexOf(`--${name}`);
  return i >= 0 && rest[i + 1] ? rest[i + 1] : def;
};
if (rolArg !== "mago" && rolArg !== "ayudante") {
  console.error("Uso: node tools/cli/mf.ts <mago|ayudante> [--perfil nombre] [--url http://localhost:8787]");
  process.exit(1);
}
const role = rolArg === "mago" ? "mago" : "helper";
const perfilName = flag("perfil", rolArg);
const baseUrl = flag("url", "http://localhost:8787").replace(/\/$/, "");

const dir = join(homedir(), ".magicfriends-cli");
const file = join(dir, `${perfilName}.json`);
mkdirSync(dir, { recursive: true });
const b64url = (n: number) => randomBytes(n).toString("base64url");
let profile: Profile = existsSync(file)
  ? JSON.parse(readFileSync(file, "utf8"))
  : { deviceId: `dev-${randomUUID()}`, deviceKey: b64url(32) };
const save = () => writeFileSync(file, JSON.stringify(profile, null, 2));
save();

// Secreto compartido de las apps (solo hace falta para registrar un mago nuevo en un servidor que lo exija).
// Orden: --app-key, variable MF_APP_KEY o el archivo ~/.magicfriends-cli/app-key.txt (fuera del repo).
const appKeyFile = join(dir, "app-key.txt");
const appKey = flag("app-key", process.env.MF_APP_KEY ?? (existsSync(appKeyFile) ? readFileSync(appKeyFile, "utf8").trim() : "")) || undefined;

// ---- Estado local --------------------------------------------------------------------------

let sessions: SessionInfo[] = [];
let pending: { requestId: string; sessionId: string; label: string | null }[] = [];
const state = new Map<string, { rev: number; settings: Record<string, unknown> }>();
let ws: WebSocket | undefined;
let reqN = 0;
const nextId = () => `cli-${++reqN}`;
const wsUrl = () => baseUrl.replace(/^http/, "ws") + `/v1/ws?m=${role === "mago" ? profile.deviceId : profile.magoId}`;

const hora = (ms: number) => new Date(ms).toLocaleTimeString("es-ES");
const dim = (s: string) => `\x1b[2m${s}\x1b[0m`;
const log = (s: string) => { process.stdout.write(`\r\x1b[K${s}\n`); rl.prompt(true); };
const nombre = (id: string) => {
  const i = sessions.findIndex((s) => s.id === id);
  return i >= 0 ? `#${i + 1} «${sessions[i].name}»` : id.slice(0, 8);
};
const describe = (s: SessionInfo) =>
  `${nombre(s.id)} [${s.status}]${s.expiresAt ? ` caduca ${hora(s.expiresAt)}` : ""}` +
  `${s.hasController ? ` controlador: ${s.controllerLabel ?? "?"}` : " sin controlador"} rev ${s.rev}`;

function upsert(s: SessionInfo) {
  const i = sessions.findIndex((x) => x.id === s.id);
  if (i >= 0) sessions[i] = s; else sessions.push(s);
}

function send(msg: Msg) {
  if (!ws || ws.readyState !== WebSocket.OPEN) return log("No hay conexión");
  ws.send(JSON.stringify({ v: 1, ...msg }));
}
const cmd = (m: Msg) => send({ id: nextId(), ...m });

// ---- Mensajes del servidor -----------------------------------------------------------------

function onMessage(m: Msg) {
  switch (m.t) {
    case "welcome":
      sessions = m.sessions; pending = m.pending;
      log(`Conectado como ${role === "mago" ? "mago" : "ayudante"}. ${sessions.length} sesiones, ${pending.length} solicitudes pendientes.`);
      break;
    case "session":
      upsert(m.session); log(`Sesión: ${describe(m.session)}`);
      break;
    case "session.removed":
      sessions = sessions.filter((s) => s.id !== m.sessionId); state.delete(m.sessionId);
      log(`Sesión ${m.sessionId.slice(0, 8)} retirada (${m.reason})`);
      break;
    case "pairing":
      log(`Código para ${nombre(m.sessionId)}: \x1b[1m${m.code}\x1b[0m (caduca ${hora(m.expiresAt)})`);
      break;
    case "pairing.request":
      pending.push(m.request);
      log(`Solicitud #${pending.length}: «${m.request.label ?? "sin etiqueta"}» quiere controlar ${nombre(m.request.sessionId)}`);
      break;
    case "pairing.result":
      log(m.accepted ? "El mago aceptó tu solicitud." : "El mago rechazó tu solicitud.");
      break;
    case "state":
      state.set(m.sessionId, { rev: m.rev, settings: m.settings });
      log(`Estado de ${nombre(m.sessionId)} (rev ${m.rev}): ${JSON.stringify(m.settings)}`);
      break;
    case "patch": {
      const cur = state.get(m.sessionId);
      if (cur && cur.rev === m.prevRev) {
        const settings = mergePatch(cur.settings, m.patch) as Record<string, unknown>;
        state.set(m.sessionId, { rev: m.rev, settings });
        log(`\x1b[32mParche rev ${m.rev}\x1b[0m en ${nombre(m.sessionId)}: ${JSON.stringify(m.patch)} → efectivo ${JSON.stringify(settings)}`);
      } else {
        log(`Hueco de revisiones (local ${cur?.rev ?? "?"}, parche ${m.prevRev}→${m.rev}): pido sync`);
        cmd({ t: "sync", sessionId: m.sessionId });
      }
      break;
    }
    case "ack": log(dim(`ack ${m.ref}${m.rev !== undefined ? ` rev ${m.rev}` : ""}`)); break;
    case "pong": log(dim("pong")); break;
    case "error": log(`\x1b[31mError ${m.code}\x1b[0m: ${m.message}`); break;
    default: log(dim(JSON.stringify(m)));
  }
}

function connect() {
  const url = wsUrl();
  ws = new WebSocket(url);
  ws.addEventListener("open", () =>
    send({ t: "hello", role, deviceId: profile.deviceId, deviceKey: profile.deviceKey, appKey: role === "mago" ? appKey : undefined,
      knownRevs: Object.fromEntries([...state].map(([id, s]) => [id, s.rev])) }));
  ws.addEventListener("message", (e) => onMessage(JSON.parse(String(e.data))));
  ws.addEventListener("close", (e) => {
    log(`Conexión cerrada (${e.code}${e.reason ? ` ${e.reason}` : ""})`);
    if (e.code !== 4001 && !closing) setTimeout(connect, 2000);
  });
  ws.addEventListener("error", () => log(`No se pudo conectar a ${url}`));
}

// ---- REPL ----------------------------------------------------------------------------------

let closing = false;
const rl = createInterface({ input: process.stdin, output: process.stdout, prompt: `${rolArg}> ` });

const sesion = (arg: string | undefined): SessionInfo | undefined => {
  const i = Number(arg);
  const s = Number.isInteger(i) ? sessions[i - 1] : sessions.find((x) => x.id === arg);
  if (!s) log("Sesión no encontrada (usa el número de «sesiones»)");
  return s;
};
const solicitud = (arg: string | undefined) => {
  const p = pending[Number(arg) - 1];
  if (!p) log("Solicitud no encontrada (usa el número de «pendientes»)");
  return p;
};
const jsonArg = (text: string): Record<string, unknown> | undefined => {
  try {
    const v = JSON.parse(text);
    if (v && typeof v === "object" && !Array.isArray(v)) return v;
  } catch { /* se avisa abajo */ }
  log("Se esperaba un objeto JSON, p. ej. {\"forcedCs\":4250}");
};

const AYUDA_MAGO = `Comandos del mago:
  crear <nombre> [minutos]   crea una sesión (caducidad opcional en minutos) y muestra su código
  sesiones | pendientes      lista sesiones / solicitudes
  codigo <n>                 código nuevo para la sesión n (también sirve para re-emparejar)
  aceptar <n> | rechazar <n> solicitud n de «pendientes»
  activar <n> | pausar <n> | revocar <n> | borrar <n>
  caducidad <n> <minutos|no> cambia o quita la caducidad
  nombre <n> <texto>         renombra
  estado <n>                 pide los ajustes actuales (sync)
  ping | ayuda | salir (cierra el programa)`;
const AYUDA_AYUDANTE = `Comandos del ayudante:
  unirse <codigo> [etiqueta] canjea un código de emparejamiento y conecta
  sesiones | estado <n>      lista sesiones / pide los ajustes (sync)
  poner <clave> <json>       parche de una clave, p. ej. poner forcedCs 4250
  quitar <clave>             quita el override (null)
  salir <n>                  abandona la sesión n: se borra su plantilla (sin número, cierra el programa)
  patch <json>               parche completo, p. ej. patch {"forceMode":"target","forcedCs":4250}
  ping | ayuda`;

async function unirse(code: string, label: string | undefined) {
  const res = await fetch(`${baseUrl}/v1/join`, {
    method: "POST",
    body: JSON.stringify({ code: code.toUpperCase(), deviceId: profile.deviceId, deviceKey: profile.deviceKey, label }),
  });
  const body = (await res.json()) as { magoId?: string; requestId?: string; sessionId?: string; sessionName?: string; code?: string; message?: string };
  if (!res.ok) return log(`\x1b[31mNo se pudo unir (${res.status} ${body.code})\x1b[0m: ${body.message}`);
  profile = { ...profile, magoId: body.magoId };
  save();
  log(`Solicitud enviada para «${body.sessionName ?? "?"}»; esperando a que el mago la acepte.`);
  ws?.close();
  connect();
}

async function handle(line: string) {
  const [c, ...args] = line.trim().split(/\s+/);
  const resto = line.trim().slice(c.length).trim();
  switch (c) {
    case "": return;
    case "ayuda": return log(role === "mago" ? AYUDA_MAGO : AYUDA_AYUDANTE);
    case "salir": {
      // Ayudante: «salir <n>» abandona la sesión n (se borra su plantilla). Sin número, cierra el programa.
      if (role === "helper" && args[0]) {
        const s = sesion(args[0]);
        return s && cmd({ t: "session.leave", sessionId: s.id });
      }
      closing = true; ws?.close(); rl.close(); process.exit(0); break;
    }
    case "ping": return cmd({ t: "ping" });
    case "sesiones":
      return log(sessions.length ? sessions.map((s) => `  ${describe(s)}`).join("\n") : "  (ninguna)");
    case "estado": { const s = sesion(args[0]); return s && cmd({ t: "sync", sessionId: s.id }); }
  }
  if (role === "mago") {
    switch (c) {
      case "crear": {
        const minutos = Number(args[args.length - 1]);
        const conCaducidad = args.length > 1 && Number.isFinite(minutos);
        const name = (conCaducidad ? args.slice(0, -1) : args).join(" ");
        if (!name) return log("Uso: crear <nombre> [minutos]");
        return cmd({ t: "session.create", name, expiresAt: conCaducidad ? Date.now() + minutos * 60_000 : null });
      }
      case "pendientes":
        return log(pending.length ? pending.map((p, i) => `  #${i + 1} «${p.label ?? "?"}» → ${nombre(p.sessionId)}`).join("\n") : "  (ninguna)");
      case "codigo": { const s = sesion(args[0]); return s && cmd({ t: "pairing.code", sessionId: s.id }); }
      case "aceptar": case "rechazar": {
        const p = solicitud(args[0]);
        if (!p) return;
        pending = pending.filter((x) => x !== p);
        return cmd({ t: c === "aceptar" ? "pairing.accept" : "pairing.reject", requestId: p.requestId });
      }
      case "activar": case "pausar": case "revocar": case "borrar": {
        const s = sesion(args[0]);
        const t = { activar: "session.activate", pausar: "session.pause", revocar: "session.revoke", borrar: "session.delete" }[c]!;
        return s && cmd({ t, sessionId: s.id });
      }
      case "caducidad": {
        const s = sesion(args[0]);
        if (!s) return;
        const expiresAt = args[1] === "no" ? null : Date.now() + Number(args[1]) * 60_000;
        if (expiresAt !== null && !Number.isFinite(expiresAt)) return log("Uso: caducidad <n> <minutos|no>");
        return cmd({ t: "session.update", sessionId: s.id, expiresAt });
      }
      case "nombre": {
        const s = sesion(args[0]);
        const name = args.slice(1).join(" ");
        return s && name ? cmd({ t: "session.update", sessionId: s.id, name }) : log("Uso: nombre <n> <texto>");
      }
    }
  } else {
    const activa = () => sessions.find((s) => s.status === "active") ?? sessions[0];
    const enviar = (patch: Record<string, unknown>) => {
      const s = activa();
      return s ? cmd({ t: "patch", sessionId: s.id, patch }) : log("No controlas ninguna sesión todavía");
    };
    switch (c) {
      case "unirse": return args[0] ? unirse(args[0], args.slice(1).join(" ") || undefined) : log("Uso: unirse <codigo> [etiqueta]");
      case "poner": {
        const valor = (() => { try { return JSON.parse(args.slice(1).join(" ")); } catch { return undefined; } })();
        return args[0] && valor !== undefined ? enviar({ [args[0]]: valor }) : log("Uso: poner <clave> <json>");
      }
      case "quitar": return args[0] ? enviar({ [args[0]]: null }) : log("Uso: quitar <clave>");
      case "patch": { const p = jsonArg(resto); return p && enviar(p); }
    }
  }
  log(`Comando desconocido: ${c}. Escribe «ayuda».`);
}

console.log(`Magic Friends CLI · perfil «${perfilName}» · ${baseUrl}\ndeviceId: ${profile.deviceId}\nEscribe «ayuda».`);
if (role === "mago" || profile.magoId) connect();
else console.log("Aún no estás emparejado: usa «unirse <codigo>».");
rl.prompt();
rl.on("line", (l) => { handle(l).catch((e) => log(`Error: ${e}`)).finally(() => rl.prompt()); });
rl.on("close", () => { closing = true; process.exit(0); });
