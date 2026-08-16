#!/usr/bin/env python3
"""Initialize and run the Python local-directory Provider example."""

from __future__ import annotations

import json
import os
import secrets
from pathlib import Path

import uvicorn

directory = Path(__file__).resolve().parent
configuration_file = directory / ".provider-config.json"
storage_root = directory / "storage"
if configuration_file.exists():
    configuration = json.loads(configuration_file.read_text(encoding="utf-8"))
else:
    configuration = {
        "host": "127.0.0.1",
        "port": 8080,
        "storageRoot": str(storage_root),
        "rootName": "Documents",
        "adapter": "local-directory-python",
        "requestJwtSecret": secrets.token_urlsafe(32),
        "maxDocumentBytes": 314_572_800,
    }
    configuration_file.write_text(
        json.dumps(configuration, indent=2) + "\n", encoding="utf-8"
    )
    configuration_file.chmod(0o600)
    print(
        "Created an ignored local configuration in examples/python/.provider-config.json."
    )

environment = {
    "TFO_STORAGE_HOST": str(configuration["host"]),
    "TFO_STORAGE_PORT": str(configuration["port"]),
    "TFO_STORAGE_ROOT": str(configuration["storageRoot"]),
    "TFO_STORAGE_ROOT_NAME": str(configuration["rootName"]),
    "TFO_STORAGE_ADAPTER": str(configuration["adapter"]),
    "TFO_STORAGE_REQUEST_JWT_SECRET": str(configuration["requestJwtSecret"]),
    "TFO_STORAGE_MAX_DOCUMENT_BYTES": str(configuration["maxDocumentBytes"]),
}
os.environ.update(environment)

print(f"Adapter name: {environment['TFO_STORAGE_ADAPTER']}")
print(f"Request JWT secret: {environment['TFO_STORAGE_REQUEST_JWT_SECRET']}")
print(
    f"Provider base URL: http://{environment['TFO_STORAGE_HOST']}:{environment['TFO_STORAGE_PORT']}"
)
print(f"Storage root: {Path(environment['TFO_STORAGE_ROOT']).resolve()}")
uvicorn.run(
    "app.main:create_app",
    factory=True,
    host=environment["TFO_STORAGE_HOST"],
    port=int(environment["TFO_STORAGE_PORT"]),
    log_level="info",
)
