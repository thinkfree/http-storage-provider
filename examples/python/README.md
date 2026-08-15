# Python local-directory Provider

This Python 3.12 example is a complete FastAPI and Uvicorn TFO HTTP Storage
Provider server. It deliberately exposes files below `storage/` so the
protocol remains visible and runnable without another storage service. Replace
the local filesystem calls with production storage operations while retaining
verification and path containment.

Start it without installing a package:

```bash
./run.sh
```

The script creates an ignored virtual environment, installs the pinned FastAPI
and Uvicorn dependencies, and starts the Provider. The first run also creates
an ignored `.provider-config.json`, prints the adapter name, random request JWT
secret, and Provider base URL, and preserves those values on later runs. The
expected root listing contains the tracked sample Office documents in
`storage/samples/`.

With the server running, verify one signed root-list request in another shell:

```bash
.venv/bin/python smoke_test.py
```

Run the complete lifecycle tests with:

```bash
.venv/bin/python -m unittest -v
```

See the repository [Python Provider guide](../../docs/python.md),
[protocol reference](../../docs/protocol.md), and
[security checklist](../../docs/security.md).
