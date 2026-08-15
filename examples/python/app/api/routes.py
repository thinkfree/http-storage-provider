from __future__ import annotations

import hashlib
import os
import re
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import TypeVar
from urllib.parse import unquote_to_bytes, urlsplit

from fastapi import APIRouter, Request
from fastapi.responses import FileResponse, JSONResponse, PlainTextResponse, Response
from pydantic import BaseModel, ValidationError

from ..config import Settings
from ..exceptions import StorageError
from ..models import LockRequest, NameRequest
from ..services.storage import LocalDirectoryStorageService
from ..state import LocalStateStore
from .dependencies import (
    RequestVerifierDependency,
    StateStoreDependency,
    StorageServiceDependency,
)

router = APIRouter()
PROTOCOL_PREFIX = "/tfo-storage/v1"
EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()
NO_STORE = {"Cache-Control": "no-store"}


class BodyKind(Enum):
    NONE = "none"
    JSON = "json"
    DOCUMENT = "document"


class Operation(Enum):
    INFO = ("info", "GET", BodyKind.NONE)
    LIST = ("list", "GET", BodyKind.NONE)
    GET = ("get", "GET", BodyKind.NONE)
    PUT = ("put", "PUT", BodyKind.DOCUMENT)
    LOCK = ("lock", "POST", BodyKind.JSON)
    UNLOCK = ("unlock", "POST", BodyKind.JSON)
    MKDIR = ("mkdir", "POST", BodyKind.JSON)
    RENAME = ("rename", "POST", BodyKind.JSON)
    DELETE = ("delete", "DELETE", BodyKind.NONE)

    def __init__(self, path_token: str, method: str, body_kind: BodyKind) -> None:
        self.path_token = path_token
        self.method = method
        self.body_kind = body_kind

    @classmethod
    def from_path_token(cls, value: str) -> Operation | None:
        return next(
            (operation for operation in cls if operation.path_token == value), None
        )


@dataclass(frozen=True)
class StorageRoute:
    operation: Operation
    raw_path: str
    segments: tuple[str, ...]


@dataclass
class RequestBody:
    length: int
    sha256: str
    content: bytes | None = None
    staging_file: Path | None = None
    committed: bool = False

    def close(self) -> None:
        if self.staging_file is not None and not self.committed:
            self.staging_file.unlink(missing_ok=True)


@router.get("/healthz", include_in_schema=False)
async def health() -> Response:
    return PlainTextResponse("ok\n", headers=NO_STORE)


# A catch-all is deliberate: JWT verification signs the original encoded URI,
# so the application must capture it before operation-specific path conversion.
@router.api_route(
    "/{_provider_path:path}",
    methods=["GET", "POST", "PUT", "DELETE"],
    include_in_schema=False,
)
async def storage_request(
    request: Request,
    _provider_path: str,
    storage: StorageServiceDependency,
    state_store: StateStoreDependency,
    verifier: RequestVerifierDependency,
) -> Response:
    route = parse_route(request, storage)
    body = await read_request_body(
        request, route.operation, storage.settings, state_store
    )
    try:
        verifier.verify(
            request.headers.get("X-TFO-Storage-Request-JWT"),
            request.headers.get("X-TFO-Storage-Adapter"),
            request.method,
            route.raw_path,
            request.headers.get("Content-Type"),
            body.length,
            body.sha256,
        )
        return execute(route, body, storage)
    finally:
        body.close()


def parse_route(
    request: Request, storage: LocalDirectoryStorageService
) -> StorageRoute:
    raw_path = request.scope.get("raw_path", request.url.path.encode("ascii")).decode(
        "ascii"
    )
    raw_target = (
        raw_path if not request.url.query else f"{raw_path}?{request.url.query}"
    )
    parsed = urlsplit(raw_target)
    if parsed.query or parsed.fragment:
        raise StorageError(400, "Query strings and fragments are not supported")
    if not parsed.path.startswith(f"{PROTOCOL_PREFIX}/"):
        raise StorageError(404, "Not found")
    raw_segments = parsed.path[len(PROTOCOL_PREFIX) + 1 :].split("/")
    if any(not segment for segment in raw_segments):
        raise StorageError(400, "Empty path segments are not supported")
    operation = Operation.from_path_token(raw_segments.pop())
    if operation is None:
        raise StorageError(404, "Unknown storage operation")
    if operation.method != request.method:
        raise StorageError(405, "Method not allowed")

    decoded_segments: list[str] = []
    for raw_segment in raw_segments:
        if re.search(r"%(?![0-9A-Fa-f]{2})", raw_segment):
            raise StorageError(
                400, "The document path is not valid UTF-8 percent-encoding"
            )
        try:
            decoded = unquote_to_bytes(raw_segment).decode("utf-8", errors="strict")
        except (UnicodeDecodeError, ValueError):
            raise StorageError(
                400, "The document path is not valid UTF-8 percent-encoding"
            ) from None
        decoded_segments.append(storage.require_segment(decoded))
    return StorageRoute(operation, parsed.path, tuple(decoded_segments))


