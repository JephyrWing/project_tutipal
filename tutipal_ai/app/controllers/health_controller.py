from fastapi import APIRouter

from app.services.health_service import get_health
from app.views.health_view import HealthView

router = APIRouter()


@router.get("/health", response_model=HealthView)
async def health() -> HealthView:
    state = await get_health()
    return HealthView(status=state.status, model_ready=state.model_ready, local_chat_ready=state.local_chat_ready,
                      local_embedding_ready=state.local_embedding_ready, fallback_configured=state.fallback_configured)
