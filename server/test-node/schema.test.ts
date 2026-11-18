import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import Ajv2020 from "ajv/dist/2020.js";
import { validateClientMessage, validateJoinBody } from "../src/protocol.ts";

const read = (rel: string) => JSON.parse(readFileSync(new URL(rel, import.meta.url), "utf8"));
const schema = read("../../protocol/schema/messages.schema.json");
const fixtures = read("../../protocol/fixtures/messages.json") as {
  cases: { name: string; def: string; valid: boolean; message: unknown }[];
};

const ajv = new Ajv2020({ strict: false });
ajv.addSchema(schema);
const validatorFor = (def: string) => ajv.compile({ $ref: `${schema.$id}#/$defs/${def}` });

describe("JSON Schema vs fixtures", () => {
  for (const c of fixtures.cases) {
    it(`${c.def}: ${c.name}`, () => {
      const validate = validatorFor(c.def);
      expect(validate(c.message), JSON.stringify(validate.errors)).toBe(c.valid);
    });
  }
});

describe("Validador manual del servidor coincide con el schema", () => {
  for (const c of fixtures.cases.filter((x) => x.def === "ClientMessage" || x.def === "JoinRequestBody")) {
    it(`${c.def}: ${c.name}`, () => {
      const result = c.def === "ClientMessage" ? validateClientMessage(c.message) : validateJoinBody(c.message);
      expect(result.ok, JSON.stringify(result)).toBe(c.valid);
    });
  }
});

describe("Los mensajes de los escenarios cumplen el schema", () => {
  const files = ["01-emparejar-y-parchear", "02-una-sola-activa-y-revocar", "03-reemparejar-y-rechazar", "04-caducidad"];
  const validate = validatorFor("ClientMessage");
  for (const f of files) {
    it(f, () => {
      const scenario = read(`../../protocol/fixtures/scenarios/${f}.json`);
      for (const step of scenario.steps) {
        if (!step.send) continue;
        const msg = JSON.parse(JSON.stringify({ v: 1, ...step.send }), (_k, v) =>
          typeof v === "string" && v.startsWith("$now") ? Date.now() + 1500
            : typeof v === "string" && v.startsWith("$") ? (/(code)/.test(v) ? "K7QX4M" : "sess-0001-aaaa") : v);
        // Se ignoran los casos de error deliberado (ids inexistentes o patch con tipos libres): solo importa la forma.
        if (!validate(msg)) throw new Error(`${f}: ${JSON.stringify(step.send)} → ${JSON.stringify(validate.errors)}`);
      }
    });
  }
});
