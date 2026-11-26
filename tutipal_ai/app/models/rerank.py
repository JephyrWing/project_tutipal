import unicodedata
from typing import Annotated

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, StringConstraints


def _not_blank(value: str) -> str:
    # 공백과 서식 문자(Cf: zero-width space, BOM 등)만 있는 값도 빈 값으로 본다.
    if all(ch.isspace() or unicodedata.category(ch) == "Cf" for ch in value):
        # 메시지에 입력값을 넣지 않는다 (422 본문에 다시 나가지 않도록).
        raise ValueError("must not be blank")
    return value


Query = Annotated[str, StringConstraints(min_length=1, max_length=1000), AfterValidator(_not_blank)]
Document = Annotated[str, StringConstraints(min_length=1, max_length=2000), AfterValidator(_not_blank)]


class RerankRequest(BaseModel):
    """PT-86: Spring이 같은 수업·같은 임베딩 모델 범위에서 고른 후보. docs/AI_WORKFLOW_CONTRACT.md 3.1"""

    model_config = ConfigDict(extra="forbid")

    query: Query
    documents: list[Document] = Field(min_length=1, max_length=32)
