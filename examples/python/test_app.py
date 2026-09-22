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
from unittest.mock import Mock
from urllib.parse import quote

import uvicorn
from pydantic import ValidationError

from app.config import Settings
from app.exceptions import RequestAuthenticationError
from app.main import create_app
from app.security import RequestJwtVerifier

ADAPTER = "customer-storage-a"
SECRET = "python-provider-test-secret-at-least-32-bytes"


def encode(value: object) -> str:
    # Whitespace ensures verification uses the original signing input.
    return (
        base64.urlsafe_b64encode(
            (
                " " + json.dumps(value, ensure_ascii=False, separators=(",", ":")) + " "
            ).encode("utf-8")
        )
        .decode("ascii")
        .rstrip("=")
    )


def sign(
    method: str,
    path: str,
    body: bytes = b"",
    content_type: str | None = None,
    *,
    request_overrides=None,
    claims_overrides=None,
    header_overrides=None,
    secret=SECRET,
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
    request.update(request_overrides or {})
    header = encode(
        {"alg": "HS256", "typ": "tfo-storage-request+jwt", **(header_overrides or {})}
    )
    claims = encode(
        {
            "iss": "thinkfree-office",
            "aud": "tfo-http-storage-provider",
            "iat": now,
            "exp": now + 60,
            "jti": str(uuid.uuid4()),
            "request": request,
            **(claims_overrides or {}),
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

    def test_retired_protocol_route_does_not_expose_documents(self) -> None:
        status, _, _ = self.send(
            "GET", "/tfo-storage/v1/contracts/sample%20document.docx/get"
        )
        self.assertEqual(404, status)

    def test_unicode_document_path_preserves_the_original_signed_target(self) -> None:
        document = "contracts/계약 2026.docx"
        (self.root / document).write_bytes(b"original")
        encoded = quote(f"/tfo-http-storage/v1/{document}", safe="/")
        status, _, body = self.send("GET", f"{encoded}/info")
        self.assertEqual(200, status)
        self.assertEqual(document, json.loads(body)["path"])
        status, _, body = self.send("GET", f"{encoded}/get")
        self.assertEqual((200, b"original"), (status, body))
        status, _, _ = self.send(
            "PUT", f"{encoded}/put", b"saved", "application/octet-stream"
        )
        self.assertEqual(200, status)
        status, _, body = self.send("GET", f"{encoded}/get")
        self.assertEqual((200, b"saved"), (status, body))
        token = sign(
            "GET",
            f"{encoded}/get",
            request_overrides={"path": f"/tfo-http-storage/v1/{document}/get"},
        )
        self.assertEqual(401, self.send("GET", f"{encoded}/get", token=token)[0])

    def test_rejects_stored_documents_above_the_configured_limit(self) -> None:
        oversized = self.root / "contracts" / "oversized.docx"
        oversized.write_bytes(b"x" * (1024 * 1024 + 1))
        status, _, _ = self.send(
            "GET", "/tfo-http-storage/v1/contracts/oversized.docx/info"
        )
        self.assertEqual(413, status)
        status, _, _ = self.send(
            "GET", "/tfo-http-storage/v1/contracts/oversized.docx/get"
        )
        self.assertEqual(413, status)

    def test_document_hard_gate_cannot_be_configured_above_300_mib(self) -> None:
        with self.assertRaises(ValidationError):
            Settings(
                root=self.root,
                adapter=ADAPTER,
                request_jwt_secret=SECRET,
                max_document_bytes=314_572_801,
            )

    def send(
        self,
        method: str,
        path: str,
        body: bytes | None = None,
        content_type: str | None = None,
        token: str | None = None,
        adapter: str | None = None,
    ) -> tuple[int, dict[str, str], bytes]:
        actual_body = body or b""
        headers = {
            "X-TFO-Storage-Request-JWT": token
            or sign(method, path, actual_body, content_type),
        }
        if adapter is not None:
            headers["X-TFO-Storage-Adapter"] = adapter
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

    def test_adapter_selection_ignores_legacy_header(self) -> None:
        path = "/tfo-http-storage/v1/contracts/list"
        for adapter in [None, ADAPTER, "other-adapter"]:
            self.assertEqual(200, self.send("GET", path, adapter=adapter)[0])
        for adapter in ["unknown", None, 42, True, [ADAPTER], {"name": ADAPTER}]:
            token = sign("GET", path, request_overrides={"adapter": adapter})
            self.assertEqual(
                401, self.send("GET", path, token=token, adapter=ADAPTER)[0]
            )
        for request in [None, [], {}, "invalid"]:
            token = sign("GET", path, claims_overrides={"request": request})
            self.assertEqual(401, self.send("GET", path, token=token)[0])
        token = sign("GET", path, secret="another-test-secret-at-least-32-bytes")
        self.assertEqual(
            401, self.send("GET", path, token=token, adapter="other-adapter")[0]
        )

    def test_verified_metadata_and_all_binding_checks(self) -> None:
        state = Mock()
        verifier = RequestJwtVerifier(
            Settings(root=self.root, adapter=ADAPTER, request_jwt_secret=SECRET), state
        )
        path = "/prefix/tfo-http-storage/v1/sample%20file/info"

        def verify(token):
            return verifier.verify(
                token, "GET", path, None, 0, hashlib.sha256(b"").hexdigest()
            )

        metadata = {"customer_context": "고객", "nested": [True, None, {"n": 1}]}
        result = verify(
            sign(
                "GET",
                path,
                request_overrides={
                    "client_metadata": metadata,
                    "arguments": {"save_type": "save"},
                },
            )
        )
        self.assertEqual(metadata, result["client_metadata"])
        self.assertEqual({"save_type": "save"}, result["arguments"])
        self.assertEqual(ADAPTER, result["adapter"])
        state.consume_request_id.assert_called_once()
        state.consume_request_id.side_effect = RequestAuthenticationError()
        with self.assertRaises(RequestAuthenticationError):
            verify(sign("GET", path))
        state.consume_request_id.side_effect = None

        depth8 = "leaf"
        for _ in range(8):
            depth8 = {"child": depth8}
        exact_bytes = {"a": "x" * 512, "b": "x" * 512, "c": "x" * 512, "d": "x" * 483}
        self.assertEqual(
            2048, len(json.dumps(exact_bytes, separators=(",", ":")).encode())
        )
        for metadata in [
            {},
            {"text": "é" * 512},
            {"text": "😀" * 256},
            {"k" * 64: "ok"},
            {"😀" * 32: "ok"},
            {"\u00a0": "nonbreaking space is not Java whitespace"},
            depth8,
            exact_bytes,
            {**exact_bytes, "d": "x" * 484},
        ]:
            self.assertEqual(
                metadata,
                verify(
                    sign("GET", path, request_overrides={"client_metadata": metadata})
                )["client_metadata"],
            )
        for metadata in [
            None,
            [],
            "text",
            {"text": "x" * 513},
            {"k" * 65: 1},
            {"😀" * 33: 1},
            {"": 1},
            {" \t\n\u3000": 1},
            {"text": "😀" * 257},
            {"child": depth8},
            {"text": "😀" * 512},
        ]:
            with self.assertRaises(RequestAuthenticationError):
                verify(
                    sign("GET", path, request_overrides={"client_metadata": metadata})
                )

        now = int(time.time())
        cases = [
            {"header_overrides": {"alg": "HS384"}},
            {"header_overrides": {"typ": "JWT"}},
            {"claims_overrides": {"iss": "wrong"}},
            {"claims_overrides": {"aud": ["tfo-http-storage-provider", "wrong"]}},
            {"claims_overrides": {"iat": now + 30}},
            {"claims_overrides": {"exp": now - 1}},
            {"claims_overrides": {"exp": now + 120}},
            {"claims_overrides": {"jti": ""}},
            *[
                {"request_overrides": item}
                for item in [
                    {"method": "POST"},
                    {"path": path.replace("%20", " ")},
                    {"content_length": 1},
                    {"content_length": 0.5},
                    {"content_length": False},
                    {"method": ["GET"]},
                    {"content_sha256": "0" * 64},
                    {"content_type": "application/json"},
                    {"content_type": " "},
                    {"content_type": None},
                ]
            ],
        ]
        for options in cases:
            state.reset_mock()
            with self.assertRaises(RequestAuthenticationError):
                verify(sign("GET", path, **options))
            state.consume_request_id.assert_not_called()
        with self.assertRaises(RequestAuthenticationError):
            verify("x" * 8193)

        # Valid /open input expands under Nimbus number serialization.
        input_json = '{"items":[' + ",".join(["1e-7"] * 700) + "]}"
        self.assertEqual(3511, len(input_json.encode()))
        self.assertEqual(4911, len(input_json.replace("1e-7", "1.0E-7").encode()))
        numeric_metadata = json.loads(input_json)
        original = sign(
            "GET", path, request_overrides={"client_metadata": numeric_metadata}
        )
        header, payload, _ = original.split(".")
        payload_json = (
            base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))
            .decode()
            .replace("1e-07", "1.0E-7")
        )
        payload = base64.urlsafe_b64encode(payload_json.encode()).decode().rstrip("=")
        signature = (
            base64.urlsafe_b64encode(
                hmac.new(
                    SECRET.encode(), f"{header}.{payload}".encode(), hashlib.sha256
                ).digest()
            )
            .decode()
            .rstrip("=")
        )
        expanded_token = f"{header}.{payload}.{signature}"
        self.assertGreater(len(expanded_token.encode()), 5120)
        self.assertLessEqual(len(expanded_token.encode()), 8192)
        self.assertEqual(numeric_metadata, verify(expanded_token)["client_metadata"])

    def test_put_returns_stable_json_document_identity(self) -> None:
        ids = []
        for file, contents in [
            ("contracts/saved document.docx", b"first"),
            ("contracts/saved document.docx", b"changed contents"),
            ("contracts/other.docx", b"changed contents"),
        ]:
            status, headers, body = self.send(
                "PUT",
                f"/tfo-http-storage/v1/{file.replace(' ', '%20')}/put",
                contents,
                "application/octet-stream",
            )
            self.assertEqual(200, status)
            self.assert_fixed_response(headers, body, "application/json")
            result = json.loads(body)
            self.assertEqual(
                {"docId": hashlib.sha256(file.encode()).hexdigest()}, result
            )
            ids.append(result["docId"])
        self.assertEqual(ids[0], ids[1])
        self.assertNotEqual(ids[1], ids[2])

    def test_complete_storage_lifecycle(self) -> None:
        file = "contracts/sample%20document.docx"
        status, headers, body = self.send("GET", f"/tfo-http-storage/v1/{file}/info")
        self.assertEqual(200, status)
        self.assert_fixed_response(headers, body, "application/json")
        self.assertEqual("contracts/sample document.docx", json.loads(body)["path"])

        status, headers, body = self.send("GET", "/tfo-http-storage/v1/contracts/list")
        self.assertEqual(200, status)
        self.assert_fixed_response(headers, body, "application/json")
        self.assertEqual("sample document.docx", json.loads(body)["entries"][0]["name"])

        status, headers, body = self.send("GET", f"/tfo-http-storage/v1/{file}/get")
        self.assertEqual(200, status)
        self.assert_fixed_response(headers, body, "application/octet-stream")
        self.assertEqual("8", headers["content-length"])
        self.assertEqual(b"original", body)

        lock = b'{"owner":"office-runtime-1"}'
        self.assertEqual(
            204,
            self.send(
                "POST", f"/tfo-http-storage/v1/{file}/lock", lock, "application/json"
            )[0],
        )
        saved = b"saved-document"
        self.assertEqual(
            200,
            self.send(
                "PUT",
                f"/tfo-http-storage/v1/{file}/put",
                saved,
                "application/octet-stream",
            )[0],
        )
        self.assertEqual(
            "saved-document",
            (self.root / "contracts" / "sample document.docx").read_text(),
        )
        self.assertEqual(
            204,
            self.send(
                "POST", f"/tfo-http-storage/v1/{file}/unlock", lock, "application/json"
            )[0],
        )
        self.assertEqual(
            204,
            self.send(
                "POST",
                "/tfo-http-storage/v1/contracts/mkdir",
                b'{"name":"archive"}',
                "application/json",
            )[0],
        )
        self.assertEqual(
            204,
            self.send(
                "POST",
                f"/tfo-http-storage/v1/{file}/rename",
                b'{"name":"renamed.docx"}',
                "application/json",
            )[0],
        )
        self.assertEqual(
            204,
            self.send("DELETE", "/tfo-http-storage/v1/contracts/renamed.docx/delete")[
                0
            ],
        )
        self.assertEqual(
            204, self.send("DELETE", "/tfo-http-storage/v1/contracts/archive/delete")[0]
        )

    def assert_fixed_response(
        self, headers: dict[str, str], body: bytes, content_type: str
    ) -> None:
        self.assertNotIn("transfer-encoding", headers)
        self.assertNotIn("content-encoding", headers)
        self.assertEqual(content_type, headers["content-type"].split(";", 1)[0])
        self.assertEqual(str(len(body)), headers["content-length"])

    def test_replay_and_path_traversal_are_rejected(self) -> None:
        path = "/tfo-http-storage/v1/contracts/sample%20document.docx/info"
        token = sign("GET", path)
        self.assertEqual(200, self.send("GET", path, token=token)[0])
        self.assertEqual(401, self.send("GET", path, token=token)[0])
        self.assertEqual(400, self.send("GET", "/tfo-http-storage/v1/%2E%2E/info")[0])
        self.assertEqual(400, self.send("DELETE", "/tfo-http-storage/v1/delete")[0])
        self.assertEqual(
            400,
            self.send(
                "POST",
                "/tfo-http-storage/v1/contracts/sample%20document.docx/lock",
                b'{"owner":""}',
                "application/json",
            )[0],
        )

    def test_declares_every_optional_operation_unsupported_only_after_authentication(
        self,
    ) -> None:
        self.restart_server("list,put,lock,unlock,mkdir,rename,delete")
        cases = (
            ("LIST", "GET", "/tfo-http-storage/v1/contracts/list", None, None),
            (
                "PUT",
                "PUT",
                "/tfo-http-storage/v1/contracts/new.docx/put",
                b"must-not-be-saved",
                "application/octet-stream",
            ),
            (
                "LOCK",
                "POST",
                "/tfo-http-storage/v1/contracts/sample%20document.docx/lock",
                b'{"owner":"office-runtime-1"}',
                "application/json",
            ),
            (
                "UNLOCK",
                "POST",
                "/tfo-http-storage/v1/contracts/sample%20document.docx/unlock",
                b'{"owner":"office-runtime-1"}',
                "application/json",
            ),
            (
                "MKDIR",
                "POST",
                "/tfo-http-storage/v1/contracts/mkdir",
                b'{"name":"must-not-exist"}',
                "application/json",
            ),
            (
                "RENAME",
                "POST",
                "/tfo-http-storage/v1/contracts/sample%20document.docx/rename",
                b'{"name":"must-not-exist.docx"}',
                "application/json",
            ),
            (
                "DELETE",
                "DELETE",
                "/tfo-http-storage/v1/contracts/sample%20document.docx/delete",
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
                "/tfo-http-storage/v1/contracts/list",
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
