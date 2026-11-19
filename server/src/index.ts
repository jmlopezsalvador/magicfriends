import { httpStatus, RE_DEVICE_ID, validateJoinBody, type ErrorCode } from "./protocol.ts";
import { sha256Hex } from "./util.ts";

export { MagoDO } from "./mago.ts";
export { DirectoryDO } from "./directory.ts";

const MAX_JOIN_BODY = 2048;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function error(code: ErrorCode, message: string): Response {
  return json(httpStatus(code), { code, message });
}

async function handleJoin(request: Request, env: Env): Promise<Response> {
  if (request.method !== "POST") return error("bad_message", "Se esperaba POST");
  const text = await request.text();
  if (text.length > MAX_JOIN_BODY) return error("too_large", "Cuerpo demasiado grande");
  let raw: unknown;
  try { raw = JSON.parse(text); } catch { return error("bad_message", "JSON inválido"); }
  const body = validateJoinBody(raw);
  if (!body.ok) return error(body.code, body.message);
  const { code, deviceId, deviceKey, label } = body.value;

  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const resolved = await env.DIRECTORY.get(env.DIRECTORY.idFromName("global")).resolve(code, ip);
  if (!resolved.ok) return error(resolved.code, resolved.code === "rate_limited" ? "Demasiados intentos, espera unos minutos" : "Código no válido o caducado");

  const mago = env.MAGO.get(env.MAGO.idFromName(resolved.magoId));
  const result = await mago.join(resolved.sessionId, deviceId, await sha256Hex(deviceKey), label ?? null);
  if (!result.ok) return error(result.code, result.message);
  return json(200, { magoId: resolved.magoId, requestId: result.requestId, sessionId: resolved.sessionId, sessionName: result.sessionName });
}

function handleWs(request: Request, env: Env): Response | Promise<Response> {
  if (request.headers.get("Upgrade") !== "websocket") return new Response("Se esperaba un WebSocket", { status: 426 });
  const magoId = new URL(request.url).searchParams.get("m");
  if (!magoId || !RE_DEVICE_ID.test(magoId)) return error("bad_message", "Falta 'm' (magoId) o no es válido");
  const stub = env.MAGO.get(env.MAGO.idFromName(magoId));
  const forwarded = new Request(request);
  forwarded.headers.set("X-Mago-Id", magoId);
  return stub.fetch(forwarded);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const { pathname } = new URL(request.url);
    if (pathname === "/v1/join") return handleJoin(request, env);
    if (pathname === "/v1/ws") return handleWs(request, env);
    if (pathname === "/" || pathname === "/health") return json(200, { ok: true, protocol: 1 });
    return error("not_found", "Ruta desconocida");
  },
} satisfies ExportedHandler<Env>;
