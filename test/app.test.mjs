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
import { loadConfig } from "../src/config.mjs";
import { RequestJwtVerifier } from "../src/security/request-jwt-verifier.mjs";

const ADAPTER = "customer-storage-a";
const SECRET = "reference-provider-test-secret-at-least-32-bytes";

function sha256(body) {
  return createHash("sha256").update(body).digest("hex");
}

function encode(value) {
  // Whitespace ensures verifiers use the original token, not reserialized JSON.
  return Buffer.from(` ${JSON.stringify(value)} `, "utf8").toString(
    "base64url",
  );
}

function sign({
  method,
  rawPath,
  body = Buffer.alloc(0),
  contentType,
  jti = randomUUID(),
  requestOverrides = {},
  claimsOverrides = {},
  headerOverrides = {},
  secret = SECRET,
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
  Object.assign(request, requestOverrides);
  const header = encode({
    alg: "HS256",
    typ: "tfo-storage-request+jwt",
    ...headerOverrides,
  });
  const payload = encode({
    iss: "thinkfree-office",
    aud: "tfo-http-storage-provider",
    iat: now,
    exp: now + 60,
    jti,
    request,
    ...claimsOverrides,
  });
  const signature = createHmac("sha256", secret)
    .update(`${header}.${payload}`)
    .digest("base64url");
  return `${header}.${payload}.${signature}`;
}

function send(
  port,
  { method, rawPath, body = Buffer.alloc(0), contentType, token, adapter },
) {
  const jwt = token || sign({ method, rawPath, body, contentType });
  return new Promise((resolve, reject) => {
    const headers = {
      "X-TFO-Storage-Request-JWT": jwt,
    };
    if (adapter !== undefined) headers["X-TFO-Storage-Adapter"] = adapter;
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

async function fixture(run, overrides = {}) {
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
    unsupportedOperations: new Set(),
    ...overrides,
  });
  try {
    await run({ storageRoot, port: server.address().port });
  } finally {
    await new Promise((resolve) => server.close(resolve));
    await rm(temporary, { recursive: true, force: true });
  }
}

test("JWT adapter selects the configured key independently of legacy headers", async () =>
  fixture(async ({ port }) => {
    const rawPath = "/tfo-storage/v1/contracts/list";
    for (const adapter of [undefined, ADAPTER, "other-adapter"]) {
      assert.equal(
        (await send(port, { method: "GET", rawPath, adapter })).status,
        200,
      );
    }
    for (const adapter of [
      "unknown",
      null,
      42,
      true,
      [ADAPTER],
      { name: ADAPTER },
    ]) {
      const token = sign({
        method: "GET",
        rawPath,
        requestOverrides: { adapter },
      });
      assert.equal(
        (await send(port, { method: "GET", rawPath, token, adapter: ADAPTER }))
          .status,
        401,
      );
    }
    for (const request of [null, [], {}, "invalid"]) {
      const token = sign({
        method: "GET",
        rawPath,
        claimsOverrides: { request },
      });
      assert.equal(
        (await send(port, { method: "GET", rawPath, token })).status,
        401,
      );
    }
    const token = sign({
      method: "GET",
      rawPath,
      secret: "another-test-secret-at-least-32-bytes",
    });
    assert.equal(
      (
        await send(port, {
          method: "GET",
          rawPath,
          token,
          adapter: "other-adapter",
        })
      ).status,
      401,
    );
  }));

test("verifier exposes request and metadata only after all checks and replay consumption", async () => {
  const rawPath = "/prefix/tfo-storage/v1/sample%20file/info";
  const consumed = new Set();
  const verifier = new RequestJwtVerifier(
    { adapter: ADAPTER, requestJwtSecret: SECRET },
    {
      async consumeRequestId(jti) {
        assert.equal(consumed.has(jti), false, "replay");
        consumed.add(jti);
      },
    },
  );
  const verify = (token) =>
    verifier.verify(
      { method: "GET", headers: { "x-tfo-storage-request-jwt": token } },
      { rawPath },
      { length: 0, sha256: sha256(Buffer.alloc(0)) },
    );
  const metadata = { customer_context: "고객", nested: [true, null, { n: 1 }] };
  const token = sign({
    method: "GET",
    rawPath,
    requestOverrides: {
      client_metadata: metadata,
      arguments: { save_type: "save" },
    },
  });
  const result = await verify(token);
  assert.deepEqual(result.client_metadata, metadata);
  assert.deepEqual(result.arguments, { save_type: "save" });
  assert.equal(result.adapter, ADAPTER);
  await assert.rejects(verify(token));
  let depth8 = "leaf";
  for (let i = 0; i < 8; i++) depth8 = { child: depth8 };
  // Compact UTF-8 JSON: 29 bytes of keys/punctuation + 2019 value bytes.
  const exactBytes = {
    a: "x".repeat(512),
    b: "x".repeat(512),
    c: "x".repeat(512),
    d: "x".repeat(483),
  };
  assert.equal(Buffer.byteLength(JSON.stringify(exactBytes)), 2048);
  for (const client_metadata of [
    {},
    { text: "é".repeat(512) },
    { text: "😀".repeat(256) },
    { ["k".repeat(64)]: "ok" },
    { ["😀".repeat(32)]: "ok" },
    { "\u00a0": "nonbreaking space is not Java whitespace" },
    depth8,
    exactBytes,
    { ...exactBytes, d: "x".repeat(484) },
  ]) {
    assert.deepEqual(
      (
        await verify(
          sign({
            method: "GET",
            rawPath,
            requestOverrides: { client_metadata },
          }),
        )
      ).client_metadata,
      client_metadata,
    );
  }
  const invalidMetadata = [
    null,
    [],
    "text",
    { text: "x".repeat(513) },
    { ["k".repeat(65)]: 1 },
    { ["😀".repeat(33)]: 1 },
    { "": 1 },
    { " \t\n\u3000": 1 },
    { text: "😀".repeat(257) },
    { child: depth8 },
    { text: "😀".repeat(512) },
  ];
  for (const client_metadata of invalidMetadata) {
    await assert.rejects(
      verify(
        sign({ method: "GET", rawPath, requestOverrides: { client_metadata } }),
      ),
    );
  }
  const now = Math.floor(Date.now() / 1000);
  for (const options of [
    { headerOverrides: { alg: "HS384" } },
    { headerOverrides: { typ: "JWT" } },
    { claimsOverrides: { iss: "wrong" } },
    { claimsOverrides: { aud: ["tfo-http-storage-provider", "wrong"] } },
    { claimsOverrides: { iat: now + 30 } },
    { claimsOverrides: { exp: now - 1 } },
    { claimsOverrides: { exp: now + 120 } },
    { claimsOverrides: { jti: "" } },
    ...[
      { method: "POST" },
      { path: rawPath.replace("%20", " ") },
      { content_length: 1 },
      { content_length: 0.5 },
      { content_length: false },
      { method: ["GET"] },
      { content_sha256: "0".repeat(64) },
      { content_type: "application/json" },
      { content_type: " " },
      { content_type: null },
    ].map((requestOverrides) => ({ requestOverrides })),
  ]) {
    const before = consumed.size;
    await assert.rejects(verify(sign({ method: "GET", rawPath, ...options })));
    assert.equal(consumed.size, before);
  }
  await assert.rejects(verify("x".repeat(8193)));

  // TFO accepts a 3511-byte /open JSON input whose Nimbus serialization grows
  // to 4911 bytes. The Provider must not impose a second metadata byte cap.
  const numericMetadata = { items: Array(700).fill(1e-7) };
  assert.equal(Buffer.byteLength(JSON.stringify(numericMetadata)), 3511);
  const original = sign({
    method: "GET",
    rawPath,
    requestOverrides: { client_metadata: numericMetadata },
  });
  const parts = original.split(".");
  const payload = Buffer.from(parts[1], "base64url")
    .toString("utf8")
    .replaceAll("1e-7", "1.0E-7");
  assert.equal(
    Buffer.byteLength(
      JSON.stringify(numericMetadata).replaceAll("1e-7", "1.0E-7"),
    ),
    4911,
  );
  parts[1] = Buffer.from(payload).toString("base64url");
  parts[2] = createHmac("sha256", SECRET)
    .update(`${parts[0]}.${parts[1]}`)
    .digest("base64url");
  const expandedToken = parts.join(".");
  assert.ok(Buffer.byteLength(expandedToken) > 5120);
  assert.ok(Buffer.byteLength(expandedToken) <= 8192);
  assert.deepEqual(
    (await verify(expandedToken)).client_metadata,
    numericMetadata,
  );
});

test("serves the complete signed storage lifecycle", async () =>
  fixture(async ({ storageRoot, port }) => {
    const encodedFile = "contracts/sample%20document.docx";

    const info = await send(port, {
      method: "GET",
      rawPath: `/tfo-storage/v1/${encodedFile}/info`,
    });
    assert.equal(info.status, 200);
    assertFixedResponse(info, "application/json");
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
    assertFixedResponse(list, "application/json");
    assert.equal(
      JSON.parse(list.body).entries[0].path,
      "contracts/sample document.docx",
    );

    const get = await send(port, {
      method: "GET",
      rawPath: `/tfo-storage/v1/${encodedFile}/get`,
    });
    assert.equal(get.status, 200);
    assertFixedResponse(get, "application/octet-stream");
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

test("PUT returns a JSON document identity stable across content changes", async () =>
  fixture(async ({ port }) => {
    const ids = [];
    for (const [file, contents] of [
      ["contracts/saved document.docx", "first"],
      ["contracts/saved document.docx", "changed contents"],
      ["contracts/other.docx", "changed contents"],
    ]) {
      const result = await send(port, {
        method: "PUT",
        rawPath: `/tfo-storage/v1/${file.replaceAll(" ", "%20")}/put`,
        body: Buffer.from(contents),
        contentType: "application/octet-stream",
      });
      assert.equal(result.status, 200);
      assertFixedResponse(result, "application/json");
      const body = JSON.parse(result.body);
      assert.deepEqual(body, { docId: sha256(Buffer.from(file)) });
      ids.push(body.docId);
    }
    assert.equal(ids[0], ids[1]);
    assert.notEqual(ids[1], ids[2]);
  }));

function assertFixedResponse(response, contentType) {
  assert.equal(response.headers["transfer-encoding"], undefined);
  assert.equal(response.headers["content-encoding"], undefined);
  assert.equal(response.headers["content-type"].split(";", 1)[0], contentType);
  assert.equal(
    response.headers["content-length"],
    String(response.body.length),
  );
}

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

test("configuration keeps mandatory operations and the lock pair consistent", () => {
  const environment = {
    TFO_STORAGE_ADAPTER: ADAPTER,
    TFO_STORAGE_REQUEST_JWT_SECRET: SECRET,
  };
  assert.throws(
    () =>
      loadConfig({ ...environment, TFO_STORAGE_UNSUPPORTED_OPERATIONS: "get" }),
    /unknown operation/,
  );
  assert.throws(
    () =>
      loadConfig({
        ...environment,
        TFO_STORAGE_UNSUPPORTED_OPERATIONS: "lock",
      }),
    /lock and unlock together/,
  );
  assert.deepEqual(
    [
      ...loadConfig({
        ...environment,
        TFO_STORAGE_UNSUPPORTED_OPERATIONS: "list,lock,unlock",
      }).unsupportedOperations,
    ].sort(),
    ["list", "lock", "unlock"],
  );
  assert.throws(
    () =>
      loadConfig({
        ...environment,
        TFO_STORAGE_MAX_DOCUMENT_BYTES: String(300 * 1024 * 1024 + 1),
      }),
    /314572800/,
  );
});

test("rejects oversized stored documents and metadata responses", async () => {
  await fixture(
    async ({ storageRoot, port }) => {
      await writeFile(
        path.join(storageRoot, "contracts", "oversized.docx"),
        "123456789",
      );
      const info = await send(port, {
        method: "GET",
        rawPath: "/tfo-storage/v1/contracts/oversized.docx/info",
      });
      assert.equal(info.status, 413);
      const get = await send(port, {
        method: "GET",
        rawPath: "/tfo-storage/v1/contracts/oversized.docx/get",
      });
      assert.equal(get.status, 413);
    },
    { maxDocumentBytes: 8 },
  );

  await fixture(
    async ({ port }) => {
      const info = await send(port, {
        method: "GET",
        rawPath: "/tfo-storage/v1/info",
      });
      assert.equal(info.status, 413);
    },
    { rootName: "x".repeat(5 * 1024 * 1024) },
  );
});

test("declares every optional operation unsupported only after authentication and without storage access", async () =>
  fixture(
    async ({ storageRoot, port }) => {
      const cases = [
        [
          "list",
          "GET",
          "/tfo-storage/v1/contracts/list",
          Buffer.alloc(0),
          undefined,
        ],
        [
          "put",
          "PUT",
          "/tfo-storage/v1/contracts/new.docx/put",
          Buffer.from("must-not-be-saved"),
          "application/octet-stream",
        ],
        [
          "lock",
          "POST",
          "/tfo-storage/v1/contracts/sample%20document.docx/lock",
          Buffer.from('{"owner":"office-runtime-1"}'),
          "application/json",
        ],
        [
          "unlock",
          "POST",
          "/tfo-storage/v1/contracts/sample%20document.docx/unlock",
          Buffer.from('{"owner":"office-runtime-1"}'),
          "application/json",
        ],
        [
          "mkdir",
          "POST",
          "/tfo-storage/v1/contracts/mkdir",
          Buffer.from('{"name":"must-not-exist"}'),
          "application/json",
        ],
        [
          "rename",
          "POST",
          "/tfo-storage/v1/contracts/sample%20document.docx/rename",
          Buffer.from('{"name":"must-not-exist.docx"}'),
          "application/json",
        ],
        [
          "delete",
          "DELETE",
          "/tfo-storage/v1/contracts/sample%20document.docx/delete",
          Buffer.alloc(0),
          undefined,
        ],
      ];

      for (const [operation, method, rawPath, body, contentType] of cases) {
        const response = await send(port, {
          method,
          rawPath,
          body,
          contentType,
        });
        assert.equal(response.status, 501, operation);
        assert.match(
          response.headers["content-type"],
          /^application\/json/,
          operation,
        );
        assert.deepEqual(JSON.parse(response.body), {
          code: `${operation.toUpperCase()}_NOT_SUPPORTED`,
        });
      }

      await assert.rejects(
        readFile(path.join(storageRoot, "contracts", "new.docx")),
        { code: "ENOENT" },
      );
      assert.equal(
        await readFile(
          path.join(storageRoot, "contracts", "sample document.docx"),
          "utf8",
        ),
        "original",
      );
      await assert.rejects(
        readFile(path.join(storageRoot, "contracts", "must-not-exist")),
        { code: "ENOENT" },
      );

      const invalid = await send(port, {
        method: "PUT",
        rawPath: "/tfo-storage/v1/contracts/new.docx/put",
        body: Buffer.from("must-not-be-saved"),
        contentType: "application/octet-stream",
        token: "not-a-jwt",
      });
      assert.equal(invalid.status, 401);
    },
    {
      unsupportedOperations: new Set([
        "list",
        "put",
        "lock",
        "unlock",
        "mkdir",
        "rename",
        "delete",
      ]),
    },
  ));
