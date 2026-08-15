class StorageError(Exception):
    """Expected Provider failure rendered as a stable HTTP status and message."""

    def __init__(self, status_code: int, detail: str) -> None:
        super().__init__(detail)
        self.status_code = status_code
        self.detail = detail


class RequestAuthenticationError(StorageError):
    """Authentication failures intentionally do not disclose a reason."""

    def __init__(self) -> None:
        super().__init__(401, "Unauthorized")
