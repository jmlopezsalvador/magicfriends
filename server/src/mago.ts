import { DurableObject } from "cloudflare:workers";
import {
  PROTOCOL_VERSION, validateClientMessage,
  type ClientMessage, type ErrorCode, type JsonObject, type PendingRequest, type Role, type SessionInfo, type Status,
} from "./protocol.ts";
import { mergePatch, newId, safeEqual, sha256Hex } from "./util.ts";

export const MAX_FRAME_BYTES = 32 * 1024;
export const MAX_PATCH_BYTES = 4 * 1024;
export const MAX_SETTINGS_BYTES = 16 * 1024;
export const MAX_SESSIONS = 20;
export const MAX_PENDING_PER_SESSION = 5;
export const MAX_HELPER_SOCKETS = 3;
export const PATCHES_PER_SECOND = 10;

interface Attachment {
  magoId: string;
  role?: Role;
  deviceId?: string;
  keyHash?: string;
}

interface SessionRow extends Record<string, SqlStorageValue> {
  id: string;
  name: string;
  status: Status;
  expires_at: number | null;
  controller_id: string | null;
  controller_key_hash: string | null;
  controller_label: string | null;
  rev: number;
  settings: string;
  created_at: number;
  updated_at: number;
}

interface RequestRow extends Record<string, SqlStorageValue> {
  id: string;
  session_id: string;
  helper_id: string;
  helper_key_hash: string;
  label: string | null;
  created_at: number;
}

export type JoinResult = { ok: true; requestId: string } | { ok: false; code: ErrorCode; message: string };

type Out = Record<string, unknown> & { t: string };

/**
 * Un Durable Object por mago. Guarda sus sesiones y el último ajuste de cada una (SQLite), y reenvía los parches
 * del ayudante al mago por WebSocket (con Hibernation). Agnóstico: los ajustes son JSON opaco.
 */
