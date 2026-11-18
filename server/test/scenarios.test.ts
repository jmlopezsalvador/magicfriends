/// <reference types="vite/client" />
import { describe, it } from "vitest";
import { runScenario, type Scenario } from "./runner.ts";

const files = import.meta.glob("../../protocol/fixtures/scenarios/*.json", { eager: true, import: "default" }) as Record<string, Scenario>;

describe("Escenarios de protocol/fixtures/scenarios", () => {
  for (const [path, scenario] of Object.entries(files).sort(([a], [b]) => a.localeCompare(b))) {
    it(`${path.split("/").pop()}: ${scenario.name}`, async () => {
      await runScenario(scenario);
    });
  }
});
