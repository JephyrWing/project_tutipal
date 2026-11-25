"""PT-87 POST /v1/insights/cluster request: question vectors and concept vectors only.

No text, no user id: Spring keeps the mapping from vector position to question / student in its own memory.
"""
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field

DIMENSIONS = 1024
MIN_VECTORS = 10
MAX_VECTORS = 256
MAX_CONCEPTS = 50

FiniteFloat = Annotated[float, Field(allow_inf_nan=False)]
Vector = Annotated[list[FiniteFloat], Field(min_length=DIMENSIONS, max_length=DIMENSIONS)]


class ClusterRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    vectors: list[Vector] = Field(min_length=MIN_VECTORS, max_length=MAX_VECTORS)
    concepts: list[Vector] = Field(max_length=MAX_CONCEPTS)
