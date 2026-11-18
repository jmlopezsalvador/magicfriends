// Bindings declarados a mano (equivalente a `wrangler types`, sin generar los tipos de runtime).
declare namespace Cloudflare {
  interface Env {
    MAGO: DurableObjectNamespace<import("./mago.ts").MagoDO>;
    DIRECTORY: DurableObjectNamespace<import("./directory.ts").DirectoryDO>;
  }
}
interface Env extends Cloudflare.Env {}
declare namespace Cloudflare {
  interface GlobalProps {
    mainModule: typeof import("./index.ts");
  }
}
