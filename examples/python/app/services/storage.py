import os
from datetime import datetime, timezone
from pathlib import Path

from ..config import Settings
from ..exceptions import StorageError
from ..models import StorageEntry, StorageListResponse
from ..state import STATE_DIRECTORY, LocalStateStore


class LocalDirectoryStorageService:
    """
    Local storage implementation that makes the FastAPI example runnable.

    A production Provider can replace this service while retaining the API,
    security, and request-body layers.
    """

    def __init__(self, settings: Settings, state_store: LocalStateStore) -> None:
        self.settings = settings
        self.state_store = state_store
        settings.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.root = settings.root.resolve(strict=True)

    def info(self, segments: tuple[str, ...]) -> StorageEntry:
        item = self.root if not segments else self._safe_path(segments)
        metadata = item.stat(follow_symlinks=False)
        if not (item.is_file() or item.is_dir()):
            raise StorageError(403, "This storage item type is not supported")
        document_path = "/".join(segments)
        lock = self.state_store.current_lock(document_path)
        return StorageEntry(
            path=document_path,
            name=segments[-1] if segments else self.settings.root_name,
            type="directory" if item.is_dir() else "file",
            size=0 if item.is_dir() else metadata.st_size,
            readable=os.access(item, os.R_OK),
            writable=os.access(item, os.W_OK),
            locked=lock is not None,
            locker=None if lock is None else lock.get("owner"),
            createdAt=self._timestamp(metadata.st_ctime),
            modifiedAt=self._timestamp(metadata.st_mtime),
            revision=f"{metadata.st_mtime_ns}-{metadata.st_size}",
        )

    def list(self, segments: tuple[str, ...]) -> StorageListResponse:
        directory = self._directory(segments)
        children = sorted(
            (child for child in directory.iterdir() if child.name != STATE_DIRECTORY),
            key=lambda child: child.name,
        )
        if len(children) > 10_000:
            raise StorageError(413, "The directory contains more than 10000 entries")
        return StorageListResponse(
            entries=[self.info(segments + (child.name,)) for child in children]
        )

    def download(self, segments: tuple[str, ...]) -> Path:
        file = self._safe_path(segments)
        if not file.is_file():
            raise StorageError(409, "The requested item is not a file")
        return file

    def save(self, segments: tuple[str, ...], staged_file: Path) -> str:
        if not segments:
            raise StorageError(400, "A document path is required")
        self._directory(segments[:-1])
        target = self._safe_path(segments, allow_missing_final=True)
        if target.is_dir():
            raise StorageError(409, "A directory already uses this path")
        os.replace(staged_file, target)
        metadata = target.stat()
        return f"{metadata.st_mtime_ns}-{metadata.st_size}"

    def lock(self, segments: tuple[str, ...], owner: str) -> None:
        self.info(segments)
        self.state_store.lock("/".join(segments), owner)

    def unlock(self, segments: tuple[str, ...], owner: str) -> None:
        self.state_store.unlock("/".join(segments), owner)

    def create_directory(self, segments: tuple[str, ...], name: str) -> None:
        target = self._directory(segments) / self.require_child_name(name)
        try:
            target.mkdir()
        except FileExistsError:
            raise StorageError(409, "An item already uses this name") from None

    def rename(self, segments: tuple[str, ...], name: str) -> None:
        if not segments:
            raise StorageError(400, "The storage root cannot be renamed")
        self.state_store.require_unlocked("/".join(segments))
        source = self._safe_path(segments)
        target = source.parent / self.require_child_name(name)
        if target.exists():
            raise StorageError(409, "An item already uses this name")
        source.rename(target)

    def delete(self, segments: tuple[str, ...]) -> None:
        if not segments:
            raise StorageError(400, "The storage root cannot be deleted")
        self.state_store.require_unlocked("/".join(segments))
        target = self._safe_path(segments)
        target.rmdir() if target.is_dir() else target.unlink()

    def _directory(self, segments: tuple[str, ...]) -> Path:
        directory = self.root if not segments else self._safe_path(segments)
        if not directory.is_dir():
            raise StorageError(409, "The parent path is not a directory")
        return directory

    def _safe_path(
        self, segments: tuple[str, ...], allow_missing_final: bool = False
    ) -> Path:
        current = self.root
        for index, segment in enumerate(segments):
            current = current / self.require_segment(segment)
            if current.is_symlink():
                raise StorageError(
                    403, "Symbolic links are not available through this Provider"
                )
            if not current.exists() and not (
                allow_missing_final and index == len(segments) - 1
            ):
                raise FileNotFoundError(current)
        try:
            current.relative_to(self.root)
        except ValueError:
            raise StorageError(
                400, "The document path escapes the storage root"
            ) from None
        return current

    @staticmethod
    def require_segment(value: str) -> str:
        if (
            not value
            or value in {".", "..", STATE_DIRECTORY}
            or "/" in value
            or "\\" in value
            or any(
                ord(character) < 0x20 or ord(character) == 0x7F for character in value
            )
        ):
            raise StorageError(400, "The document path contains an unsupported segment")
        return value

    @classmethod
    def require_child_name(cls, value: str) -> str:
        if len(value) > 255:
            raise StorageError(400, "The name is too long")
        return cls.require_segment(value)

    @staticmethod
    def _timestamp(value: float) -> str:
        return (
            datetime.fromtimestamp(value, timezone.utc)
            .isoformat()
            .replace("+00:00", "Z")
        )
