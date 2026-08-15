"""Send one signed root-list request to a running local Python example."""

import base64
import hashlib
import hmac
import json
import time
import uuid
from http.client import HTTPConnection
from pathlib import Path

configuration = json.loads(
    (Path(__file__).resolve().parent / ".provider-config.json").read_text(
        encoding="utf-8"
    )
)
adapter = configuration["adapter"]
secret = configuration["requestJwtSecret"]


def encode(value: object) -> str:
    return (
        base64.urlsafe_b64encode(
            json.dumps(value, separators=(",", ":")).encode("utf-8")
        )
        .decode("ascii")
        .rstrip("=")
    )


def signed_list(path: str) -> list[str]:
    now = int(time.time())
    header = encode({"alg": "HS256", "typ": "tfo-storage-request+jwt"})
    claims = encode(
        {
            "iss": "thinkfree-office",
            "aud": "tfo-http-storage-provider",
            "iat": now,
            "exp": now + 60,
            "jti": str(uuid.uuid4()),
            "request": {
                "adapter": adapter,
                "method": "GET",
                "path": path,
                "content_length": 0,
                "content_sha256": hashlib.sha256(b"").hexdigest(),
            },
        }
    )
    signature = (
        base64.urlsafe_b64encode(
            hmac.new(
                secret.encode(), f"{header}.{claims}".encode(), hashlib.sha256
            ).digest()
        )
        .decode("ascii")
        .rstrip("=")
    )
    connection = HTTPConnection(configuration["host"], configuration["port"], timeout=5)
    connection.request(
        "GET",
        path,
        headers={
            "X-TFO-Storage-Adapter": adapter,
            "X-TFO-Storage-Request-JWT": f"{header}.{claims}.{signature}",
        },
    )
    response = connection.getresponse()
    body = response.read()
    connection.close()
    if response.status != 200:
        raise SystemExit(
            f"Signed list failed with HTTP {response.status}: {body.decode()}"
        )
    return [entry["name"] for entry in json.loads(body)["entries"]]


root_names = signed_list("/tfo-storage/v1/list")
if "Welcome.txt" not in root_names or "samples" not in root_names:
    raise SystemExit("Root list did not contain Welcome.txt and samples")
sample_names = signed_list("/tfo-storage/v1/samples/list")
for expected in ("sample.docx", "sample.xlsx", "sample.pptx"):
    if expected not in sample_names:
        raise SystemExit(f"Sample list did not contain {expected}")
print(f"Signed listing succeeded: {', '.join(sample_names)}")