export class MagoDO extends DurableObject<Env> {
  private patchTimes = new WeakMap<WebSocket, number[]>();

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.storage.sql.exec(`
      CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS sessions (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        status TEXT NOT NULL CHECK (status IN ('active','paused','expired','revoked')),
        expires_at INTEGER,
        controller_id TEXT,
        controller_key_hash TEXT,
        controller_label TEXT,
        rev INTEGER NOT NULL DEFAULT 0,
        settings TEXT NOT NULL DEFAULT '{}',
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
      );
      CREATE UNIQUE INDEX IF NOT EXISTS one_active ON sessions(status) WHERE status = 'active';
      CREATE TABLE IF NOT EXISTS join_requests (
        id TEXT PRIMARY KEY,
        session_id TEXT NOT NULL,
        helper_id TEXT NOT NULL,
        helper_key_hash TEXT NOT NULL,
        label TEXT,
        created_at INTEGER NOT NULL
      );
    `);
  }

  // ---- Entrada --------------------------------------------------------------------------------

  async fetch(request: Request): Promise<Response> {
    if (request.headers.get("Upgrade") !== "websocket") return new Response("Se esperaba un WebSocket", { status: 426 });
    const magoId = request.headers.get("X-Mago-Id");
    if (!magoId) return new Response("Falta el magoId", { status: 400 });
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1]);
    pair[1].serializeAttachment({ magoId } satisfies Attachment);
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  /** Llamado por el Worker tras canjear un código en el Directory. */
  async join(sessionId: string, deviceId: string, keyHash: string, label: string | null): Promise<JoinResult> {
    await this.sweep();
    const s = this.getSession(sessionId);
    if (!s) return { ok: false, code: "not_found", message: "La sesión ya no existe" };
    if (s.status === "revoked") return { ok: false, code: "not_active", message: "La sesión está revocada" };
    const sql = this.ctx.storage.sql;
    sql.exec("DELETE FROM join_requests WHERE session_id = ? AND helper_id = ?", sessionId, deviceId);
    const pending = sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM join_requests WHERE session_id = ?", sessionId).one().n;
    if (pending >= MAX_PENDING_PER_SESSION) return { ok: false, code: "limit_reached", message: "Demasiadas solicitudes pendientes" };
    const requestId = newId();
    const at = Date.now();
    sql.exec("INSERT INTO join_requests(id, session_id, helper_id, helper_key_hash, label, created_at) VALUES (?, ?, ?, ?, ?, ?)",
      requestId, sessionId, deviceId, keyHash, label, at);
    this.sendToMago({ t: "pairing.request", request: { requestId, sessionId, label, at } satisfies PendingRequest });
    return { ok: true, requestId };
  }

  async alarm(): Promise<void> {
    await this.sweep();
  }

  // ---- WebSocket ------------------------------------------------------------------------------

  async webSocketMessage(ws: WebSocket, data: string | ArrayBuffer): Promise<void> {
    if (typeof data !== "string") return this.sendError(ws, undefined, "bad_message", "Solo se admiten frames de texto");
    if (data.length > MAX_FRAME_BYTES) return this.sendError(ws, undefined, "too_large", "Mensaje demasiado grande");
    let raw: unknown;
    try { raw = JSON.parse(data); } catch { return this.sendError(ws, undefined, "bad_message", "JSON inválido"); }
    const parsed = validateClientMessage(raw);
    if (!parsed.ok) {
      const id = typeof (raw as { id?: unknown })?.id === "string" ? (raw as { id: string }).id : undefined;
      return this.sendError(ws, id, parsed.code, parsed.message);
    }
    const msg = parsed.value;
    const att = ws.deserializeAttachment() as Attachment;

    if (msg.t === "hello") return this.handleHello(ws, att, msg);
    if (!att.role) {
      this.sendError(ws, msg.id, "bad_auth", "Hay que enviar 'hello' primero");
      ws.close(4001, "bad_auth");
      return;
    }
    await this.sweep();
    try {
      await this.dispatch(ws, att, msg);
    } finally {
      await this.scheduleAlarm();
    }
  }

  webSocketClose(ws: WebSocket, code: number): void {
    try { ws.close(code === 1005 || code === 1006 ? 1000 : code, "bye"); } catch { /* ya cerrado */ }
  }

  webSocketError(ws: WebSocket): void {
    try { ws.close(1011, "error"); } catch { /* ya cerrado */ }
  }

  // ---- hello ----------------------------------------------------------------------------------

  private async handleHello(ws: WebSocket, att: Attachment, msg: Extract<ClientMessage, { t: "hello" }>): Promise<void> {
    const badAuth = () => {
      this.sendError(ws, msg.id, "bad_auth", "Credenciales no válidas");
      ws.close(4001, "bad_auth");
    };
    const keyHash = await sha256Hex(msg.deviceKey);
    const sql = this.ctx.storage.sql;

    if (msg.role === "mago") {
      if (msg.deviceId !== att.magoId) return badAuth();
      const stored = this.getMeta("mago_key_hash");
      if (stored === null) this.setMeta("mago_key_hash", keyHash); // primer uso: se registra la clave
      else if (!safeEqual(stored, keyHash)) return badAuth();
    } else {
      const controls = sql.exec<{ n: number }>(
        "SELECT COUNT(*) AS n FROM sessions WHERE controller_id = ? AND controller_key_hash = ?", msg.deviceId, keyHash).one().n;
      const requests = sql.exec<{ n: number }>(
        "SELECT COUNT(*) AS n FROM join_requests WHERE helper_id = ? AND helper_key_hash = ?", msg.deviceId, keyHash).one().n;
      if (controls + requests === 0) return badAuth();
    }

    // Una conexión nueva del mismo rol/dispositivo sustituye a la anterior.
    let otherHelpers = 0;
    for (const other of this.ctx.getWebSockets()) {
      if (other === ws) continue;
      const o = other.deserializeAttachment() as Attachment;
      if (o.role !== msg.role) continue;
      if (msg.role === "mago" || o.deviceId === msg.deviceId) {
        other.serializeAttachment({ magoId: o.magoId } satisfies Attachment);
        try { other.close(4000, "replaced"); } catch { /* ya cerrado */ }
      } else otherHelpers++;
    }
    if (msg.role === "helper" && otherHelpers >= MAX_HELPER_SOCKETS) {
      this.sendError(ws, msg.id, "rate_limited", "Demasiadas conexiones de ayudantes");
      ws.close(4002, "too_many");
      return;
    }

    ws.serializeAttachment({ magoId: att.magoId, role: msg.role, deviceId: msg.deviceId, keyHash } satisfies Attachment);
    await this.sweep();

    const sessions = this.sessionsFor(msg.role, msg.deviceId);
    const pending = msg.role === "mago"
      ? sql.exec<RequestRow>("SELECT * FROM join_requests ORDER BY created_at").toArray()
      : sql.exec<RequestRow>("SELECT * FROM join_requests WHERE helper_id = ? ORDER BY created_at", msg.deviceId).toArray();
    this.send(ws, {
      t: "welcome", ref: msg.id, role: msg.role, serverTime: Date.now(),
      sessions: sessions.map(toInfo),
      pending: pending.map(toPending),
    });
    for (const s of sessions) {
      if (s.status === "active" && msg.knownRevs?.[s.id] !== s.rev) this.send(ws, stateMsg(s));
    }
    await this.scheduleAlarm();
  }

  // ---- Comandos -------------------------------------------------------------------------------

  private async dispatch(ws: WebSocket, att: Attachment, msg: ClientMessage): Promise<void> {
    const role = att.role!;
    const mago = role === "mago";
    const forbid = (what: string) => this.sendError(ws, msg.id, "forbidden", what);
    const sql = this.ctx.storage.sql;
    const now = Date.now();

    switch (msg.t) {
      case "ping":
        return this.send(ws, { t: "pong", ref: msg.id });

      case "session.create": {
        if (!mago) return forbid("Solo el mago crea sesiones");
        const expiresAt = msg.expiresAt ?? null;
        if (expiresAt !== null && expiresAt <= now) return this.sendError(ws, msg.id, "bad_message", "La caducidad ya ha pasado");
        const count = sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM sessions").one().n;
        if (count >= MAX_SESSIONS) return this.sendError(ws, msg.id, "limit_reached", "Demasiadas sesiones");
        const id = newId();
        sql.exec("INSERT INTO sessions(id, name, status, expires_at, created_at, updated_at) VALUES (?, ?, 'paused', ?, ?, ?)",
          id, msg.name, expiresAt, now, now);
        const code = await this.issueCode(att.magoId, id);
        this.send(ws, { t: "session", ref: msg.id, session: toInfo(this.getSession(id)!) });
        this.send(ws, { t: "pairing", ref: msg.id, sessionId: id, ...code });
        return;
      }

      case "pairing.code": {
        if (!mago) return forbid("Solo el mago genera códigos");
        const s = this.getSession(msg.sessionId);
        if (!s) return this.sendError(ws, msg.id, "not_found", "Sesión inexistente");
        if (s.status === "revoked") return forbid("La sesión está revocada");
        if (s.status === "expired") return this.sendError(ws, msg.id, "expired", "La sesión ha caducado");
        const code = await this.issueCode(att.magoId, s.id);
        this.send(ws, { t: "pairing", ref: msg.id, sessionId: s.id, ...code });
        return;
      }

      case "pairing.accept": {
        if (!mago) return forbid("Solo el mago acepta solicitudes");
        const req = this.getRequest(msg.requestId);
        if (!req) return this.sendError(ws, msg.id, "not_found", "Solicitud inexistente");
        const s = this.getSession(req.session_id);
        if (!s || s.status === "revoked") return this.sendError(ws, msg.id, "not_found", "Sesión inexistente");
        const previous = s.controller_id;
        sql.exec(`UPDATE sessions SET controller_id = ?, controller_key_hash = ?, controller_label = ?, updated_at = ? WHERE id = ?`,
          req.helper_id, req.helper_key_hash, req.label, now, s.id);
        sql.exec("DELETE FROM join_requests WHERE session_id = ?", s.id);
        const updated = this.getSession(s.id)!;
        this.send(ws, { t: "session", ref: msg.id, session: toInfo(updated) });
        if (previous && previous !== req.helper_id) this.sendToHelper(previous, { t: "session.removed", sessionId: s.id, reason: "replaced" });
        this.sendToHelper(req.helper_id, { t: "pairing.result", requestId: req.id, accepted: true, sessionId: s.id });
        this.sendToHelper(req.helper_id, { t: "session", session: toInfo(updated) });
        if (updated.status === "active") this.sendToHelper(req.helper_id, stateMsg(updated));
        return;
      }

      case "pairing.reject": {
        if (!mago) return forbid("Solo el mago rechaza solicitudes");
        const req = this.getRequest(msg.requestId);
        if (!req) return this.sendError(ws, msg.id, "not_found", "Solicitud inexistente");
        sql.exec("DELETE FROM join_requests WHERE id = ?", req.id);
        this.send(ws, { t: "ack", ref: msg.id });
        this.sendToHelper(req.helper_id, { t: "pairing.result", requestId: req.id, accepted: false, sessionId: req.session_id });
        return;
      }

      case "session.activate": {
        if (!mago) return forbid("Solo el mago activa sesiones");
        const s = this.getSession(msg.sessionId);
        if (!s) return this.sendError(ws, msg.id, "not_found", "Sesión inexistente");
        if (s.status === "revoked") return forbid("La sesión está revocada");
        if (s.status === "expired") return this.sendError(ws, msg.id, "expired", "La sesión ha caducado");
        if (s.status === "active") return this.send(ws, { t: "session", ref: msg.id, session: toInfo(s) });
        let prev: SessionRow | undefined;
        this.ctx.storage.transactionSync(() => {
          prev = sql.exec<SessionRow>("SELECT * FROM sessions WHERE status = 'active'").toArray()[0];
          if (prev) sql.exec("UPDATE sessions SET status = 'paused', updated_at = ? WHERE id = ?", now, prev.id);
          sql.exec("UPDATE sessions SET status = 'active', updated_at = ? WHERE id = ?", now, s.id);
        });
        const active = this.getSession(s.id)!;
        if (prev) {
          const paused = this.getSession(prev.id)!;
          this.send(ws, { t: "session", session: toInfo(paused) });
          this.sendToController(paused, { t: "session", session: toInfo(paused) });
        }
        this.send(ws, { t: "session", ref: msg.id, session: toInfo(active) });
        this.send(ws, stateMsg(active));
        this.sendToController(active, { t: "session", session: toInfo(active) });
        this.sendToController(active, stateMsg(active));
        return;
      }

      case "session.pause": {
        if (!mago) return forbid("Solo el mago pausa sesiones");
        const s = this.getSession(msg.sessionId);
        if (!s) return this.sendError(ws, msg.id, "not_found", "Sesión inexistente");
        if (s.status === "revoked") return forbid("La sesión está revocada");
        if (s.status === "expired") return this.sendError(ws, msg.id, "expired", "La sesión ha caducado");
        if (s.status === "active") {
          sql.exec("UPDATE sessions SET status = 'paused', updated_at = ? WHERE id = ?", now, s.id);
        }
        const updated = this.getSession(s.id)!;
        this.send(ws, { t: "session", ref: msg.id, session: toInfo(updated) });
        if (s.status === "active") this.sendToController(updated, { t: "session", session: toInfo(updated) });
        return;
      }

      case "session.revoke": {
        if (!mago) return forbid("Solo el mago revoca sesiones");
        const s = this.getSession(msg.sessionId);
        if (!s) return this.sendError(ws, msg.id, "not_found", "Sesión inexistente");
        if (s.status !== "revoked") {
          sql.exec(`UPDATE sessions SET status = 'revoked', controller_id = NULL, controller_key_hash = NULL,
                    controller_label = NULL, updated_at = ? WHERE id = ?`, now, s.id);
          sql.exec("DELETE FROM join_requests WHERE session_id = ?", s.id);
          await this.env.DIRECTORY.get(this.env.DIRECTORY.idFromName("global")).revokeSession(s.id);
          this.sendToController(s, { t: "session.removed", sessionId: s.id, reason: "revoked" });
        }
        this.send(ws, { t: "session", ref: msg.id, session: toInfo(this.getSession(s.id)!) });
        return;
      }

      case "session.update": {
        if (!mago) return forbid("Solo el mago edita sesiones");
        const s = this.getSession(msg.sessionId);
        if (!s) return this.sendError(ws, msg.id, "not_found", "Sesión inexistente");
        if (s.status === "revoked") return forbid("La sesión está revocada");
        const name = msg.name ?? s.name;
        const expiresAt = msg.expiresAt === undefined ? s.expires_at : msg.expiresAt;
        if (msg.expiresAt !== undefined && expiresAt !== null && expiresAt <= now) {
          return this.sendError(ws, msg.id, "bad_message", "La caducidad ya ha pasado");
        }
        const status: Status = s.status === "expired" && (expiresAt === null || expiresAt > now) ? "paused" : s.status;
        sql.exec("UPDATE sessions SET name = ?, expires_at = ?, status = ?, updated_at = ? WHERE id = ?", name, expiresAt, status, now, s.id);
        const updated = this.getSession(s.id)!;
        this.send(ws, { t: "session", ref: msg.id, session: toInfo(updated) });
        this.sendToController(updated, { t: "session", session: toInfo(updated) });
        return;
      }

      case "session.delete": {
        if (!mago) return forbid("Solo el mago borra sesiones");
        const s = this.getSession(msg.sessionId);
        if (!s) return this.sendError(ws, msg.id, "not_found", "Sesión inexistente");
        sql.exec("DELETE FROM sessions WHERE id = ?", s.id);
        sql.exec("DELETE FROM join_requests WHERE session_id = ?", s.id);
        await this.env.DIRECTORY.get(this.env.DIRECTORY.idFromName("global")).revokeSession(s.id);
        this.send(ws, { t: "ack", ref: msg.id });
        this.sendToController(s, { t: "session.removed", sessionId: s.id, reason: "deleted" });
        return;
      }

      case "sync": {
        const s = this.getSession(msg.sessionId);
        if (!s) return this.sendError(ws, msg.id, "not_found", "Sesión inexistente");
        if (!mago && !this.isController(s, att)) return forbid("No eres el controlador de esta sesión");
        this.send(ws, { ...stateMsg(s), ref: msg.id });
        return;
      }

      case "patch": {
        if (mago) return forbid("Solo el ayudante envía parches");
        const s = this.getSession(msg.sessionId);
        if (!s) return this.sendError(ws, msg.id, "not_found", "Sesión inexistente");
        if (!this.isController(s, att)) return forbid("No eres el controlador de esta sesión");
        if (s.status === "expired") return this.sendError(ws, msg.id, "expired", "La sesión ha caducado");
        if (s.status !== "active") return this.sendError(ws, msg.id, "not_active", "La sesión no está activa");
        if (!this.allowPatch(ws, now)) return this.sendError(ws, msg.id, "rate_limited", "Demasiados parches por segundo");
        if (JSON.stringify(msg.patch).length > MAX_PATCH_BYTES) return this.sendError(ws, msg.id, "too_large", "Parche demasiado grande");
        const merged = JSON.stringify(mergePatch(JSON.parse(s.settings), msg.patch));
        if (merged.length > MAX_SETTINGS_BYTES) return this.sendError(ws, msg.id, "too_large", "Ajustes demasiado grandes");
        const rev = s.rev + 1;
        sql.exec("UPDATE sessions SET settings = ?, rev = ?, updated_at = ? WHERE id = ?", merged, rev, now, s.id);
        this.send(ws, { t: "ack", ref: msg.id, rev });
        this.sendToMago({ t: "patch", sessionId: s.id, rev, prevRev: s.rev, patch: msg.patch });
        return;
      }
    }
  }

  // ---- Caducidad ------------------------------------------------------------------------------

  /** Marca como caducadas las sesiones cuyo expiresAt ha pasado y avisa a mago y controlador. */
  private async sweep(): Promise<void> {
    const now = Date.now();
    const sql = this.ctx.storage.sql;
    const due = sql.exec<SessionRow>(
      "SELECT * FROM sessions WHERE status IN ('active','paused') AND expires_at IS NOT NULL AND expires_at <= ?", now).toArray();
    for (const s of due) {
      sql.exec("UPDATE sessions SET status = 'expired', updated_at = ? WHERE id = ?", now, s.id);
      const updated = this.getSession(s.id)!;
      this.sendToMago({ t: "session", session: toInfo(updated) });
      this.sendToController(updated, { t: "session", session: toInfo(updated) });
    }
  }

  private async scheduleAlarm(): Promise<void> {
    const row = this.ctx.storage.sql.exec<{ next: number | null }>(
      "SELECT MIN(expires_at) AS next FROM sessions WHERE status IN ('active','paused') AND expires_at IS NOT NULL").one();
    if (row.next === null) await this.ctx.storage.deleteAlarm();
    else await this.ctx.storage.setAlarm(Math.max(row.next, Date.now() + 1));
  }

  // ---- Utilidades -----------------------------------------------------------------------------

  private getMeta(key: string): string | null {
    return this.ctx.storage.sql.exec<{ value: string }>("SELECT value FROM meta WHERE key = ?", key).toArray()[0]?.value ?? null;
  }

  private setMeta(key: string, value: string): void {
    this.ctx.storage.sql.exec("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)", key, value);
  }

  private getSession(id: string): SessionRow | undefined {
    return this.ctx.storage.sql.exec<SessionRow>("SELECT * FROM sessions WHERE id = ?", id).toArray()[0];
  }

  private getRequest(id: string): RequestRow | undefined {
    return this.ctx.storage.sql.exec<RequestRow>("SELECT * FROM join_requests WHERE id = ?", id).toArray()[0];
  }

  private sessionsFor(role: Role, deviceId: string): SessionRow[] {
    const sql = this.ctx.storage.sql;
    return role === "mago"
      ? sql.exec<SessionRow>("SELECT * FROM sessions ORDER BY created_at, id").toArray()
      : sql.exec<SessionRow>("SELECT * FROM sessions WHERE controller_id = ? ORDER BY created_at, id", deviceId).toArray();
  }

  private isController(s: SessionRow, att: Attachment): boolean {
    return s.controller_id !== null && s.controller_id === att.deviceId
      && s.controller_key_hash !== null && att.keyHash !== undefined && safeEqual(s.controller_key_hash, att.keyHash);
  }

  private async issueCode(magoId: string, sessionId: string): Promise<{ code: string; expiresAt: number }> {
    return this.env.DIRECTORY.get(this.env.DIRECTORY.idFromName("global")).issue(magoId, sessionId);
  }

  private allowPatch(ws: WebSocket, now: number): boolean {
    const times = (this.patchTimes.get(ws) ?? []).filter((t) => now - t < 1000);
    if (times.length >= PATCHES_PER_SECOND) { this.patchTimes.set(ws, times); return false; }
    times.push(now);
    this.patchTimes.set(ws, times);
    return true;
  }

  private send(ws: WebSocket, msg: Out): void {
    try { ws.send(JSON.stringify({ v: PROTOCOL_VERSION, ...stripUndefined(msg) })); } catch { /* conexión cerrada */ }
  }

  private sendError(ws: WebSocket, ref: string | undefined, code: ErrorCode, message: string): void {
    this.send(ws, { t: "error", ref, code, message });
  }

  private socketsOf(role: Role, deviceId?: string): WebSocket[] {
    return this.ctx.getWebSockets().filter((w) => {
      const a = w.deserializeAttachment() as Attachment | null;
      return a?.role === role && (deviceId === undefined || a.deviceId === deviceId);
    });
  }

  private sendToMago(msg: Out): void {
    for (const w of this.socketsOf("mago")) this.send(w, msg);
  }

  private sendToHelper(deviceId: string, msg: Out): void {
    for (const w of this.socketsOf("helper", deviceId)) this.send(w, msg);
  }

  private sendToController(s: SessionRow, msg: Out): void {
    if (s.controller_id) this.sendToHelper(s.controller_id, msg);
  }
}

function stripUndefined(o: Out): Out {
  return Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined)) as Out;
}

function toInfo(s: SessionRow): SessionInfo {
  return {
    id: s.id, name: s.name, status: s.status, expiresAt: s.expires_at,
    hasController: s.controller_id !== null, controllerLabel: s.controller_label,
    rev: s.rev, createdAt: s.created_at, updatedAt: s.updated_at,
  };
}

function toPending(r: RequestRow): PendingRequest {
  return { requestId: r.id, sessionId: r.session_id, label: r.label, at: r.created_at };
}

function stateMsg(s: SessionRow): Out {
  return { t: "state", sessionId: s.id, rev: s.rev, settings: JSON.parse(s.settings) as JsonObject };
}
