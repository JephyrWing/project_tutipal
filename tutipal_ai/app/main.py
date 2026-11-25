from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.controllers.health_controller import router as health_router
from app.controllers.inference_controller import router as inference_router
from app.controllers.rerank_controller import router as rerank_router
from app.core.body_limit import InferenceBodyLimit
from app.services import rerank_service


@asynccontextmanager
async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
    # PT-86: 재순위화 모델을 시작할 때 한 번 읽는다. 없거나 꺼져 있어도 앱은 뜬다 (/v1/rerank만 503).
    await rerank_service.startup()
    try:
        yield
    finally:
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


@app.exception_handler(RequestValidationError)
async def validation_error(_request, error: RequestValidationError) -> JSONResponse:
    # FastAPI 기본 오류 본문에 질문·이미지 원문을 다시 넣지 않는다.
    details = [{"loc": item["loc"], "type": item["type"], "msg": item["msg"]} for item in error.errors()]
    return JSONResponse(status_code=422, content={"detail": details})
