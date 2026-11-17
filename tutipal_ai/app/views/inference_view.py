from pydantic import BaseModel


class EmbedView(BaseModel):
    model: str
    vectors: list[list[float]]
