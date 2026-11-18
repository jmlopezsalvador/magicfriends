import { defineConfig } from "vitest/config";
import { cloudflareTest } from "@cloudflare/vitest-plugin";

// Tests que corren dentro del runtime de Workers (Durable Objects reales, vía Miniflare).
export default defineConfig({
  plugins: [cloudflareTest({ wrangler: { configPath: "./wrangler.jsonc" } })],
  server: { fs: { allow: ["..", "../protocol"] } },
  test: { include: ["test/**/*.test.ts"], testTimeout: 20000 },
});
