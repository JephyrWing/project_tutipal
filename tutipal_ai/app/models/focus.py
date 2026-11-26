from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

FocusCategory = Literal["ide", "browser", "terminal", "game", "chat", "other"]


class FocusRow(BaseModel):
    """One stored minute. minutes_ago 1 = the latest minute before the target minute."""

    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    minutes_ago: int = Field(ge=1, le=15, strict=True)
    category: FocusCategory
    idle_sec: int = Field(ge=0, le=60, strict=True)
    switch_count: int = Field(ge=0, le=120, strict=True)


class FocusPredictRequest(BaseModel):
    """PT-90: Spring sends only categories and numbers (no exe name, user or session). Contract 8."""

    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    rows: list[FocusRow] = Field(max_length=15)
    hour: float = Field(ge=0, lt=24, strict=True)  # strict: "10.5" and true are 422 like the other fields
    weekday: int = Field(ge=0, le=6, strict=True)

    @field_validator("rows")
    @classmethod
    def _unique_minutes(cls, rows: list[FocusRow]) -> list[FocusRow]:
        if len({r.minutes_ago for r in rows}) != len(rows):
            raise ValueError("minutes_ago must be unique")  # fixed text: no input in the 422 body
        return rows
