from fastapi import APIRouter, Depends, Request

from app.core.security import internal_only
from app.models.rerank import RerankRequest
from app.services.rerank_service import rerank
from app.views.rerank_view import RerankView

# PT-86: 모델 제공자(Ollama·OpenAI)와 무관한 로컬 ONNX 추론이라 require_supported_provider는 걸지 않는다.
router = APIRouter(prefix="/v1", dependencies=[Depends(internal_only)])


@router.post("/rerank", response_model=RerankView)
async def rerank_candidates(request: RerankRequest, http: Request) -> RerankView:
    # 기다리는 동안 Spring이 끊었으면(2s timeout) 추론을 건너뛴다.
    return await rerank(request, http.is_disconnected)
