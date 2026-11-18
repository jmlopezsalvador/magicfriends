// Tipos y validación del protocolo v1. La fuente de verdad es ../../protocol (schema + fixtures).
// La validación está escrita a mano (Ajv usa `new Function`, que Workers no permite); un test comprueba
// que coincide con el JSON Schema usando los fixtures.

export const PROTOCOL_VERSION = 1;

export type Role = "mago" | "helper";
export type Status = "active" | "paused" | "expired" | "revoked";
export type ErrorCode =
  | "bad_auth" | "forbidden" | "not_found" | "not_active" | "expired" | "too_large"
  | "rate_limited" | "bad_message" | "bad_code" | "unsupported_version" | "limit_reached";

export type JsonObject = { [key: string]: unknown };

export interface SessionInfo {
  id: string;
  name: string;
  status: Status;
  expiresAt: number | null;
  hasController: boolean;
  controllerLabel: string | null;
  rev: number;
  createdAt: number;
  updatedAt: number;
}

export interface PendingRequest {
  requestId: string;
  sessionId: string;
  label: string | null;
  at: number;
}

// ---- Mensajes del cliente ------------------------------------------------------------------

interface Base { v: 1; id?: string }
export type ClientMessage =
  | (Base & { t: "hello"; role: Role; deviceId: string; deviceKey: string; appKey?: string; knownRevs?: Record<string, number> })
  | (Base & { t: "session.create"; name: string; expiresAt?: number | null })
  | (Base & { t: "pairing.code"; sessionId: string })
  | (Base & { t: "pairing.accept"; requestId: string })
  | (Base & { t: "pairing.reject"; requestId: string })
  | (Base & { t: "session.activate"; sessionId: string })
  | (Base & { t: "session.pause"; sessionId: string })
  | (Base & { t: "session.revoke"; sessionId: string })
  | (Base & { t: "session.update"; sessionId: string; name?: string; expiresAt?: number | null })
  | (Base & { t: "session.delete"; sessionId: string })
  | (Base & { t: "patch"; sessionId: string; patch: JsonObject })
  | (Base & { t: "sync"; sessionId: string })
  | (Base & { t: "ping" });

export interface JoinRequestBody {
  code: string;
  deviceId: string;
  deviceKey: string;
  label?: string;
}

// ---- Formatos ------------------------------------------------------------------------------

export const RE_MSG_ID = /^[A-Za-z0-9_.:-]{1,64}$/;
export const RE_DEVICE_ID = /^[A-Za-z0-9_-]{16,64}$/;
export const RE_DEVICE_KEY = /^[A-Za-z0-9_-]{32,128}$/;
export const RE_SESSION_ID = /^[A-Za-z0-9_-]{8,64}$/;
export const RE_REQUEST_ID = RE_SESSION_ID;
export const RE_CODE = /^[2-9A-HJKMNP-Z]{6}$/;
export const CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

export type Validation<T> = { ok: true; value: T } | { ok: false; code: ErrorCode; message: string };

const fail = (message: string, code: ErrorCode = "bad_message"): { ok: false; code: ErrorCode; message: string } =>
  ({ ok: false, code, message });

const isObject = (x: unknown): x is JsonObject => typeof x === "object" && x !== null && !Array.isArray(x);
const isStr = (x: unknown, re: RegExp) => typeof x === "string" && re.test(x);
const isName = (x: unknown) => typeof x === "string" && x.length >= 1 && x.length <= 60;
const isLabel = (x: unknown) => typeof x === "string" && x.length >= 1 && x.length <= 40;
const isEpoch = (x: unknown) => typeof x === "number" && Number.isInteger(x) && x >= 0;
const isEpochOrNull = (x: unknown) => x === null || isEpoch(x);

