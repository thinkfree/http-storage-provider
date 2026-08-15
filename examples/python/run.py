#!/usr/bin/env python3
"""Initialize and run the Python local-directory Provider example."""

from __future__ import annotations

import json
from pathlib import Path
import secrets

import uvicorn

from provider import ProviderConfig, create_app


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
        "maxDocumentBytes": 536_870_912,
    }
    configuration_file.write_text(json.dumps(configuration, indent=2) + "\n", encoding="utf-8")
    configuration_file.chmod(0o600)
    print("Created an ignored local configuration in examples/python/.provider-config.json.")

config = ProviderConfig(
    host=configuration["host"],
    port=int(configuration["port"]),
    storage_root=Path(configuration["storageRoot"]),
    root_name=configuration["rootName"],
    adapter=configuration["adapter"],
    request_jwt_secret=configuration["requestJwtSecret"],
    max_document_bytes=int(configuration["maxDocumentBytes"]),
)
print(f"Adapter name: {config.adapter}")
print(f"Request JWT secret: {config.request_jwt_secret}")
print(f"Provider base URL: http://{config.host}:{config.port}")
print(f"Storage root: {config.storage_root.resolve()}")
uvicorn.run(create_app(config), host=config.host, port=config.port, log_level="info")
