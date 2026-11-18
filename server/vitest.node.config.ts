import { defineConfig } from "vitest/config";

// Tests en Node (Ajv necesita `new Function`, que el runtime de Workers no permite).
export default defineConfig({
  test: { include: ["test-node/**/*.test.ts"], environment: "node" },
});
