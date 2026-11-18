import { DurableObject } from "cloudflare:workers";
import type { ErrorCode } from "./protocol.ts";
import { newPairingCode } from "./util.ts";

export const CODE_TTL_MS = 10 * 60 * 1000;
export const MAX_FAILED_ATTEMPTS = 10;
export const ATTEMPT_WINDOW_MS = 10 * 60 * 1000;

export type ResolveResult =
  | { ok: true; magoId: string; sessionId: string }
  | { ok: false; code: ErrorCode };

/**
 * Durable Object singleton (nombre "global"). Solo traduce «código de emparejamiento → magoId/sessionId»
 * y limita los intentos fallidos por IP. No guarda ajustes ni sesiones.
 */
export class DirectoryDO extends DurableObject {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.storage.sql.exec(`
      CREATE TABLE IF NOT EXISTS codes (
        code TEXT PRIMARY KEY,
        mago_id TEXT NOT NULL,
        session_id TEXT NOT NULL,
        expires_at INTEGER NOT NULL
      );
      CREATE UNIQUE INDEX IF NOT EXISTS codes_session ON codes(session_id);
      CREATE TABLE IF NOT EXISTS attempts (
        ip TEXT PRIMARY KEY,
        window_start INTEGER NOT NULL,
        count INTEGER NOT NULL
      );
    `);
  }

  /** Emite un código nuevo para la sesión e invalida el anterior de esa sesión. */
  issue(magoId: string, sessionId: string): { code: string; expiresAt: number } {
    const sql = this.ctx.storage.sql;
    const now = Date.now();
    sql.exec("DELETE FROM codes WHERE expires_at <= ? OR session_id = ?", now, sessionId);
    sql.exec("DELETE FROM attempts WHERE window_start <= ?", now - ATTEMPT_WINDOW_MS);
    const expiresAt = now + CODE_TTL_MS;
    for (let i = 0; i < 20; i++) {
      const code = newPairingCode();
      const taken = sql.exec("SELECT 1 FROM codes WHERE code = ?", code).toArray().length > 0;
      if (taken) continue;
      sql.exec("INSERT INTO codes(code, mago_id, session_id, expires_at) VALUES (?, ?, ?, ?)", code, magoId, sessionId, expiresAt);
      return { code, expiresAt };
    }
    throw new Error("No se pudo generar un código único");
  }

  /** Canjea (y consume) un código. Cuenta los fallos por IP. */
  resolve(code: string, ip: string): ResolveResult {
    const sql = this.ctx.storage.sql;
    const now = Date.now();

    const att = sql.exec<{ window_start: number; count: number }>("SELECT window_start, count FROM attempts WHERE ip = ?", ip).toArray()[0];
    const inWindow = att !== undefined && att.window_start > now - ATTEMPT_WINDOW_MS;
    if (inWindow && att.count >= MAX_FAILED_ATTEMPTS) return { ok: false, code: "rate_limited" };

    const row = sql.exec<{ mago_id: string; session_id: string; expires_at: number }>(
      "SELECT mago_id, session_id, expires_at FROM codes WHERE code = ?", code).toArray()[0];
    if (row === undefined || row.expires_at <= now) {
      if (row !== undefined) sql.exec("DELETE FROM codes WHERE code = ?", code);
      if (inWindow) sql.exec("UPDATE attempts SET count = count + 1 WHERE ip = ?", ip);
      else sql.exec("INSERT OR REPLACE INTO attempts(ip, window_start, count) VALUES (?, ?, 1)", ip, now);
      return { ok: false, code: "bad_code" };
    }
    sql.exec("DELETE FROM codes WHERE code = ?", code);
    return { ok: true, magoId: row.mago_id, sessionId: row.session_id };
  }

  /** Invalida los códigos pendientes de una sesión (revocada, borrada…). */
  revokeSession(sessionId: string): void {
    this.ctx.storage.sql.exec("DELETE FROM codes WHERE session_id = ?", sessionId);
  }
}
