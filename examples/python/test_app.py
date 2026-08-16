from __future__ import annotations

import base64
import hashlib
import hmac
import json
import socket
import tempfile
import threading
import time
import unittest
import uuid
from http.client import HTTPConnection
from pathlib import Path

import uvicorn
from pydantic import ValidationError

from app.config import Settings
from app.main import create_app

ADAPTER = "customer-storage-a"
SECRET = "python-provider-test-secret-at-least-32-bytes"


def encode(value: object) -> str:
    return (
        base64.urlsafe_b64encode(
            json.dumps(value, separators=(",", ":")).encode("utf-8")
        )
        .decode("ascii")
        .rstrip("=")
    )


def sign(
    method: str, path: str, body: bytes = b"", content_type: str | None = None
) -> str:
    now = int(time.time())
    request: dict[str, object] = {
        "adapter": ADAPTER,
        "method": method,
        "path": path,
        "content_length": len(body),
        "content_sha256": hashlib.sha256(body).hexdigest(),
    }
    if content_type is not None:
        request["content_type"] = content_type
    header = encode({"alg": "HS256", "typ": "tfo-storage-request+jwt"})
    claims = encode(
        {
            "iss": "thinkfree-office",
            "aud": "tfo-http-storage-provider",
            "iat": now,
            "exp": now + 60,
            "jti": str(uuid.uuid4()),
            "request": request,
        }
    )
    signature = (
        base64.urlsafe_b64encode(
            hmac.new(
                SECRET.encode(), f"{header}.{claims}".encode(), hashlib.sha256
            ).digest()
        )
        .decode("ascii")
        .rstrip("=")
    )
    return f"{header}.{claims}.{signature}"


class FastApiProviderApplicationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name) / "storage"
        (self.root / "contracts").mkdir(parents=True)
        (self.root / "contracts" / "sample document.docx").write_text(
            "original", encoding="utf-8"
        )
        self.start_server("")

    def start_server(self, unsupported_operations: str) -> None:
        application = create_app(
            Settings(
                host="127.0.0.1",
                port=0,
                root=self.root,
                root_name="Documents",
                adapter=ADAPTER,
                request_jwt_secret=SECRET,
                max_document_bytes=1024 * 1024,
                unsupported_operations=unsupported_operations,
            )
        )
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.socket.bind(("127.0.0.1", 0))
        self.port = self.socket.getsockname()[1]
        self.server = uvicorn.Server(
            uvicorn.Config(
                application,
                host="127.0.0.1",
                port=self.port,
                log_level="warning",
                lifespan="off",
            )
        )
        self.thread = threading.Thread(
            target=self.server.run,
            kwargs={"sockets": [self.socket]},
            daemon=True,
        )
        self.thread.start()
        deadline = time.time() + 5
        while not self.server.started and time.time() < deadline:
            time.sleep(0.01)
        if not self.server.started:
            self.fail("Uvicorn did not start")

    def tearDown(self) -> None:
        self.server.should_exit = True
        self.thread.join(timeout=5)
        self.socket.close()
        self.temporary.cleanup()

    def restart_server(self, unsupported_operations: str) -> None:
        self.server.should_exit = True
        self.thread.join(timeout=5)
        self.socket.close()
        self.start_server(unsupported_operations)

    def send(
        self,
        method: str,
        path: str,
        body: bytes | None = None,
        content_type: str | None = None,
        token: str | None = None,
    ) -> tuple[int, dict[str, str], bytes]:
        actual_body = body or b""
        headers = {
            "X-TFO-Storage-Adapter": ADAPTER,
            "X-TFO-Storage-Request-JWT": token
            or sign(method, path, actual_body, content_type),
        }
        if content_type is not None:
            headers["Content-Type"] = content_type
            headers["Content-Length"] = str(len(actual_body))
        connection = HTTPConnection("127.0.0.1", self.port, timeout=5)
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        result = (
            response.status,
            {name.lower(): value for name, value in response.getheaders()},
            response.read(),
        )
        connection.close()
        return result

    def test_complete_storage_lifecycle(self) -> None:
        file = "contracts/sample%20document.docx"
        status, headers, body = self.send("GET", f"/tfo-storage/v1/{file}/info")
        self.assertEqual(200, status)
        self.assert_fixed_response(headers, body, "application/json")
        self.assertEqual("contracts/sample document.docx", json.loads(body)["path"])

        status, headers, body = self.send("GET", "/tfo-storage/v1/contracts/list")
        self.assertEqual(200, status)
        self.assert_fixed_response(headers, body, "application/json")
        self.assertEqual("sample document.docx", json.loads(body)["entries"][0]["name"])

        status, headers, body = self.send("GET", f"/tfo-storage/v1/{file}/get")
        self.assertEqual(200, status)
        self.assert_fixed_response(headers, body, "application/octet-stream")
        self.assertEqual("8", headers["content-length"])
        self.assertEqual(b"original", body)

        lock = b'{"owner":"office-runtime-1"}'
        self.assertEqual(
            204,
            self.send("POST", f"/tfo-storage/v1/{file}/lock", lock, "application/json")[
                0
            ],
        )
        saved = b"saved-document"
        self.assertEqual(
            200,
            self.send(
                "PUT", f"/tfo-storage/v1/{file}/put", saved, "application/octet-stream"
            )[0],
        )
        self.assertEqual(
            "saved-document",
            (self.root / "contracts" / "sample document.docx").read_text(),
        )
        self.assertEqual(
            204,
            self.send(
                "POST", f"/tfo-storage/v1/{file}/unlock", lock, "application/json"
            )[0],
        )
        self.assertEqual(
            204,
            self.send(
                "POST",
                "/tfo-storage/v1/contracts/mkdir",
                b'{"name":"archive"}',
                "application/json",
            )[0],
        )
        self.assertEqual(
            204,
            self.send(
                "POST",
                f"/tfo-storage/v1/{file}/rename",
                b'{"name":"renamed.docx"}',
                "application/json",
            )[0],
        )
        self.assertEqual(
            204, self.send("DELETE", "/tfo-storage/v1/contracts/renamed.docx/delete")[0]
        )
        self.assertEqual(
            204, self.send("DELETE", "/tfo-storage/v1/contracts/archive/delete")[0]
        )

    def assert_fixed_response(
        self, headers: dict[str, str], body: bytes, content_type: str
    ) -> None:
        self.assertNotIn("transfer-encoding", headers)
        self.assertNotIn("content-encoding", headers)
        self.assertEqual(content_type, headers["content-type"].split(";", 1)[0])
        self.assertEqual(str(len(body)), headers["content-length"])

    def test_replay_and_path_traversal_are_rejected(self) -> None:
        path = "/tfo-storage/v1/contracts/sample%20document.docx/info"
        token = sign("GET", path)
        self.assertEqual(200, self.send("GET", path, token=token)[0])
        self.assertEqual(401, self.send("GET", path, token=token)[0])
        self.assertEqual(400, self.send("GET", "/tfo-storage/v1/%2E%2E/info")[0])
        self.assertEqual(400, self.send("DELETE", "/tfo-storage/v1/delete")[0])
        self.assertEqual(
            400,
            self.send(
                "POST",
                "/tfo-storage/v1/contracts/sample%20document.docx/lock",
                b'{"owner":""}',
                "application/json",
            )[0],
        )

    def test_declares_every_optional_operation_unsupported_only_after_authentication(
        self,
    ) -> None:
        self.restart_server("list,put,lock,unlock,mkdir,rename,delete")
        cases = (
            ("LIST", "GET", "/tfo-storage/v1/contracts/list", None, None),
            (
                "PUT",
                "PUT",
                "/tfo-storage/v1/contracts/new.docx/put",
                b"must-not-be-saved",
                "application/octet-stream",
            ),
            (
                "LOCK",
                "POST",
                "/tfo-storage/v1/contracts/sample%20document.docx/lock",
                b'{"owner":"office-runtime-1"}',
                "application/json",
            ),
            (
                "UNLOCK",
                "POST",
                "/tfo-storage/v1/contracts/sample%20document.docx/unlock",
                b'{"owner":"office-runtime-1"}',
                "application/json",
            ),
            (
                "MKDIR",
                "POST",
                "/tfo-storage/v1/contracts/mkdir",
                b'{"name":"must-not-exist"}',
                "application/json",
            ),
            (
                "RENAME",
                "POST",
                "/tfo-storage/v1/contracts/sample%20document.docx/rename",
                b'{"name":"must-not-exist.docx"}',
                "application/json",
            ),
            (
                "DELETE",
                "DELETE",
                "/tfo-storage/v1/contracts/sample%20document.docx/delete",
                None,
                None,
            ),
        )

        for operation, method, path, request_body, content_type in cases:
            status, headers, response_body = self.send(
                method, path, request_body, content_type
            )
            self.assertEqual(501, status, operation)
            self.assertTrue(
                headers["content-type"].startswith("application/json"), operation
            )
            self.assertEqual(
                {"code": f"{operation}_NOT_SUPPORTED"}, json.loads(response_body)
            )

        self.assertEqual(
            "original",
            (self.root / "contracts" / "sample document.docx").read_text(
                encoding="utf-8"
            ),
        )
        self.assertFalse((self.root / "contracts" / "new.docx").exists())
        self.assertFalse((self.root / "contracts" / "must-not-exist").exists())
        self.assertEqual(
            401,
            self.send(
                "GET",
                "/tfo-storage/v1/contracts/list",
                token="not-a-jwt",
            )[0],
        )

    def test_configuration_keeps_mandatory_operations_and_lock_pair_consistent(
        self,
    ) -> None:
        common = {
            "root": self.root,
            "adapter": ADAPTER,
            "request_jwt_secret": SECRET,
        }
        with self.assertRaises(ValidationError):
            Settings(**common, unsupported_operations="get")
        with self.assertRaises(ValidationError):
            Settings(**common, unsupported_operations="lock")


if __name__ == "__main__":
    unittest.main()
