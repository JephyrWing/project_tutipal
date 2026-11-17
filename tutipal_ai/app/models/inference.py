import base64
import binascii
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_validator, model_validator


class Message(BaseModel):
    model_config = ConfigDict(extra="forbid")

    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=8000)
    images: list[str] | None = Field(default=None, max_length=1)

    @field_validator("images")
    @classmethod
    def validate_images(cls, images: list[str] | None) -> list[str] | None:
        for image in images or []:
            if len(image) > 3_000_000:
                raise ValueError("image too large")
            try:
                raw = base64.b64decode(image, validate=True)
            except (ValueError, binascii.Error) as error:
                raise ValueError("image must be base64 JPEG or PNG") from error
            if not (raw.startswith(b"\xff\xd8\xff") or raw.startswith(b"\x89PNG\r\n\x1a\n")):
                raise ValueError("image must be JPEG or PNG")
        return images


class ChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    system: str = Field(max_length=12000)
    messages: list[Message] = Field(min_length=1, max_length=40)
    json_mode: bool = False

    @model_validator(mode="after")
    def validate_total_size(self) -> "ChatRequest":
        if sum(len(message.images or []) for message in self.messages) > 1:
            raise ValueError("at most one image per request")
        if sum(len(message.content) for message in self.messages) + len(self.system) > 256_000:
            raise ValueError("chat context too large")
        return self


class EmbedRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    texts: list[Annotated[str, StringConstraints(min_length=1, max_length=4000)]] = Field(min_length=1, max_length=64)
