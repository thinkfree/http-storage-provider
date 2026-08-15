import re
from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Validated Provider configuration loaded with Pydantic Settings."""

    model_config = SettingsConfigDict(env_prefix="TFO_STORAGE_", case_sensitive=False)

    host: str = Field(default="127.0.0.1", min_length=1)
    port: int = Field(default=8080, ge=0, le=65535)
    root: Path = Path("./storage")
    root_name: str = Field(default="Documents", min_length=1)
    adapter: str
    request_jwt_secret: str
    max_document_bytes: int = Field(default=536_870_912, gt=0)

    @field_validator("root")
    @classmethod
    def absolute_root(cls, value: Path) -> Path:
        return value.expanduser().resolve()

    @field_validator("adapter")
    @classmethod
    def valid_adapter(cls, value: str) -> str:
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", value):
            raise ValueError("adapter contains unsupported characters")
        return value

    @field_validator("request_jwt_secret")
    @classmethod
    def strong_secret(cls, value: str) -> str:
        if len(value.encode("utf-8")) < 32:
            raise ValueError("request JWT secret must contain at least 32 UTF-8 bytes")
        return value
