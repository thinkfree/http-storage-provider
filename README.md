# Thinkfree HTTP Storage Provider

Connect Self-hosted Office to document storage through signed, versioned HTTP
requests. This repository contains complete Node.js, Java, and Python Provider servers,
the TFO HTTP Storage Protocol v1 schemas, integration guides, and contract
tests.

**Availability:** Preview. The Provider works with a Self-hosted Office build
that offers **HTTP Storage** as a system adapter. The protocol and packaging can
change before a stable release.

The examples deliberately use a local directory as their backing storage. This
keeps the code focused on request authentication, path handling, streaming,
metadata, locking, and the storage lifecycle. Replace the filesystem operations
with your S3, database, or storage service implementation for production.

## Run the Node.js Express Provider

Prerequisites: Git and Node.js 22 or later.

```bash
git clone https://github.com/thinkfree/http-storage-provider.git
cd http-storage-provider
npm install
npm run init
npm start
```

`npm run init` creates an ignored `.env`, preserves it on later runs, and shows
the three values needed by Office:

```text
Adapter name: local-directory
Request JWT secret: <generated 256-bit value>
Provider base URL: http://127.0.0.1:8080
```

The Express Provider immediately lists `Welcome.txt` and the tracked Word,
Cell, and Show files below [`storage/samples`](storage/samples). Add more files
below `storage/` and they become available without restarting the server.

The expected startup result is:

```text
Thinkfree HTTP Storage Provider listening on 127.0.0.1:8080
Storage root: .../http-storage-provider/storage
Adapter: local-directory
```

## Connect Self-hosted Office

The Provider base URL must be reachable from the Office runtime. Do not use
`127.0.0.1` when Office runs in another container, Pod, virtual machine, or
host; use the Provider's private DNS name or HTTPS origin instead.

1. Open the restricted Self-hosted Office administrator.
2. Open **External linkage → Adapter List** and select **Add**.
3. Select **HTTP Storage**.
4. Enter `local-directory` as the adapter name.
5. Enter the reachable Provider base URL as `endpointUrl`.
6. Enter the generated value as `requestJwtSecret`.
7. Register and enable the adapter.
8. Use the file browser to open `samples/sample.docx`, `sample.xlsx`, or
   `sample.pptx`.

You can also open the sample directly after replacing the Office origin:

```text
https://office.example.com/cloud-office/api/local-directory/samples/sample.docx/open
  ?app=WRITE_EDITOR
  &docId=welcome01
  &user_id=example-user
```

Use `WRITE_EDITOR` for `sample.docx`, `CALC_EDITOR` for `sample.xlsx`, and
`SHOW_EDITOR` for `sample.pptx`. Give each test document a stable, unique
alphanumeric `docId`.

Make a visible edit, save, close the editor, and reopen the file. The changed
document in `storage/samples/` is the expected result.

