#!/usr/bin/env python3
"""Complete local-directory example for TFO HTTP Storage Protocol v1.

The example uses a local directory to keep the protocol implementation easy to
inspect. Replace the filesystem methods with production storage calls while
preserving request verification, path containment, and operation semantics.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
import re
import tempfile
import time
from typing import Any
from urllib.parse import unquote_to_bytes, urlsplit

from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, JSONResponse, PlainTextResponse, Response


PROTOCOL_PREFIX = "/tfo-storage/v1"
STATE_DIRECTORY = ".tfo-http-storage-state"
EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()
OPERATIONS = {
    "info": "GET",
    "list": "GET",
    "get": "GET",
    "put": "PUT",
    "lock": "POST",
    "unlock": "POST",
    "mkdir": "POST",
    "rename": "POST",
    "delete": "DELETE",
}


class ProviderError(Exception):
    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.message = message


class AuthenticationError(ProviderError):
    def __init__(self) -> None:
        super().__init__(401, "Unauthorized")


@dataclass(frozen=True)
class ProviderConfig:
    host: str
    port: int
    storage_root: Path
    root_name: str
    adapter: str
    request_jwt_secret: str
    max_document_bytes: int = 536_870_912

    def validate(self) -> "ProviderConfig":
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", self.adapter):
            raise ValueError("adapter contains unsupported characters")
        if len(self.request_jwt_secret.encode("utf-8")) < 32:
            raise ValueError("request JWT secret must contain at least 32 UTF-8 bytes")
        if not 0 <= self.port <= 65535:
            raise ValueError("port is invalid")
        if self.max_document_bytes < 1:
            raise ValueError("max document bytes must be positive")
        return self


@dataclass(frozen=True)
class Route:
    operation: str
    raw_path: str
    segments: tuple[str, ...]

    @property
    def document_path(self) -> str:
        return "/".join(self.segments)


class ProviderState:
    def __init__(self, storage_root: Path, adapter: str) -> None:
        self.adapter = adapter
        self.state_root = storage_root / STATE_DIRECTORY
        self.replay_root = self.state_root / "replay"
        self.lock_root = self.state_root / "locks"
        self.staging_root = self.state_root / "staging"
        for directory in (self.replay_root, self.lock_root, self.staging_root):
            directory.mkdir(parents=True, exist_ok=True, mode=0o700)

    @staticmethod
    def _key(value: str) -> str:
        return hashlib.sha256(value.encode("utf-8")).hexdigest()

    def consume_jti(self, request_id: str, expires_at: int) -> None:
        file = self.replay_root / self._key(f"{self.adapter}\0{request_id}")
        for _ in range(2):
            try:
                descriptor = os.open(file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(descriptor, "w", encoding="utf-8") as output:
                    output.write(str(expires_at))
                return
            except FileExistsError:
                try:
                    if int(file.read_text(encoding="utf-8")) <= int(time.time()):
                        file.unlink(missing_ok=True)
                        continue
                except (OSError, ValueError):
                    pass
                raise AuthenticationError() from None
        raise AuthenticationError()

    def staging_file(self) -> Path:
        descriptor, name = tempfile.mkstemp(prefix="upload-", suffix=".stage", dir=self.staging_root)
        os.close(descriptor)
        os.chmod(name, 0o600)
        return Path(name)

    def _lock_file(self, document_path: str) -> Path:
        return self.lock_root / self._key(f"{self.adapter}\0{document_path}")

    def current_lock(self, document_path: str) -> dict[str, Any] | None:
        file = self._lock_file(document_path)
        try:
            value = json.loads(file.read_text(encoding="utf-8"))
            return value if isinstance(value, dict) else None
        except FileNotFoundError:
            return None

    def acquire_lock(self, document_path: str, owner: str) -> None:
        file = self._lock_file(document_path)
        record = json.dumps({
            "documentPath": document_path,
            "owner": owner,
            "createdAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        }).encode("utf-8")
        try:
            descriptor = os.open(file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as output:
                output.write(record)
        except FileExistsError:
            current = self.current_lock(document_path)
            if current is None or current.get("owner") != owner:
                raise ProviderError(409, "The document is locked by another owner") from None

    def release_lock(self, document_path: str, owner: str) -> None:
        current = self.current_lock(document_path)
        if current is None:
            return
        if current.get("owner") != owner:
            raise ProviderError(409, "The lock belongs to another owner")
        self._lock_file(document_path).unlink(missing_ok=True)

    def require_unlocked(self, document_path: str) -> None:
        if self.current_lock(document_path) is not None:
            raise ProviderError(409, "The document is locked")


class StorageProvider:
    def __init__(self, config: ProviderConfig) -> None:
        self.config = config.validate()
        self.config.storage_root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.root = self.config.storage_root.resolve(strict=True)
        self.state = ProviderState(self.root, self.config.adapter)

    @staticmethod
    def _segment(value: str) -> str:
        if (
            not value
            or value in {".", "..", STATE_DIRECTORY}
            or "/" in value
            or "\\" in value
            or any(ord(character) < 0x20 or ord(character) == 0x7F for character in value)
        ):
            raise ProviderError(400, "The document path contains an unsupported segment")
        return value

    def parse_route(self, raw_target: str, method: str) -> Route:
        parsed = urlsplit(raw_target)
        if parsed.query or parsed.fragment:
            raise ProviderError(400, "Query strings and fragments are not supported")
        raw_path = parsed.path
        if not raw_path.startswith(f"{PROTOCOL_PREFIX}/"):
            raise ProviderError(404, "Not found")
        raw_segments = raw_path[len(PROTOCOL_PREFIX) + 1 :].split("/")
        if any(not segment for segment in raw_segments):
            raise ProviderError(400, "Empty path segments are not supported")
        operation = raw_segments.pop()
        if operation not in OPERATIONS:
            raise ProviderError(404, "Unknown storage operation")
        if OPERATIONS[operation] != method:
            raise ProviderError(405, "Method not allowed")
        segments: list[str] = []
        for raw_segment in raw_segments:
            if re.search(r"%(?![0-9A-Fa-f]{2})", raw_segment):
                raise ProviderError(400, "The document path is not valid UTF-8 percent-encoding")
            try:
                decoded = unquote_to_bytes(raw_segment).decode("utf-8", errors="strict")
            except (UnicodeDecodeError, ValueError):
                raise ProviderError(400, "The document path is not valid UTF-8 percent-encoding") from None
            segments.append(self._segment(decoded))
        return Route(operation, raw_path, tuple(segments))

    def safe_path(self, segments: tuple[str, ...], allow_missing_final: bool = False) -> Path:
        current = self.root
        for index, segment in enumerate(segments):
            current = current / self._segment(segment)
            if current.is_symlink():
                raise ProviderError(403, "Symbolic links are not available through this Provider")
            if not current.exists() and not (allow_missing_final and index == len(segments) - 1):
                raise ProviderError(404, "Not found")
        try:
            current.relative_to(self.root)
        except ValueError:
            raise ProviderError(400, "The document path escapes the storage root") from None
        return current

    def directory(self, segments: tuple[str, ...]) -> Path:
        directory = self.root if not segments else self.safe_path(segments)
        if not directory.is_dir():
            raise ProviderError(409, "The parent path is not a directory")
        return directory

    def entry(self, segments: tuple[str, ...]) -> dict[str, Any]:
        file = self.root if not segments else self.safe_path(segments)
        metadata = file.stat(follow_symlinks=False)
        if not (file.is_file() or file.is_dir()):
            raise ProviderError(403, "This storage item type is not supported")
        document_path = "/".join(segments)
        lock = self.state.current_lock(document_path)
        return {
            "path": document_path,
            "name": segments[-1] if segments else self.config.root_name,
            "type": "directory" if file.is_dir() else "file",
            "size": 0 if file.is_dir() else metadata.st_size,
            "readable": os.access(file, os.R_OK),
            "writable": os.access(file, os.W_OK),
            "locked": lock is not None,
            "locker": None if lock is None else lock.get("owner"),
            "createdAt": datetime.fromtimestamp(metadata.st_ctime, timezone.utc).isoformat().replace("+00:00", "Z"),
            "modifiedAt": datetime.fromtimestamp(metadata.st_mtime, timezone.utc).isoformat().replace("+00:00", "Z"),
            "revision": f"{metadata.st_mtime_ns}-{metadata.st_size}",
        }

    def verify(
        self,
        token: str | None,
        adapter_header: str | None,
        method: str,
        raw_path: str,
        content_type: str | None,
        content_length: int,
        content_sha256: str,
    ) -> dict[str, Any]:
        if token is None or len(token.encode("utf-8")) > 5120:
            raise AuthenticationError()
        if not hmac.compare_digest(adapter_header or "", self.config.adapter):
            raise AuthenticationError()
        parts = token.split(".")
        if len(parts) != 3:
            raise AuthenticationError()

        def decode(part: str) -> bytes:
            if not re.fullmatch(r"[A-Za-z0-9_-]+", part):
                raise AuthenticationError()
            padding = "=" * ((4 - len(part) % 4) % 4)
            try:
                return base64.urlsafe_b64decode(part + padding)
            except ValueError:
                raise AuthenticationError() from None

        try:
            header = json.loads(decode(parts[0]))
            claims = json.loads(decode(parts[1]))
        except (json.JSONDecodeError, UnicodeDecodeError, TypeError):
            raise AuthenticationError() from None
        if not isinstance(header, dict) or not isinstance(claims, dict):
            raise AuthenticationError()
        if header.get("alg") != "HS256" or header.get("typ") != "tfo-storage-request+jwt":
            raise AuthenticationError()
        expected = hmac.new(
            self.config.request_jwt_secret.encode("utf-8"),
            f"{parts[0]}.{parts[1]}".encode("ascii"),
            hashlib.sha256,
        ).digest()
        if not hmac.compare_digest(expected, decode(parts[2])):
            raise AuthenticationError()
        request = claims.get("request")
        audience = claims.get("aud")
        if isinstance(audience, str):
            audience = [audience]
        now = int(time.time())
        valid = (
            claims.get("iss") == "thinkfree-office"
            and audience == ["tfo-http-storage-provider"]
            and isinstance(claims.get("iat"), int)
            and isinstance(claims.get("exp"), int)
            and claims["iat"] <= now < claims["exp"]
            and 0 < claims["exp"] - claims["iat"] <= 60
            and isinstance(claims.get("jti"), str)
            and 0 < len(claims["jti"]) <= 64
            and isinstance(request, dict)
            and hmac.compare_digest(str(request.get("adapter", "")), self.config.adapter)
            and hmac.compare_digest(str(request.get("method", "")), method)
            and hmac.compare_digest(str(request.get("path", "")), raw_path)
            and request.get("content_length") == content_length
            and hmac.compare_digest(str(request.get("content_sha256", "")), content_sha256)
            and hmac.compare_digest(str(request.get("content_type", "")), content_type or "")
        )
        if not valid:
            raise AuthenticationError()
        self.state.consume_jti(claims["jti"], claims["exp"])
        return request


class ProviderHttpApplication:
    """FastAPI HTTP boundary around the storage implementation."""

    def __init__(self, config: ProviderConfig) -> None:
        self.provider = StorageProvider(config)

    @staticmethod
    def _content_length(request: Request, required: bool) -> int:
        value = request.headers.get("Content-Length")
        if value is None:
            if required:
                raise ProviderError(411, "Content-Length is required")
            return 0
        if not re.fullmatch(r"0|[1-9][0-9]*", value):
            raise ProviderError(400, "Content-Length must be a non-negative integer")
        return int(value)

    @staticmethod
    def _reject_chunked(request: Request) -> None:
        if request.headers.get("Transfer-Encoding") is not None:
            raise ProviderError(400, "Chunked request bodies are not supported")

    async def _fixed_body(self, request: Request, maximum: int) -> tuple[bytes, str]:
        self._reject_chunked(request)
        length = self._content_length(request, True)
        if length > maximum:
            raise ProviderError(413, "The request body is too large")
        body = await request.body()
        if len(body) != length:
            raise ProviderError(400, "The request body does not match Content-Length")
        return body, hashlib.sha256(body).hexdigest()

    async def _stage_put(self, request: Request) -> tuple[Path, int, str]:
        self._reject_chunked(request)
        length = self._content_length(request, True)
        if length > self.provider.config.max_document_bytes:
            raise ProviderError(413, "The document exceeds the Provider size limit")
        staged = self.provider.state.staging_file()
        digest = hashlib.sha256()
        remaining = length
        try:
            with staged.open("wb") as output:
                async for chunk in request.stream():
                    if not chunk:
                        continue
                    if len(chunk) > remaining:
                        raise ProviderError(400, "The request body does not match Content-Length")
                    output.write(chunk)
                    digest.update(chunk)
                    remaining -= len(chunk)
                if remaining:
                    raise ProviderError(400, "The request body does not match Content-Length")
                output.flush()
                os.fsync(output.fileno())
            return staged, length, digest.hexdigest()
        except Exception:
            staged.unlink(missing_ok=True)
            raise

    @staticmethod
    def _single_string(body: bytes, field: str) -> str:
        try:
            value = json.loads(body)
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise ProviderError(400, "The request body must be valid JSON") from None
        if not isinstance(value, dict) or set(value) != {field} or not isinstance(value[field], str) or not value[field]:
            raise ProviderError(400, f"The request body must contain only a non-empty {field} string")
        return value[field]

    async def handle(self, request: Request) -> Response:
        staged: Path | None = None
        committed = False
        try:
            raw_path = request.scope.get("raw_path", request.url.path.encode("ascii")).decode("ascii")
            raw_target = raw_path if not request.url.query else f"{raw_path}?{request.url.query}"
            route = self.provider.parse_route(raw_target, request.method)
            content_type = request.headers.get("Content-Type")
            body = b""
            if route.operation == "put":
                if content_type != "application/octet-stream":
                    raise ProviderError(415, "PUT requires application/octet-stream")
                staged, length, digest = await self._stage_put(request)
            elif route.operation in {"lock", "unlock", "mkdir", "rename"}:
                if content_type != "application/json":
                    raise ProviderError(415, "This operation requires application/json")
                body, digest = await self._fixed_body(request, 16 * 1024)
                length = len(body)
            else:
                self._reject_chunked(request)
                if self._content_length(request, False) != 0:
                    raise ProviderError(400, "This operation does not accept a request body")
                length, digest = 0, EMPTY_SHA256
            self.provider.verify(
                request.headers.get("X-TFO-Storage-Request-JWT"),
                request.headers.get("X-TFO-Storage-Adapter"),
                request.method,
                route.raw_path,
                content_type,
                length,
                digest,
            )
            response, committed = self._execute(route, body, staged)
            return response
        except ProviderError as error:
            return self._text(error.status, error.message)
        except FileNotFoundError:
            return self._text(404, "Not found")
        except PermissionError:
            return self._text(403, "Storage access was denied")
        except OSError as error:
            if error.errno in {39, 66}:  # ENOTEMPTY on Linux/macOS.
                return self._text(409, "The directory is not empty")
            print(f"Storage request failed: {type(error).__name__}")
            return self._text(500, "Storage request failed")
        except Exception as error:
            print(f"Storage request failed: {type(error).__name__}")
            return self._text(500, "Storage request failed")
        finally:
            if staged is not None and not committed:
                staged.unlink(missing_ok=True)

    def _execute(self, route: Route, body: bytes, staged: Path | None) -> tuple[Response, bool]:
        if route.operation == "info":
            return self._json(200, self.provider.entry(route.segments)), False
        elif route.operation == "list":
            directory = self.provider.directory(route.segments)
            children = sorted(
                (child for child in directory.iterdir() if child.name != STATE_DIRECTORY),
                key=lambda child: child.name,
            )
            if len(children) > 10_000:
                raise ProviderError(413, "The directory contains more than 10000 entries")
            return self._json(200, {
                "entries": [self.provider.entry(route.segments + (child.name,)) for child in children]
            }), False
        elif route.operation == "get":
            file = self.provider.safe_path(route.segments)
            if not file.is_file():
                raise ProviderError(409, "The requested item is not a file")
            return FileResponse(
                file,
                media_type="application/octet-stream",
                headers={"Cache-Control": "no-store"},
            ), False
        elif route.operation == "put":
            if not route.segments or staged is None:
                raise ProviderError(400, "A document path is required")
            self.provider.directory(route.segments[:-1])
            target = self.provider.safe_path(route.segments, allow_missing_final=True)
            if target.is_dir():
                raise ProviderError(409, "A directory already uses this path")
            os.replace(staged, target)
            metadata = target.stat()
            return self._text(200, f"{metadata.st_mtime_ns}-{metadata.st_size}"), True
        elif route.operation == "lock":
            self.provider.entry(route.segments)
            self.provider.state.acquire_lock(route.document_path, self._single_string(body, "owner"))
            return self._empty(204), False
        elif route.operation == "unlock":
            self.provider.state.release_lock(route.document_path, self._single_string(body, "owner"))
            return self._empty(204), False
        elif route.operation == "mkdir":
            name = self.provider._segment(self._single_string(body, "name"))
            if len(name) > 255:
                raise ProviderError(400, "The name is too long")
            target = self.provider.directory(route.segments) / name
            try:
                target.mkdir()
            except FileExistsError:
                raise ProviderError(409, "An item already uses this name") from None
            return self._empty(204), False
        elif route.operation == "rename":
            if not route.segments:
                raise ProviderError(400, "The storage root cannot be renamed")
            self.provider.state.require_unlocked(route.document_path)
            name = self.provider._segment(self._single_string(body, "name"))
            source = self.provider.safe_path(route.segments)
            target = source.parent / name
            if target.exists():
                raise ProviderError(409, "An item already uses this name")
            source.rename(target)
            return self._empty(204), False
        elif route.operation == "delete":
            if not route.segments:
                raise ProviderError(400, "The storage root cannot be deleted")
            self.provider.state.require_unlocked(route.document_path)
            target = self.provider.safe_path(route.segments)
            target.rmdir() if target.is_dir() else target.unlink()
            return self._empty(204), False
        raise ProviderError(404, "Unknown storage operation")

    @staticmethod
    def _json(status: int, value: Any) -> Response:
        return JSONResponse(value, status_code=status, headers={"Cache-Control": "no-store"})

    @staticmethod
    def _text(status: int, value: str) -> Response:
        return PlainTextResponse(value, status_code=status, headers={"Cache-Control": "no-store"})

    @staticmethod
    def _empty(status: int) -> Response:
        return Response(status_code=status, headers={"Cache-Control": "no-store"})


def create_app(config: ProviderConfig) -> FastAPI:
    """Create a complete FastAPI Provider around the local-directory example."""
    http_application = ProviderHttpApplication(config)
    app = FastAPI(
        title="Thinkfree HTTP Storage Provider",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.storage_provider = http_application.provider

    @app.get("/healthz", include_in_schema=False)
    async def health() -> Response:
        return PlainTextResponse("ok\n", headers={"Cache-Control": "no-store"})

    # Catch all methods and paths so verification sees the original raw path,
    # including percent-encoding, before storage routing is performed.
    @app.api_route(
        "/{provider_path:path}",
        methods=["GET", "POST", "PUT", "DELETE"],
        include_in_schema=False,
    )
    async def protocol_request(request: Request, provider_path: str) -> Response:
        del provider_path
        return await http_application.handle(request)

    return app
