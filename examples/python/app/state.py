import hashlib
import json
import os
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .exceptions import RequestAuthenticationError, StorageError

STATE_DIRECTORY = ".tfo-http-storage-state"


class LocalStateStore:
    """Local replay, lock, and staging state for this single-node example."""

    def __init__(self, storage_root: Path, adapter: str) -> None:
        self.adapter = adapter
        state_root = storage_root / STATE_DIRECTORY
        self.replay_root = state_root / "replay"
        self.lock_root = state_root / "locks"
        self.staging_root = state_root / "staging"
        for directory in (self.replay_root, self.lock_root, self.staging_root):
            directory.mkdir(parents=True, exist_ok=True, mode=0o700)

    @staticmethod
    def _key(value: str) -> str:
        return hashlib.sha256(value.encode("utf-8")).hexdigest()

    def consume_request_id(self, request_id: str, expires_at: int) -> None:
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
                raise RequestAuthenticationError() from None
        raise RequestAuthenticationError()

    def create_staging_file(self) -> Path:
        descriptor, name = tempfile.mkstemp(
            prefix="upload-", suffix=".stage", dir=self.staging_root
        )
        os.close(descriptor)
        os.chmod(name, 0o600)
        return Path(name)

    def current_lock(self, document_path: str) -> dict[str, Any] | None:
        file = self._lock_file(document_path)
        try:
            value = json.loads(file.read_text(encoding="utf-8"))
            return value if isinstance(value, dict) else None
        except FileNotFoundError:
            return None

    def lock(self, document_path: str, owner: str) -> None:
        file = self._lock_file(document_path)
        record = json.dumps(
            {
                "documentPath": document_path,
                "owner": owner,
                "createdAt": datetime.now(timezone.utc)
                .isoformat()
                .replace("+00:00", "Z"),
            }
        ).encode("utf-8")
        try:
            descriptor = os.open(file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as output:
                output.write(record)
        except FileExistsError:
            current = self.current_lock(document_path)
            if current is None or current.get("owner") != owner:
                raise StorageError(
                    409, "The document is locked by another owner"
                ) from None

    def unlock(self, document_path: str, owner: str) -> None:
        current = self.current_lock(document_path)
        if current is None:
            return
        if current.get("owner") != owner:
            raise StorageError(409, "The lock belongs to another owner")
        self._lock_file(document_path).unlink(missing_ok=True)

    def require_unlocked(self, document_path: str) -> None:
        if self.current_lock(document_path) is not None:
            raise StorageError(409, "The document is locked")

    def _lock_file(self, document_path: str) -> Path:
        return self.lock_root / self._key(f"{self.adapter}\0{document_path}")
