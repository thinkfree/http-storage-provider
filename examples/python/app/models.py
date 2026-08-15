from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


class StorageEntry(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    path: str
    name: str
    type: Literal["file", "directory"]
    size: int = Field(ge=0)
    readable: bool
    writable: bool
    locked: bool
    locker: str | None
    created_at: str = Field(alias="createdAt")
    modified_at: str = Field(alias="modifiedAt")
    revision: str


class StorageListResponse(BaseModel):
    entries: list[StorageEntry]


class LockRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    owner: str

    @field_validator("owner")
    @classmethod
    def non_empty_owner(cls, value: str) -> str:
        if not value or value.isspace():
            raise ValueError("owner must not be empty")
        return value


class NameRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str

    @field_validator("name")
    @classmethod
    def non_empty_name(cls, value: str) -> str:
        if not value or value.isspace():
            raise ValueError("name must not be empty")
        return value
