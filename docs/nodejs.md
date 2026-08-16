# Run the Node.js Express Provider

The Node.js server at the repository root is a complete Express application
for TFO HTTP Storage Protocol v1. It uses `storage/` so you can clone the
repository, start the server, and inspect every protocol operation without
configuring an external storage service. The filesystem is an example boundary,
not a production storage recommendation.

## Start the server

Prerequisites: Node.js 22 or later.

```bash
npm install
npm run init
npm start
```

The expected result is a server on `http://127.0.0.1:8080` and a root listing
that contains `Welcome.txt` plus Word, Cell, and Show documents below
`storage/samples/`. `npm run init` creates `.env` only when it does not exist,
so the adapter identity and secret remain stable across restarts.

## Understand the source

| Source | Responsibility |
| --- | --- |
| `src/app.mjs` | Composes the Express application, router, services, and error middleware. |
| `src/server.mjs` | Starts the Express application and reports non-secret configuration. |
| `src/routes/storage-router.mjs` | Implements the HTTP boundary, raw body handling, and operation dispatch. |
| `src/security/request-jwt-verifier.mjs` | Verifies HS256 JWT and actual-request equality. |
| `src/services/local-directory-storage-service.mjs` | Implements the replaceable local storage service. |
| `src/repositories/local-state-store.mjs` | Stores replay IDs, locks, and PUT staging files below a hidden state directory. |
| `src/middleware/error-handler.mjs` | Maps expected errors without exposing secrets. |
| `src/domain/storage-route.mjs` | Parses signed raw paths and enforces path policy. |
| `src/config.mjs` | Validates environment configuration. |
| `test/app.test.mjs` | Starts Express on a real port and exercises the complete lifecycle and security failures. |

The Provider writes request state below
`storage/.tfo-http-storage-state/`. Listing hides this reserved directory, and
protocol paths cannot select it.

## Configure the server

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `TFO_STORAGE_ADAPTER` | Yes | Generated as `local-directory` by `npm run init` | Exact immutable Office adapter name. |
| `TFO_STORAGE_REQUEST_JWT_SECRET` | Yes | Random 256-bit value from `npm run init` | Shared HS256 request secret, at least 32 UTF-8 bytes. |
| `TFO_STORAGE_ROOT` | No | `./storage` | Isolated local directory exposed by the example. |
| `TFO_STORAGE_ROOT_NAME` | No | `Documents` | Display name returned for root metadata. |
| `TFO_STORAGE_HOST` | No | `127.0.0.1` | Listen address. Use a network policy when changing it. |
| `TFO_STORAGE_PORT` | No | `8080` | Listen port. |
| `TFO_STORAGE_MAX_DOCUMENT_BYTES` | No | `314572800` | Provider limit in bytes. It may be lowered but cannot exceed the protocol hard gate of 300 MiB. |
| `TFO_STORAGE_UNSUPPORTED_OPERATIONS` | No | empty | Comma-separated optional operations to demonstrate authenticated `501` capability responses. INFO and GET cannot be disabled; LOCK and UNLOCK must be paired. |

Do not commit `.env` or log the request JWT secret. A container, Pod, or remote
Office server cannot reach the Provider through its own `127.0.0.1`; configure
an address reachable from that runtime.

`storage-router.mjs` serializes the bounded INFO/LIST JSON once and sends the
same buffer with its exact byte length. GET obtains the file size before
headers and pipelines the file stream with that fixed `Content-Length`. Do not
replace either response with Express chunked streaming.

## Run the tests

```bash
npm test
```

The expected result is six passing tests. They cover every operation, a real
save to disk, fixed download length, replay rejection, body digest mismatch,
root deletion, traversal, and symbolic-link containment.

## Run the container

Create `.env` first, then build and run:

```bash
npm run init
docker build -t thinkfree-http-storage-provider:local .
docker run --rm \
  --env-file .env \
  --env TFO_STORAGE_HOST=0.0.0.0 \
  --env TFO_STORAGE_ROOT=/data \
  -p 8080:8080 \
  -v "$PWD/storage:/data" \
  thinkfree-http-storage-provider:local
```

The two explicit values override the host-only paths in `.env` after Docker
loads the adapter name and secret.

For production, replace `LocalDirectoryStorageService` with the intended
backing store, use a shared atomic replay/lock repository across replicas, and
follow the [security checklist](security.md).
