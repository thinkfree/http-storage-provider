import { randomBytes } from "node:crypto";
import { access, mkdir, writeFile } from "node:fs/promises";
import path from "node:path";

const configurationFile = path.resolve(".env");
try {
  await access(configurationFile);
  console.log(
    ".env already exists. Existing adapter identity and secret were preserved.",
  );
  process.exit(0);
} catch {
  // Create the first local configuration below.
}

const adapter = "local-directory";
const secret = randomBytes(32).toString("base64url");
const storageRoot = path.resolve("storage");
const configuration = [
  "TFO_STORAGE_HOST=127.0.0.1",
  "TFO_STORAGE_PORT=8080",
  "TFO_STORAGE_ROOT=./storage",
  "TFO_STORAGE_ROOT_NAME=Documents",
  `TFO_STORAGE_ADAPTER=${adapter}`,
  `TFO_STORAGE_REQUEST_JWT_SECRET=${secret}`,
  "TFO_STORAGE_MAX_DOCUMENT_BYTES=314572800",
  "",
].join("\n");

await writeFile(configurationFile, configuration, {
  encoding: "utf8",
  mode: 0o600,
  flag: "wx",
});
await mkdir(storageRoot, { recursive: true, mode: 0o700 });
await writeFile(
  path.join(storageRoot, "Welcome.txt"),
  "This file is served from the local directory used by the TFO HTTP Storage Provider example.\n",
  { encoding: "utf8", mode: 0o600, flag: "wx" },
).catch((error) => {
  if (error.code !== "EEXIST") throw error;
});

console.log("Created an ignored local configuration in .env.");
console.log(`Adapter name: ${adapter}`);
console.log(`Request JWT secret: ${secret}`);
console.log("Provider base URL: http://127.0.0.1:8080");
console.log(
  "Run npm start, then copy the adapter name, base URL, and secret into Self-hosted Office.",
);
