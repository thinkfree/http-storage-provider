from typing import Annotated

from fastapi import Depends, Request

from ..config import Settings
from ..security import RequestJwtVerifier
from ..services.storage import LocalDirectoryStorageService
from ..state import LocalStateStore


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


def get_state_store(request: Request) -> LocalStateStore:
    return request.app.state.state_store


def get_storage_service(request: Request) -> LocalDirectoryStorageService:
    return request.app.state.storage_service


def get_request_verifier(request: Request) -> RequestJwtVerifier:
    return request.app.state.request_verifier


SettingsDependency = Annotated[Settings, Depends(get_settings)]
StateStoreDependency = Annotated[LocalStateStore, Depends(get_state_store)]
StorageServiceDependency = Annotated[
    LocalDirectoryStorageService, Depends(get_storage_service)
]
RequestVerifierDependency = Annotated[RequestJwtVerifier, Depends(get_request_verifier)]
