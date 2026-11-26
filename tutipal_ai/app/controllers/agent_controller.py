from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse

from app.core.security import internal_only
from app.models.agent import AgentChatRequest
from app.models.summary import SummarizeRequest, SummarizeResponse
from app.services import summary_service
from app.services.agent import service
from app.services.inference_service import require_supported_provider

router = APIRouter(prefix="/v1/agent", dependencies=[Depends(internal_only), Depends(require_supported_provider)])


@router.post("/chat")
async def agent_chat(request: AgentChatRequest) -> StreamingResponse:
    return StreamingResponse(service.run_agent(request), media_type="application/x-ndjson",
                             headers={"Cache-Control": "no-store"})


@router.post("/summarize")
async def agent_summarize(request: SummarizeRequest) -> SummarizeResponse:
    """PT-106 contract 6.5: summary of stored conversation messages, called by Spring outside the chat turn."""
    try:
        return SummarizeResponse(summary=await summary_service.summarize(request))
    except summary_service.EmptySummary as error:
        raise HTTPException(status_code=502, detail="empty_summary") from error
    except summary_service.SummaryUnavailable as error:
        raise HTTPException(status_code=503, detail="llm_unavailable") from error
