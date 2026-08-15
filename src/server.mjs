import { pathToFileURL } from "node:url";
import { createApp } from "./app.mjs";
import { loadConfig } from "./config.mjs";

export async function startServer(config = loadConfig()) {
  const app = await createApp(config);
  return new Promise((resolve, reject) => {
    const server = app.listen(config.port, config.host, () => resolve(server));
    server.once("error", reject);
  });
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const config = loadConfig();
  const server = await startServer(config);
  const address = server.address();
  console.log(
    `Thinkfree HTTP Storage Provider listening on ${address.address}:${address.port}`,
  );
  console.log(`Storage root: ${config.storageRoot}`);
  console.log(`Adapter: ${config.adapter}`);
}
