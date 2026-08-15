import { createHash, createHmac, randomUUID } from "node:crypto";

const adapter = process.env.TFO_STORAGE_ADAPTER;
const secret = process.env.TFO_STORAGE_REQUEST_JWT_SECRET;
const host = process.env.TFO_STORAGE_HOST || "127.0.0.1";
const port = process.env.TFO_STORAGE_PORT || "8080";
if (!adapter || !secret)
  throw new Error("Run npm run init before the smoke test");

const encode = (value) =>
  Buffer.from(JSON.stringify(value), "utf8").toString("base64url");
async function signedList(rawPath) {
  const now = Math.floor(Date.now() / 1000);
  const header = encode({ alg: "HS256", typ: "tfo-storage-request+jwt" });
  const claims = encode({
    iss: "thinkfree-office",
    aud: "tfo-http-storage-provider",
    iat: now,
    exp: now + 60,
    jti: randomUUID(),
    request: {
      adapter,
      method: "GET",
      path: rawPath,
      content_length: 0,
      content_sha256: createHash("sha256")
        .update(Buffer.alloc(0))
        .digest("hex"),
    },
  });
  const signature = createHmac("sha256", secret)
    .update(`${header}.${claims}`)
    .digest("base64url");
  const response = await fetch(`http://${host}:${port}${rawPath}`, {
    headers: {
      "X-TFO-Storage-Adapter": adapter,
      "X-TFO-Storage-Request-JWT": `${header}.${claims}.${signature}`,
    },
  });
  if (!response.ok)
    throw new Error(
      `List failed with HTTP ${response.status}: ${await response.text()}`,
    );
  return (await response.json()).entries.map((entry) => entry.name);
}

const rootNames = await signedList("/tfo-storage/v1/list");
for (const expected of ["Welcome.txt", "samples"]) {
  if (!rootNames.includes(expected))
    throw new Error(`Root list did not contain ${expected}`);
}
const sampleNames = await signedList("/tfo-storage/v1/samples/list");
for (const expected of ["sample.docx", "sample.xlsx", "sample.pptx"]) {
  if (!sampleNames.includes(expected))
    throw new Error(`Sample list did not contain ${expected}`);
}
console.log(`Signed listing succeeded: ${sampleNames.join(", ")}`);
