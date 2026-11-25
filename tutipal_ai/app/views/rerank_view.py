from pydantic import BaseModel


class RerankView(BaseModel):
    """scores는 요청 documents와 같은 순서·같은 길이. 높을수록 관련 (값의 범위는 모델마다 다름)."""

    model: str
    scores: list[float]
