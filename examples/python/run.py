#!/usr/bin/env python3
"""Initialize and run the Python local-directory Provider example."""

from __future__ import annotations

import json
from pathlib import Path
import secrets

from provider import ProviderConfig, create_server


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
server = create_server(config)
print(f"Adapter name: {config.adapter}")
print(f"Request JWT secret: {config.request_jwt_secret}")
print(f"Provider base URL: http://{config.host}:{server.server_address[1]}")
print(f"Storage root: {config.storage_root.resolve()}")
try:
    server.serve_forever()
except KeyboardInterrupt:
    pass
finally:
    server.server_close()
