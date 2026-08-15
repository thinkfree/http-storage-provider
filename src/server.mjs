import {createServer} from "node:http";
import {pathToFileURL} from "node:url";
import {loadConfig} from "./config.mjs";
import {createProvider} from "./provider.mjs";

export async function startServer(config = loadConfig()) {
  const provider = await createProvider(config);
  const server = createServer((request, response) => {
    provider(request, response).catch((error) => {
      console.error("Unhandled Provider error");
      if (!response.headersSent) response.writeHead(500, {"Content-Length": "0"});
      response.end();
    });
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(config.port, config.host, resolve);
  });
  return server;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const config = loadConfig();
  const server = await startServer(config);
  const address = server.address();
  console.log(`Thinkfree HTTP Storage Provider listening on ${address.address}:${address.port}`);
  console.log(`Storage root: ${config.storageRoot}`);
  console.log(`Adapter: ${config.adapter}`);
}
