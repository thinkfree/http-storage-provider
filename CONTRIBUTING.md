# Contributing

Thank you for improving the Thinkfree HTTP Storage Provider examples and
protocol documentation.

## Before you change code

1. Read the [protocol reference](docs/protocol.md) and
   [security checklist](docs/security.md).
2. Open an issue before changing a route, HTTP method, JWT claim, header,
   metadata field, or operation semantic. Those changes affect the packaged
   Office adapter and every Provider implementation.
3. Keep secrets, request JWTs, customer documents, storage credentials, and
   internal paths out of commits, tests, logs, and issue content.

## Keep every example aligned

Node.js, Java, and Python are complete servers for one protocol. A behavior
change is incomplete until all affected examples, tests, schemas, and documents
agree. Do not add a compatibility route, HTTP Proxy fallback, query parameter,
cookie forwarding, or arbitrary outbound header.

The examples intentionally use local directories. Keep that explanation near
new storage code so readers do not mistake the backing store for a production
requirement.

## Run the checks

```bash
npm ci
npm run check

cd examples/java
mvn test

cd ../python
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m unittest -v
```

Also build the container when changing the root Node.js server or Dockerfile:

```bash
docker build -t thinkfree-http-storage-provider:check .
```

Verify that every sample DOCX, XLSX, and PPTX remains a valid ZIP and that the
same filename has the same SHA-256 in all three example directories.

## Submit a pull request

Use a focused title and explain:

- the developer or operator outcome;
- the protocol or implementation behavior that changed;
- security and compatibility effects;
- commands and end-to-end scenarios you verified.

By submitting a contribution, you agree that it is licensed under the Apache
License 2.0 used by this repository.
