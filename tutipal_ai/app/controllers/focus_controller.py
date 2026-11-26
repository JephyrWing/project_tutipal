from fastapi import APIRouter, Depends

from app.core.security import internal_only
from app.models.focus import FocusPredictRequest
from app.services.focus_service import predict
from app.views.focus_view import FocusView

# PT-90: local LightGBM inference, independent of the chat model provider (no require_supported_provider).
router = APIRouter(prefix="/v1", dependencies=[Depends(internal_only)])


@router.post("/focus/predict", response_model=FocusView, response_model_exclude_none=True)
async def predict_focus(request: FocusPredictRequest) -> FocusView:
    return await predict(request)
