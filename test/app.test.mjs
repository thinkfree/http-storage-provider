import { createHmac, createHash, randomUUID } from "node:crypto";
import {
  mkdtemp,
  mkdir,
  readFile,
  rm,
  symlink,
  writeFile,
} from "node:fs/promises";
import { request as httpRequest } from "node:http";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import assert from "node:assert/strict";
import { startServer } from "../src/server.mjs";

const ADAPTER = "customer-storage-a";
const SECRET = "reference-provider-test-secret-at-least-32-bytes";

function sha256(body) {
  return createHash("sha256").update(body).digest("hex");
}

function encode(value) {
  return Buffer.from(JSON.stringify(value), "utf8").toString("base64url");
}

function sign({
  method,
  rawPath,
  body = Buffer.alloc(0),
  contentType,
  jti = randomUUID(),
}) {
  const now = Math.floor(Date.now() / 1000);
  const request = {
    adapter: ADAPTER,
    method,
    path: rawPath,
    content_length: body.length,
    content_sha256: sha256(body),
  };
  if (contentType) request.content_type = contentType;
  const header = encode({ alg: "HS256", typ: "tfo-storage-request+jwt" });
  const payload = encode({
    iss: "thinkfree-office",
    aud: "tfo-http-storage-provider",
    iat: now,
    exp: now + 60,
    jti,
    request,
  });
  const signature = createHmac("sha256", SECRET)
    .update(`${header}.${payload}`)
    .digest("base64url");
  return `${header}.${payload}.${signature}`;
}

function send(
  port,
  {
    method,
    rawPath,
    body = Buffer.alloc(0),
    contentType,
    token,
    adapter = ADAPTER,
  },
) {
  const jwt = token || sign({ method, rawPath, body, contentType });
  return new Promise((resolve, reject) => {
    const headers = {
      "X-TFO-Storage-Adapter": adapter,
      "X-TFO-Storage-Request-JWT": jwt,
    };
    if (contentType) {
      headers["Content-Type"] = contentType;
      headers["Content-Length"] = String(body.length);
    }
    const request = httpRequest(
      { host: "127.0.0.1", port, method, path: rawPath, headers },
      (response) => {
        const chunks = [];
        response.on("data", (chunk) => chunks.push(chunk));
        response.on("end", () =>
          resolve({
            status: response.statusCode,
            headers: response.headers,
            body: Buffer.concat(chunks),
          }),
        );
      },
    );
    request.on("error", reject);
    if (body.length) request.write(body);
    request.end();
  });
}

async function fixture(run) {
  const temporary = await mkdtemp(path.join(os.tmpdir(), "tfo-provider-test-"));
  const storageRoot = path.join(temporary, "storage");
  await mkdir(path.join(storageRoot, "contracts"), { recursive: true });
  await writeFile(
    path.join(storageRoot, "contracts", "sample document.docx"),
    "original",
    "utf8",
  );
  const server = await startServer({
    host: "127.0.0.1",
    port: 0,
    storageRoot,
    rootName: "Documents",
    adapter: ADAPTER,
    requestJwtSecret: SECRET,
    maxDocumentBytes: 1024 * 1024,
  });
  try {
    await run({ storageRoot, port: server.address().port });
  } finally {
    await new Promise((resolve) => server.close(resolve));
    await rm(temporary, { recursive: true, force: true });
  }
}

