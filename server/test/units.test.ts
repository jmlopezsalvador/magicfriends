import { exports } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { MAX_FAILED_ATTEMPTS } from "../src/directory.ts";
import { CODE_ALPHABET, RE_CODE } from "../src/protocol.ts";
import { mergePatch, newPairingCode } from "../src/util.ts";

describe("mergePatch (RFC 7396)", () => {
  it("fusiona objetos, quita con null y sustituye arrays", () => {
    const target = { a: 1, b: { c: 2, d: 3 }, e: [1, 2] };
    expect(mergePatch(target, { b: { c: null, x: 9 }, e: [3], f: null })).toEqual({ a: 1, b: { d: 3, x: 9 }, e: [3] });
  });
  it("no contamina el prototipo", () => {
    const out = mergePatch({}, JSON.parse('{"__proto__": {"polluted": true}, "ok": 1}')) as Record<string, unknown>;
    expect(out.ok).toBe(1);
    expect(({} as Record<string, unknown>).polluted).toBeUndefined();
  });
});

describe("Códigos de emparejamiento", () => {
  it("usan solo el alfabeto permitido", () => {
    for (let i = 0; i < 500; i++) {
      const code = newPairingCode();
      expect(code).toMatch(RE_CODE);
      for (const ch of code) expect(CODE_ALPHABET).toContain(ch);
    }
    for (const forbidden of "01OIL") expect(CODE_ALPHABET).not.toContain(forbidden);
  });
});

describe("Límite de intentos de código por IP", () => {
  it("bloquea tras demasiados fallos", async () => {
    const attempt = () => exports.default.fetch("https://magicfriends.test/v1/join", {
      method: "POST",
      headers: { "CF-Connecting-IP": "203.0.113.77" },
      body: JSON.stringify({ code: "ZZZZZZ", deviceId: "dev-limite-0000000001", deviceKey: "k".repeat(40) }),
    });
    for (let i = 0; i < MAX_FAILED_ATTEMPTS; i++) expect((await attempt()).status).toBe(404);
    const blocked = await attempt();
    expect(blocked.status).toBe(429);
    expect(((await blocked.json()) as { code: string }).code).toBe("rate_limited");
  });
});

describe("HTTP", () => {
  it("responde en /health y rechaza rutas desconocidas", async () => {
    expect((await exports.default.fetch("https://magicfriends.test/health")).status).toBe(200);
    expect((await exports.default.fetch("https://magicfriends.test/nada")).status).toBe(404);
  });
  it("exige WebSocket y magoId válido en /v1/ws", async () => {
    expect((await exports.default.fetch("https://magicfriends.test/v1/ws?m=x")).status).toBe(426);
    const res = await exports.default.fetch("https://magicfriends.test/v1/ws?m=x", { headers: { Upgrade: "websocket" } });
    expect(res.status).toBe(400);
  });
});
