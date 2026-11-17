from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.controllers.health_controller import router as health_router
from app.controllers.inference_controller import router as inference_router
from app.core.body_limit import InferenceBodyLimit

app = FastAPI(
    title="TUTIPAL Internal Inference API",
    version="0.0.1",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)
app.add_middleware(InferenceBodyLimit)
app.include_router(health_router, prefix="/internal/ai")
app.include_router(health_router)
app.include_router(inference_router)


@app.exception_handler(RequestValidationError)
async def validation_error(_request, error: RequestValidationError) -> JSONResponse:
    # FastAPI 기본 오류 본문에 질문·이미지 원문을 다시 넣지 않는다.
    details = [{"loc": item["loc"], "type": item["type"], "msg": item["msg"]} for item in error.errors()]
    return JSONResponse(status_code=422, content={"detail": details})
