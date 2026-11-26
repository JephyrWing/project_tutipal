from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.controllers.agent_controller import router as agent_router
from app.controllers.focus_controller import router as focus_router
from app.controllers.health_controller import router as health_router
from app.controllers.inference_controller import router as inference_router
from app.controllers.rerank_controller import router as rerank_router
from app.core.body_limit import InferenceBodyLimit
from app.core.settings import require_tracing_disabled
from app.services import focus_service, rerank_service


@asynccontextmanager
async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
    # LangSmith 추적이 켜져 있으면 요청 본문이 외부로 나갈 수 있으므로 시작하지 않는다 (PT-85).
    require_tracing_disabled()
    # PT-86: 재순위화 모델을 시작할 때 한 번 읽는다. 없거나 꺼져 있어도 앱은 뜬다 (/v1/rerank만 503).
    await rerank_service.startup()
    # PT-90: 집중도 모델(LightGBM 텍스트)을 해시 확인 뒤 한 번 읽는다. 없거나 검사에 실패해도 앱은 뜬다 (/v1/focus/predict만 503).
    await focus_service.startup()
    try:
        yield
    finally:
        focus_service.shutdown()
        rerank_service.shutdown()


app = FastAPI(
    title="TUTIPAL Internal Inference API",
    version="0.0.1",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
    lifespan=lifespan,
)
app.add_middleware(InferenceBodyLimit)
app.include_router(health_router, prefix="/internal/ai")
app.include_router(health_router)
app.include_router(inference_router)
app.include_router(rerank_router)
app.include_router(agent_router)
app.include_router(focus_router)


# Pydantic messages for these types are built from field limits or our own fixed ValueError texts,
# never from the client's value. Any other type (e.g. union_tag_invalid, which quotes the tag the
# client sent) gets INVALID_VALUE_MSG instead (PT-85 Round 1 P5).
SAFE_MSG_TYPES = frozenset({
    "missing", "extra_forbidden", "value_error", "literal_error", "int_type", "int_parsing", "int_from_float",
    "bool_type", "bool_parsing", "string_type", "string_too_short", "string_too_long", "string_pattern_mismatch",
    "string_unicode", "too_short", "too_long", "greater_than", "greater_than_equal", "less_than",
    "less_than_equal", "dict_type", "list_type", "model_type", "model_attributes_type", "json_invalid",
    "json_type",
})
INVALID_VALUE_MSG = "Invalid value"


def safe_error(item: dict) -> dict:
    """One 422 detail entry without client text: no input, no ctx, no client-chosen key names."""
    loc = list(item["loc"])
    if item["type"] == "extra_forbidden" and loc:
        loc = loc[:-1]  # the last element is the unknown key exactly as the client spelled it
    msg = item["msg"] if item["type"] in SAFE_MSG_TYPES else INVALID_VALUE_MSG
    return {"loc": loc, "type": item["type"], "msg": msg}


@app.exception_handler(RequestValidationError)
async def validation_error(_request, error: RequestValidationError) -> JSONResponse:
    # FastAPI 기본 오류 본문에 질문·이미지 원문을 다시 넣지 않는다.
    return JSONResponse(status_code=422, content={"detail": [safe_error(item) for item in error.errors()]})