test("serves the complete signed storage lifecycle", async () =>
  fixture(async ({ storageRoot, port }) => {
    const encodedFile = "contracts/sample%20document.docx";

    const info = await send(port, {
      method: "GET",
      rawPath: `/tfo-storage/v1/${encodedFile}/info`,
    });
    assert.equal(info.status, 200);
    assert.deepEqual(JSON.parse(info.body), {
      ...JSON.parse(info.body),
      path: "contracts/sample document.docx",
      name: "sample document.docx",
      type: "file",
      size: 8,
      readable: true,
      writable: true,
      locked: false,
      locker: null,
    });

    const list = await send(port, {
      method: "GET",
      rawPath: "/tfo-storage/v1/contracts/list",
    });
    assert.equal(list.status, 200);
    assert.equal(
      JSON.parse(list.body).entries[0].path,
      "contracts/sample document.docx",
    );

    const get = await send(port, {
      method: "GET",
      rawPath: `/tfo-storage/v1/${encodedFile}/get`,
    });
    assert.equal(get.status, 200);
    assert.equal(get.headers["content-length"], "8");
    assert.equal(get.body.toString("utf8"), "original");

    const lockBody = Buffer.from('{"owner":"office-runtime-1"}', "utf8");
    const lock = await send(port, {
      method: "POST",
      rawPath: `/tfo-storage/v1/${encodedFile}/lock`,
      body: lockBody,
      contentType: "application/json",
    });
    assert.equal(lock.status, 204);

    const saved = Buffer.from("saved-document", "utf8");
    const put = await send(port, {
      method: "PUT",
      rawPath: `/tfo-storage/v1/${encodedFile}/put`,
      body: saved,
      contentType: "application/octet-stream",
    });
    assert.equal(put.status, 200);
    assert.equal(
      await readFile(
        path.join(storageRoot, "contracts", "sample document.docx"),
        "utf8",
      ),
      "saved-document",
    );

    const unlock = await send(port, {
      method: "POST",
      rawPath: `/tfo-storage/v1/${encodedFile}/unlock`,
      body: lockBody,
      contentType: "application/json",
    });
    assert.equal(unlock.status, 204);

    const mkdirBody = Buffer.from('{"name":"archive"}', "utf8");
    assert.equal(
      (
        await send(port, {
          method: "POST",
          rawPath: "/tfo-storage/v1/contracts/mkdir",
          body: mkdirBody,
          contentType: "application/json",
        })
      ).status,
      204,
    );

    const renameBody = Buffer.from('{"name":"renamed.docx"}', "utf8");
    assert.equal(
      (
        await send(port, {
          method: "POST",
          rawPath: `/tfo-storage/v1/${encodedFile}/rename`,
          body: renameBody,
          contentType: "application/json",
        })
      ).status,
      204,
    );

    assert.equal(
      (
        await send(port, {
          method: "DELETE",
          rawPath: "/tfo-storage/v1/contracts/renamed.docx/delete",
        })
      ).status,
      204,
    );
    assert.equal(
      (
        await send(port, {
          method: "DELETE",
          rawPath: "/tfo-storage/v1/contracts/archive/delete",
        })
      ).status,
      204,
    );
  }));

test("rejects replayed and body-mismatched requests before storage access", async () =>
  fixture(async ({ storageRoot, port }) => {
    const rawPath = "/tfo-storage/v1/contracts/sample%20document.docx/info";
    const token = sign({ method: "GET", rawPath });
    assert.equal(
      (await send(port, { method: "GET", rawPath, token })).status,
      200,
    );
    assert.equal(
      (await send(port, { method: "GET", rawPath, token })).status,
      401,
    );

    const signedBody = Buffer.from("signed", "utf8");
    const sentBody = Buffer.from("forged", "utf8");
    const putPath = "/tfo-storage/v1/contracts/forged.docx/put";
    const forgedToken = sign({
      method: "PUT",
      rawPath: putPath,
      body: signedBody,
      contentType: "application/octet-stream",
    });
    assert.equal(
      (
        await send(port, {
          method: "PUT",
          rawPath: putPath,
          body: sentBody,
          contentType: "application/octet-stream",
          token: forgedToken,
        })
      ).status,
      401,
    );
    await assert.rejects(
      readFile(path.join(storageRoot, "contracts", "forged.docx")),
      { code: "ENOENT" },
    );

    const invalidLockBody = Buffer.from('{"owner":""}', "utf8");
    assert.equal(
      (
        await send(port, {
          method: "POST",
          rawPath: "/tfo-storage/v1/contracts/sample%20document.docx/lock",
          body: invalidLockBody,
          contentType: "application/json",
        })
      ).status,
      400,
    );
  }));

test("contains paths and refuses symbolic links and root deletion", async () =>
  fixture(async ({ storageRoot, port }) => {
    const outside = await mkdtemp(
      path.join(os.tmpdir(), "tfo-provider-outside-"),
    );
    try {
      await writeFile(path.join(outside, "secret.docx"), "outside", "utf8");
      await symlink(outside, path.join(storageRoot, "outside-link"));

      assert.equal(
        (
          await send(port, {
            method: "GET",
            rawPath: "/tfo-storage/v1/outside-link/secret.docx/info",
          })
        ).status,
        403,
      );
      assert.equal(
        (
          await send(port, {
            method: "DELETE",
            rawPath: "/tfo-storage/v1/delete",
          })
        ).status,
        400,
      );
      assert.equal(
        (
          await send(port, {
            method: "GET",
            rawPath: "/tfo-storage/v1/%2E%2E/info",
          })
        ).status,
        400,
      );
    } finally {
      await rm(outside, { recursive: true, force: true });
    }
  }));

test("health endpoint does not expose configuration", async () =>
  fixture(async ({ port }) => {
    const response = await new Promise((resolve, reject) => {
      const request = httpRequest(
        { host: "127.0.0.1", port, method: "GET", path: "/healthz" },
        (result) => {
          const chunks = [];
          result.on("data", (chunk) => chunks.push(chunk));
          result.on("end", () =>
            resolve({ status: result.statusCode, body: Buffer.concat(chunks) }),
          );
        },
      );
      request.on("error", reject);
      request.end();
    });
    assert.equal(response.status, 200);
    assert.equal(response.body.toString("utf8"), "ok\n");
  }));