/** Valida un mensaje recibido del cliente. Ignora los campos desconocidos. */
export function validateClientMessage(raw: unknown): Validation<ClientMessage> {
  if (!isObject(raw)) return fail("El mensaje debe ser un objeto JSON");
  if (raw.v !== PROTOCOL_VERSION) return fail("Versión de protocolo no soportada", "unsupported_version");
  if (typeof raw.t !== "string") return fail("Falta el tipo de mensaje 't'");
  if (raw.id !== undefined && !isStr(raw.id, RE_MSG_ID)) return fail("'id' inválido");
  if (raw.ref !== undefined && !isStr(raw.ref, RE_MSG_ID)) return fail("'ref' inválido");

  const needId = () => (raw.id === undefined ? fail("Falta 'id'") : null);
  const needSession = () => (!isStr(raw.sessionId, RE_SESSION_ID) ? fail("'sessionId' inválido") : null);
  const ok = () => ({ ok: true as const, value: raw as unknown as ClientMessage });

  switch (raw.t) {
    case "hello":
      if (raw.role !== "mago" && raw.role !== "helper") return fail("'role' inválido");
      if (!isStr(raw.deviceId, RE_DEVICE_ID)) return fail("'deviceId' inválido");
      if (!isStr(raw.deviceKey, RE_DEVICE_KEY)) return fail("'deviceKey' inválida");
      if (raw.appKey !== undefined && (typeof raw.appKey !== "string" || raw.appKey.length < 1 || raw.appKey.length > 128)) {
        return fail("'appKey' inválida");
      }
      if (raw.knownRevs !== undefined) {
        if (!isObject(raw.knownRevs)) return fail("'knownRevs' inválido");
        for (const r of Object.values(raw.knownRevs)) {
          if (typeof r !== "number" || !Number.isInteger(r) || r < 0) return fail("'knownRevs' inválido");
        }
      }
      return ok();
    case "session.create":
      return needId() ?? (!isName(raw.name) ? fail("'name' inválido")
        : raw.expiresAt !== undefined && !isEpochOrNull(raw.expiresAt) ? fail("'expiresAt' inválido") : ok());
    case "pairing.accept":
    case "pairing.reject":
      return needId() ?? (!isStr(raw.requestId, RE_REQUEST_ID) ? fail("'requestId' inválido") : ok());
    case "pairing.code":
    case "session.activate":
    case "session.pause":
    case "session.revoke":
    case "session.delete":
    case "sync":
      return needId() ?? needSession() ?? ok();
    case "session.update":
      return needId() ?? needSession()
        ?? (raw.name !== undefined && !isName(raw.name) ? fail("'name' inválido")
          : raw.expiresAt !== undefined && !isEpochOrNull(raw.expiresAt) ? fail("'expiresAt' inválido") : ok());
    case "patch":
      return needId() ?? needSession() ?? (!isObject(raw.patch) ? fail("'patch' debe ser un objeto") : ok());
    case "ping":
      return ok();
    default:
      return fail(`Tipo de mensaje desconocido: ${raw.t}`);
  }
}

export function validateJoinBody(raw: unknown): Validation<JoinRequestBody> {
  if (!isObject(raw)) return fail("El cuerpo debe ser un objeto JSON");
  if (typeof raw.code !== "string") return fail("'code' inválido");
  const code = raw.code.toUpperCase();
  if (!RE_CODE.test(code)) return fail("'code' inválido");
  if (!isStr(raw.deviceId, RE_DEVICE_ID)) return fail("'deviceId' inválido");
  if (!isStr(raw.deviceKey, RE_DEVICE_KEY)) return fail("'deviceKey' inválida");
  if (raw.label !== undefined && !isLabel(raw.label)) return fail("'label' inválido");
  return {
    ok: true,
    value: { code, deviceId: raw.deviceId as string, deviceKey: raw.deviceKey as string, label: raw.label as string | undefined },
  };
}

/** Códigos de error → estado HTTP. */
export function httpStatus(code: ErrorCode): number {
  switch (code) {
    case "bad_message": case "unsupported_version": return 400;
    case "bad_auth": return 401;
    case "forbidden": return 403;
    case "bad_code": case "not_found": return 404;
    case "not_active": case "expired": return 409;
    case "too_large": return 413;
    case "rate_limited": case "limit_reached": return 429;
  }
}