async def read_request_body(
    request: Request,
    operation: Operation,
    settings: Settings,
    state_store: LocalStateStore,
) -> RequestBody:
    if operation.body_kind is BodyKind.NONE:
        reject_chunked(request)
        if content_length(request, required=False) != 0:
            raise StorageError(400, "This operation does not accept a request body")
        return RequestBody(0, EMPTY_SHA256)
    if operation.body_kind is BodyKind.JSON:
        require_content_type(request, "application/json")
        reject_chunked(request)
        declared = content_length(request, required=True)
        if declared > 16 * 1024:
            raise StorageError(413, "The request body is too large")
        value = await request.body()
        if len(value) != declared:
            raise StorageError(400, "The request body does not match Content-Length")
        return RequestBody(len(value), hashlib.sha256(value).hexdigest(), content=value)

    require_content_type(request, "application/octet-stream")
    reject_chunked(request)
    declared = content_length(request, required=True)
    if declared > settings.max_document_bytes:
        raise StorageError(413, "The document exceeds the Provider size limit")
    staging_file = state_store.create_staging_file()
    digest = hashlib.sha256()
    remaining = declared
    try:
        with staging_file.open("wb") as output:
            async for chunk in request.stream():
                if not chunk:
                    continue
                if len(chunk) > remaining:
                    raise StorageError(
                        400, "The request body does not match Content-Length"
                    )
                output.write(chunk)
                digest.update(chunk)
                remaining -= len(chunk)
            if remaining:
                raise StorageError(
                    400, "The request body does not match Content-Length"
                )
            output.flush()
            os.fsync(output.fileno())
        return RequestBody(declared, digest.hexdigest(), staging_file=staging_file)
    except Exception:
        staging_file.unlink(missing_ok=True)
        raise


def execute(
    route: StorageRoute,
    body: RequestBody,
    storage: LocalDirectoryStorageService,
) -> Response:
    if route.operation is Operation.INFO:
        return model_response(storage.info(route.segments))
    if route.operation is Operation.LIST:
        return model_response(storage.list(route.segments))
    if route.operation is Operation.GET:
        return FileResponse(
            storage.download(route.segments),
            media_type="application/octet-stream",
            headers=NO_STORE,
        )
    if route.operation is Operation.PUT:
        if body.staging_file is None:
            raise StorageError(400, "A document path is required")
        revision = storage.save(route.segments, body.staging_file)
        body.committed = True
        return PlainTextResponse(revision, headers=NO_STORE)
    if route.operation in {Operation.LOCK, Operation.UNLOCK}:
        value = parse_model(LockRequest, body.content, "owner")
        if route.operation is Operation.LOCK:
            storage.lock(route.segments, value.owner)
        else:
            storage.unlock(route.segments, value.owner)
        return Response(status_code=204, headers=NO_STORE)
    if route.operation in {Operation.MKDIR, Operation.RENAME}:
        value = parse_model(NameRequest, body.content, "name")
        if route.operation is Operation.MKDIR:
            storage.create_directory(route.segments, value.name)
        else:
            storage.rename(route.segments, value.name)
        return Response(status_code=204, headers=NO_STORE)
    if route.operation is Operation.DELETE:
        storage.delete(route.segments)
        return Response(status_code=204, headers=NO_STORE)
    raise StorageError(404, "Unknown storage operation")


Model = TypeVar("Model", bound=BaseModel)


def parse_model(model_type: type[Model], body: bytes | None, field: str) -> Model:
    try:
        return model_type.model_validate_json(body or b"")
    except ValidationError:
        raise StorageError(
            400, f"The request body must contain only a non-empty {field} string"
        ) from None


def model_response(value: BaseModel) -> JSONResponse:
    return JSONResponse(value.model_dump(mode="json", by_alias=True), headers=NO_STORE)


def require_content_type(request: Request, expected: str) -> None:
    if request.headers.get("Content-Type") != expected:
        raise StorageError(415, f"This operation requires {expected}")


def reject_chunked(request: Request) -> None:
    if request.headers.get("Transfer-Encoding") is not None:
        raise StorageError(400, "Chunked request bodies are not supported")


def content_length(request: Request, required: bool) -> int:
    value = request.headers.get("Content-Length")
    if value is None:
        if required:
            raise StorageError(411, "Content-Length is required")
        return 0
    if not re.fullmatch(r"0|[1-9][0-9]*", value):
        raise StorageError(400, "Content-Length must be a non-negative integer")
    return int(value)
