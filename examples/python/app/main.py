import errno
import logging

from fastapi import FastAPI, Request
from fastapi.responses import PlainTextResponse

from .api.routes import router
from .config import Settings
from .exceptions import RequestAuthenticationError, StorageError
from .security import RequestJwtVerifier
from .services.storage import LocalDirectoryStorageService
from .state import LocalStateStore

LOGGER = logging.getLogger("http-storage-provider")
NO_STORE = {"Cache-Control": "no-store"}


def create_app(settings: Settings | None = None) -> FastAPI:
    """FastAPI application factory used by Uvicorn and the test suite."""
    actual_settings = settings or Settings()  # type: ignore[call-arg]
    state_store = LocalStateStore(actual_settings.root, actual_settings.adapter)
    storage_service = LocalDirectoryStorageService(actual_settings, state_store)

    application = FastAPI(
        title="Thinkfree HTTP Storage Provider",
        version="1.0.0-preview",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    application.state.settings = actual_settings
    application.state.state_store = state_store
    application.state.storage_service = storage_service
    application.state.request_verifier = RequestJwtVerifier(
        actual_settings, state_store
    )
    application.include_router(router)
    register_exception_handlers(application)
    return application


def register_exception_handlers(application: FastAPI) -> None:
    @application.exception_handler(RequestAuthenticationError)
    async def authentication_failure(
        request: Request, exception: RequestAuthenticationError
    ) -> PlainTextResponse:
        del request, exception
        return PlainTextResponse("Unauthorized", status_code=401, headers=NO_STORE)

    @application.exception_handler(StorageError)
    async def storage_failure(
        request: Request, exception: StorageError
    ) -> PlainTextResponse:
        del request
        return PlainTextResponse(
            exception.detail,
            status_code=exception.status_code,
            headers=NO_STORE,
        )

    @application.exception_handler(FileNotFoundError)
    async def not_found(
        request: Request, exception: FileNotFoundError
    ) -> PlainTextResponse:
        del request, exception
        return PlainTextResponse("Not found", status_code=404, headers=NO_STORE)

    @application.exception_handler(PermissionError)
    async def access_denied(
        request: Request, exception: PermissionError
    ) -> PlainTextResponse:
        del request, exception
        return PlainTextResponse(
            "Storage access was denied", status_code=403, headers=NO_STORE
        )

    @application.exception_handler(OSError)
    async def operating_system_failure(
        request: Request, exception: OSError
    ) -> PlainTextResponse:
        del request
        if exception.errno in {errno.ENOTEMPTY, 66}:
            return PlainTextResponse(
                "The directory is not empty", status_code=409, headers=NO_STORE
            )
        LOGGER.error("Storage request failed: %s", type(exception).__name__)
        return PlainTextResponse(
            "Storage request failed", status_code=500, headers=NO_STORE
        )

    @application.exception_handler(Exception)
    async def unexpected_failure(
        request: Request, exception: Exception
    ) -> PlainTextResponse:
        del request
        LOGGER.error("Storage request failed: %s", type(exception).__name__)
        return PlainTextResponse(
            "Storage request failed", status_code=500, headers=NO_STORE
        )
