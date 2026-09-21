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
        method: str,
        raw_path: str,
        content_type: str | None,
        content_length: int,
        content_sha256: str,
    ) -> dict[str, Any]:
        """Return the signed request only after verification and replay consumption."""
        if token is None or len(token.encode("utf-8")) > 8192:
            raise RequestAuthenticationError()
        parts = token.split(".")
        if len(parts) != 3:
            raise RequestAuthenticationError()

        try:
            header = json.loads(self._decode(parts[0]))
            claims = json.loads(self._decode(parts[1]))
        except (json.JSONDecodeError, UnicodeDecodeError, TypeError, RecursionError):
            raise RequestAuthenticationError() from None
        if not isinstance(header, dict) or not isinstance(claims, dict):
            raise RequestAuthenticationError()
        # Unverified input selects only the existing configured adapter key.
        candidate = claims.get("request")
        if not isinstance(candidate, dict) or not self._equals(
            candidate, "adapter", self.settings.adapter
        ):
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
            and type(claims.get("iat")) is int
            and type(claims.get("exp")) is int
            and claims["iat"] <= now < claims["exp"]
            and 0 < claims["exp"] - claims["iat"] <= 60
            and isinstance(claims.get("jti"), str)
            and 0 < len(claims["jti"]) <= 64
            and isinstance(signed_request, dict)
            and self._equals(signed_request, "adapter", self.settings.adapter)
            and self._equals(signed_request, "method", method)
            and self._equals(signed_request, "path", raw_path)
            and type(signed_request.get("content_length")) is int
            and signed_request.get("content_length") == content_length
            and self._equals(signed_request, "content_sha256", content_sha256)
            and self._equals(
                {"content_type": signed_request.get("content_type", "")},
                "content_type",
                content_type or "",
            )
        )
        if not valid:
            raise RequestAuthenticationError()
        if "client_metadata" in signed_request:
            self._validate_metadata(signed_request["client_metadata"])
        self.state_store.consume_request_id(claims["jti"], claims["exp"])
        # Verified transit integrity does not confer customer authorization.
        return signed_request

    @staticmethod
    def _validate_metadata(metadata: Any) -> None:
        # TFO caps /open JSON input at 4096 bytes, not its JWT reserialization.
        # Incoming JWT is capped at 8192 bytes above; root depth 0, max depth 8.
        # Match Java String.length(): values 512, nonblank keys 64 UTF-16 units.
        try:
            if not isinstance(metadata, dict):
                raise RequestAuthenticationError()

            def visit(value: Any, depth: int) -> None:
                if depth > 8 or (
                    isinstance(value, str)
                    and len(value.encode("utf-16-le", errors="surrogatepass")) // 2
                    > 512
                ):
                    raise RequestAuthenticationError()
                if isinstance(value, dict):
                    for key, child in value.items():
                        # Java String.isBlank(), not Python's broader str.isspace().
                        if len(
                            key.encode("utf-16-le", errors="surrogatepass")
                        ) // 2 > 64 or re.fullmatch(
                            r"[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]*",
                            key,
                        ):
                            raise RequestAuthenticationError()
                        visit(child, depth + 1)
                elif isinstance(value, list):
                    for child in value:
                        visit(child, depth + 1)

            visit(metadata, 0)
        except (ValueError, UnicodeError, RecursionError):
            raise RequestAuthenticationError() from None

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
        actual = value.get(field)
        try:
            return isinstance(actual, str) and hmac.compare_digest(
                actual.encode("utf-8"), expected.encode("utf-8")
            )
        except UnicodeError:
            return False
