from typing import Literal

from pydantic import BaseModel


class FocusView(BaseModel):
    """ok: state + probabilities (break, distracted, focus; sum 1) + model_version. insufficient_data: all None."""

    status: Literal["ok", "insufficient_data"]
    state: Literal["break", "distracted", "focus"] | None = None
    probabilities: dict[str, float] | None = None
    model_version: str | None = None
