from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from app.core.security import internal_only
from app.models.agent import AgentChatRequest
from app.services.agent import service
from app.services.inference_service import require_supported_provider

router = APIRouter(prefix="/v1/agent", dependencies=[Depends(internal_only), Depends(require_supported_provider)])


@router.post("/chat")
async def agent_chat(request: AgentChatRequest) -> StreamingResponse:
    return StreamingResponse(service.run_agent(request), media_type="application/x-ndjson",
                             headers={"Cache-Control": "no-store"})
