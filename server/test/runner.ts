// Ejecutor de escenarios de protocol/fixtures/scenarios contra el Worker real (Durable Objects incluidos).
// El formato de los escenarios está descrito en protocol/PROTOCOLO.md.
import { exports } from "cloudflare:workers";

type Json = any; // eslint-disable-line @typescript-eslint/no-explicit-any

interface ActorDef { role: "mago" | "helper"; as?: string; key?: string; mago?: string }
export interface Scenario { name: string; actors: Record<string, ActorDef>; steps: Json[] }

interface Actor {
  name: string;
  def: ActorDef;
  deviceId: string;
  key: string;
  magoActor: string;
  ws?: WebSocket;
  queue: Json[];
  closed: boolean;
  waiter?: () => void;
}

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));
const BASE = "https://magicfriends.test";

export async function runScenario(scenario: Scenario): Promise<void> {
  const runId = Math.random().toString(36).slice(2, 8);
  const captures: Record<string, unknown> = {};
  const actors: Record<string, Actor> = {};

  const pad = (s: string, n: number) => s.padEnd(n, "x");
  for (const [name, def] of Object.entries(scenario.actors)) {
    const identity = def.as ?? name;
    actors[name] = {
      name, def, queue: [], closed: false,
      deviceId: pad(`dev-${identity}-${runId}-`, 20),
      key: def.key ?? pad(`key-${identity}-${runId}-`, 40),
      magoActor: def.mago ?? Object.entries(scenario.actors).find(([, d]) => d.role === "mago" && !d.as)?.[0] ?? name,
    };
  }
  const magoDeviceId = (a: Actor) => actors[a.magoActor].deviceId;

  const subst = (v: Json): Json => {
    if (typeof v === "string") {
      if (v.startsWith("$now+")) return Date.now() + Number(v.slice(5));
      if (v.startsWith("$") && !v.startsWith("$>") && v !== "$any" && v !== "$absent") {
        const name = v.slice(1);
        if (!(name in captures)) throw new Error(`Captura desconocida: ${v}`);
        return captures[name];
      }
      return v;
    }
    if (Array.isArray(v)) return v.map(subst);
    if (v && typeof v === "object") {
      return Object.fromEntries(Object.entries(v).map(([k, x]) => [k.startsWith("$") && k.slice(1) in captures ? String(captures[k.slice(1)]) : k, subst(x)]));
    }
    return v;
  };

  const match = (exp: Json, act: Json, path: string): string | null => {
    if (exp === "$any") return act === undefined ? `${path}: falta` : null;
    if (typeof exp === "string" && exp.startsWith("$>")) {
      if (act === undefined) return `${path}: falta (captura ${exp})`;
      captures[exp.slice(2)] = act;
      return null;
    }
    if (typeof exp === "string" && exp.startsWith("$") && exp !== "$absent") exp = subst(exp);
    if (Array.isArray(exp)) {
      if (!Array.isArray(act) || act.length !== exp.length) return `${path}: se esperaba un array de ${exp.length}, llegó ${JSON.stringify(act)}`;
      for (let i = 0; i < exp.length; i++) { const e = match(exp[i], act[i], `${path}[${i}]`); if (e) return e; }
      return null;
    }
    if (exp && typeof exp === "object") {
      if (!act || typeof act !== "object" || Array.isArray(act)) return `${path}: se esperaba un objeto, llegó ${JSON.stringify(act)}`;
      for (const [k, x] of Object.entries(exp)) {
        const key = k.startsWith("$") && k.slice(1) in captures ? String(captures[k.slice(1)]) : k;
        if (x === "$absent") { if (key in act) return `${path}.${key}: debía estar ausente`; continue; }
        const e = match(x, act[key], `${path}.${key}`);
        if (e) return e;
      }
      return null;
    }
    return exp === act ? null : `${path}: se esperaba ${JSON.stringify(exp)}, llegó ${JSON.stringify(act)}`;
  };

  const connect = async (a: Actor) => {
    const res = await exports.default.fetch(`${BASE}/v1/ws?m=${magoDeviceId(a)}`, { headers: { Upgrade: "websocket" } });
    const ws = res.webSocket;
    if (!ws) throw new Error(`${a.name}: no se pudo abrir el WebSocket (${res.status})`);
    ws.accept();
    a.ws = ws; a.queue = []; a.closed = false;
    ws.addEventListener("message", (e: MessageEvent) => { a.queue.push(JSON.parse(String(e.data))); a.waiter?.(); });
    ws.addEventListener("close", () => { a.closed = true; a.waiter?.(); });
  };

  const next = async (a: Actor, what: string): Promise<Json> => {
    const deadline = Date.now() + 4000;
    while (a.queue.length === 0) {
      if (Date.now() > deadline) throw new Error(`${what}: ${a.name} no recibió nada a tiempo`);
      await Promise.race([new Promise<void>((r) => { a.waiter = r; }), sleep(100)]);
    }
    return a.queue.shift();
  };

  let n = 0;
  for (const step of scenario.steps) {
    n++;
    const where = `[${scenario.name}] paso ${n}${step.comment ? ` (${step.comment})` : ""}`;
    if (step.sleep) { await sleep(step.sleep); continue; }
    const a = actors[step.actor];
    if (!a) throw new Error(`${where}: actor desconocido ${step.actor}`);

    if (step.hello) {
      await connect(a);
      a.ws!.send(JSON.stringify({ v: 1, t: "hello", role: a.def.role, deviceId: a.deviceId, deviceKey: a.key, ...subst(step.hello) }));
    } else if (step.send) {
      a.ws!.send(JSON.stringify({ v: 1, ...subst(step.send) }));
    } else if (step.expect) {
      const msg = await next(a, where);
      const err = match(step.expect, msg, "$");
      if (err) throw new Error(`${where}\n  ${err}\n  mensaje recibido por ${a.name}: ${JSON.stringify(msg)}`);
      if (msg.v !== 1) throw new Error(`${where}: falta v:1 en ${JSON.stringify(msg)}`);
    } else if (step.join) {
      const body = { deviceId: a.deviceId, deviceKey: a.key, ...subst(step.join) };
      const res = await exports.default.fetch(`${BASE}/v1/join`, { method: "POST", body: JSON.stringify(body), headers: { "CF-Connecting-IP": `10.0.0.${runId.length}` } });
      const resBody = await res.json();
      if (step.expectHttp) {
        if (step.expectHttp.status !== undefined && res.status !== step.expectHttp.status) {
          throw new Error(`${where}: HTTP ${res.status} (se esperaba ${step.expectHttp.status}): ${JSON.stringify(resBody)}`);
        }
        const err = match(step.expectHttp.body, resBody, "$");
        if (err) throw new Error(`${where}\n  ${err}\n  cuerpo: ${JSON.stringify(resBody)}`);
      }
    } else if (step.close) {
      a.ws!.close(); a.ws = undefined; a.queue = [];
    } else if (step.expectClose) {
      const deadline = Date.now() + 3000;
      while (!a.closed) {
        if (Date.now() > deadline) throw new Error(`${where}: ${a.name} debía cerrarse`);
        await Promise.race([new Promise<void>((r) => { a.waiter = r; }), sleep(100)]);
      }
    } else if (step.expectNothing) {
      await sleep(150);
      if (a.queue.length) throw new Error(`${where}: ${a.name} recibió algo inesperado: ${JSON.stringify(a.queue)}`);
    } else {
      throw new Error(`${where}: paso no reconocido ${JSON.stringify(step)}`);
    }
  }

  // Ningún actor debe tener mensajes sin consumir.
  await sleep(200);
  for (const a of Object.values(actors)) {
    if (a.queue.length) throw new Error(`[${scenario.name}] ${a.name} tiene mensajes sin consumir: ${JSON.stringify(a.queue)}`);
    try { a.ws?.close(); } catch { /* ya cerrado */ }
  }
}
