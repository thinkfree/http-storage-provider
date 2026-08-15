# Python local-directory Provider

This Python 3.12 example is a complete TFO HTTP Storage Provider server. It
deliberately exposes files below `storage/` so the protocol remains visible and
runnable without another storage service. Replace the local filesystem calls
with production storage operations while retaining verification and path
containment.

Start it without installing a package:

```bash
python3 run.py
```

The first run creates an ignored `.provider-config.json`, prints the adapter
name, random request JWT secret, and Provider base URL, and preserves those
values on later runs. The expected root listing contains the tracked sample
Office documents in `storage/samples/`.

With the server running, verify one signed root-list request in another shell:

```bash
python3 smoke_test.py
```

Run the complete lifecycle tests with:

```bash
python3 -m unittest -v
```

See the repository [Python Provider guide](../../docs/python.md),
[protocol reference](../../docs/protocol.md), and
[security checklist](../../docs/security.md).
