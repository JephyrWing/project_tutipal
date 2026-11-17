from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from app.core.security import internal_only
from app.models.inference import ChatRequest, EmbedRequest
from app.services.inference_service import embed, require_supported_provider, stream_chat
from app.views.inference_view import EmbedView

router = APIRouter(prefix="/v1", dependencies=[Depends(internal_only), Depends(require_supported_provider)])


@router.post("/chat")
async def chat(request: ChatRequest) -> StreamingResponse:
    return StreamingResponse(stream_chat(request), media_type="application/x-ndjson", headers={"Cache-Control": "no-store"})


@router.post("/embed", response_model=EmbedView)
async def embeddings(request: EmbedRequest) -> EmbedView:
    return await embed(request)