See the production [connection guide](https://www.developers.thinkfree.com/docs/docker/http-storage/)
and [protocol reference](https://www.developers.thinkfree.com/docs/docker/http-storage-api/)
for the Office-side workflow.

## Run the Java Spring Boot Provider

The Java example is a separate, complete Spring Boot server with the same
tracked Office documents below
[`examples/java/storage/samples`](examples/java/storage/samples). Stop the
Node.js Provider first because both examples use port `8080` by default.

Prerequisites: Java 17 or later, Maven 3.9 or later, and OpenSSL.

```bash
cd examples/java
./run.sh
```

The script creates an ignored `.env.java` once, prints the adapter name,
generated request JWT secret, and Provider base URL, builds the executable JAR,
and starts the server. Use the printed values in the same Office form. The Java
adapter name defaults to `local-directory-java`.

Read the [Java Provider guide](docs/java.md) for direct Maven commands and code
ownership boundaries. Read the [Node.js Provider guide](docs/nodejs.md) for the
root implementation.

## Run the Python FastAPI Provider

The Python 3.12 example uses FastAPI and Uvicorn and has the same tracked Office
documents below
[`examples/python/storage/samples`](examples/python/storage/samples). Stop
another Provider using port `8080`, then run:

Prerequisites: Python 3.12 or later with `venv` and `pip`.

```bash
cd examples/python
./run.sh
```

The first run creates an ignored local configuration and prints the adapter
name `local-directory-python`, generated request JWT secret, and Provider base
URL. See the [Python Provider guide](docs/python.md) for source and test details.

## What is implemented

All three servers implement the complete Provider operation set:

| Operation | Result |
| --- | --- |
| `info` | Returns strict JSON metadata with an exact fixed `Content-Length`. |
| `list` | Returns up to 10,000 direct children with an exact fixed `Content-Length`. |
| `get` | Streams the original file with a fixed `Content-Length`. |
| `put` | Stages, hashes, verifies, and atomically replaces the complete file. |
| `lock` / `unlock` | Keeps owner-aware locks and rejects conflicting owners. |
| `mkdir` | Creates one direct child directory. |
| `rename` | Renames an item inside its current parent. |
| `delete` | Deletes a file or an empty directory and refuses root deletion. |

Every storage request verifies the HS256 signature, JWT type, issuer, audience,
lifetime, unique `jti`, adapter identity, actual HTTP method, raw encoded path,
content type, body length, and SHA-256 before accessing document storage.
Query strings, redirects, cookies, arbitrary forwarding headers, chunked PUT,
path traversal, and symbolic links are not part of this contract.
Successful INFO, LIST, and GET responses also must not use chunked transfer or
content encoding. Providers must determine and publish the exact response byte
length before streaming; GET can still stream arbitrarily large documents.

If a customer implementation intentionally omits an operation, it can return
the protocol's authenticated `501` response with the exact single-field code,
such as `{"code":"LIST_NOT_SUPPORTED"}`. The complete examples support all
nine operations by default; set `TFO_STORAGE_UNSUPPORTED_OPERATIONS=list` (or a
comma-separated operation list) to run and test the same capability behavior.

## Documentation

- [Protocol reference](docs/protocol.md): exact routes, headers, claims, bodies,
  metadata, and errors.
- [Security and production checklist](docs/security.md): trust boundaries,
  replay storage, paths, locks, staging, TLS, and multi-replica requirements.
- [Node.js Provider guide](docs/nodejs.md): Express application structure,
  configuration, tests, and Docker use.
- [Java Provider guide](docs/java.md): Spring Boot application, executable JAR,
  configuration, and tests.
- [Python Provider guide](docs/python.md): FastAPI and Uvicorn startup, source
  map, and tests.
- [Bundled Office samples](docs/sample-documents.md): origin, paths, and expected
  SHA-256 values.
- [Troubleshooting](docs/troubleshooting.md): symptom-first checks and safe
  recovery.
- [JSON Schemas](schemas/v1): Draft 2020-12 metadata contracts copied from the
  current adapter contract.

The corresponding published Thinkfree Developers pages are:

- [Connect a TFO HTTP Storage Provider](https://www.developers.thinkfree.com/docs/docker/http-storage/)
- [TFO HTTP Storage endpoint reference](https://www.developers.thinkfree.com/docs/docker/http-storage-api/)
- [Node.js request verification example](https://www.developers.thinkfree.com/docs/docker/http-storage-nodejs/)
- [Java request verification example](https://www.developers.thinkfree.com/docs/docker/http-storage-java/)

## Verify the repository

```bash
npm ci
npm run check
cd examples/java
mvn test
cd ../python
python3 -m pip install -r requirements.txt -r requirements-dev.txt
ruff check .
ruff format --check .
python3 -m unittest -v
```

The three suites exercise all nine operations against real files, complete
saves, fixed download length, replay rejection, and path protection. The sample
DOCX, XLSX, and PPTX files are the same files shipped with the Thinkfree Office
sample document directory.

## License

Licensed under the [Apache License 2.0](LICENSE).
