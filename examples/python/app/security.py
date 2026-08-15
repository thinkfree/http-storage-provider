import base64
import hashlib
import hmac
import json
import re
import time
from typing import Any

from .config import Settings
from .exceptions import RequestAuthenticationError
from .state import LocalStateStore


class RequestJwtVerifier:
    """Verify the signed JWT against the exact incoming HTTP request."""

    def __init__(self, settings: Settings, state_store: LocalStateStore) -> None:
        self.settings = settings
        self.state_store = state_store

    def verify(
        self,
        token: str | None,
        adapter_header: str | None,
        method: str,
        raw_path: str,
        content_type: str | None,
        content_length: int,
        content_sha256: str,
    ) -> None:
        if token is None or len(token.encode("utf-8")) > 5120:
            raise RequestAuthenticationError()
        if not hmac.compare_digest(adapter_header or "", self.settings.adapter):
            raise RequestAuthenticationError()
        parts = token.split(".")
        if len(parts) != 3:
            raise RequestAuthenticationError()

        try:
            header = json.loads(self._decode(parts[0]))
            claims = json.loads(self._decode(parts[1]))
        except (json.JSONDecodeError, UnicodeDecodeError, TypeError):
            raise RequestAuthenticationError() from None
        if not isinstance(header, dict) or not isinstance(claims, dict):
            raise RequestAuthenticationError()
        if (
            header.get("alg") != "HS256"
            or header.get("typ") != "tfo-storage-request+jwt"
        ):
            raise RequestAuthenticationError()

        expected = hmac.new(
            self.settings.request_jwt_secret.encode("utf-8"),
            f"{parts[0]}.{parts[1]}".encode("ascii"),
            hashlib.sha256,
        ).digest()
        if not hmac.compare_digest(expected, self._decode(parts[2])):
            raise RequestAuthenticationError()

        signed_request = claims.get("request")
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
            and isinstance(signed_request, dict)
            and self._equals(signed_request, "adapter", self.settings.adapter)
            and self._equals(signed_request, "method", method)
            and self._equals(signed_request, "path", raw_path)
            and signed_request.get("content_length") == content_length
            and self._equals(signed_request, "content_sha256", content_sha256)
            and hmac.compare_digest(
                str(signed_request.get("content_type", "")), content_type or ""
            )
        )
        if not valid:
            raise RequestAuthenticationError()
        self.state_store.consume_request_id(claims["jti"], claims["exp"])

    @staticmethod
    def _decode(part: str) -> bytes:
        if not re.fullmatch(r"[A-Za-z0-9_-]+", part):
            raise RequestAuthenticationError()
        padding = "=" * ((4 - len(part) % 4) % 4)
        try:
            return base64.urlsafe_b64decode(part + padding)
        except ValueError:
            raise RequestAuthenticationError() from None

    @staticmethod
    def _equals(value: dict[str, Any], field: str, expected: str) -> bool:
        return hmac.compare_digest(str(value.get(field, "")), expected)
