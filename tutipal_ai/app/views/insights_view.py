"""PT-87 clustering response. Positions only (members index the request's vectors); no representatives, no text."""
from typing import Literal

from pydantic import BaseModel, ConfigDict


class ConceptMatchView(BaseModel):
    model_config = ConfigDict(extra="forbid")

    index: int
    similarity: float


class ClusterItemView(BaseModel):
    model_config = ConfigDict(extra="forbid")

    members: list[int]
    concept: ConceptMatchView | None


class ClusterView(BaseModel):
    model_config = ConfigDict(extra="forbid")

    status: Literal["OK", "LOW_QUALITY"]
    algorithm: str
    k: int
    silhouette: float | None
    clusters: list[ClusterItemView]
