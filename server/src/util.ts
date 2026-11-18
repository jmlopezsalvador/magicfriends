import { CODE_ALPHABET } from "./protocol.ts";

const enc = new TextEncoder();

/** sha256 en hexadecimal. El servidor solo guarda el hash de las claves de dispositivo. */
export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", enc.encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Comparación en tiempo constante de dos cadenas de igual longitud (hashes hex). */
export function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/**
 * Comprueba el secreto compartido de las apps. Si el servidor no tiene APP_SECRET (desarrollo local), no se exige.
 * Se compara el sha256 de ambos para no filtrar la longitud ni comparar en tiempo variable.
 */
export async function appKeyAllowed(secret: string | undefined, provided: string | undefined): Promise<boolean> {
  if (!secret) return true;
  if (!provided) return false;
  return safeEqual(await sha256Hex(secret), await sha256Hex(provided));
}

export function newId(): string {
  return crypto.randomUUID();
}

/** Código de emparejamiento de 6 caracteres sin sesgo (muestreo con rechazo). */
export function newPairingCode(): string {
  const n = CODE_ALPHABET.length;
  const limit = 256 - (256 % n);
  let out = "";
  while (out.length < 6) {
    const bytes = crypto.getRandomValues(new Uint8Array(12));
    for (const b of bytes) {
      if (b < limit && out.length < 6) out += CODE_ALPHABET[b % n];
    }
  }
  return out;
}

/** JSON Merge Patch (RFC 7396): null quita la clave, objetos se fusionan, el resto se sustituye. */
export function mergePatch(target: unknown, patch: unknown): unknown {
  if (typeof patch !== "object" || patch === null || Array.isArray(patch)) return patch;
  const result: Record<string, unknown> =
    typeof target === "object" && target !== null && !Array.isArray(target) ? { ...(target as Record<string, unknown>) } : {};
  for (const [key, value] of Object.entries(patch as Record<string, unknown>)) {
    if (key === "__proto__") continue;
    if (value === null) delete result[key];
    else result[key] = mergePatch(result[key], value);
  }
  return result;
}
