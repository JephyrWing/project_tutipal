import asyncio
import json
import logging
from collections.abc import AsyncIterator

import httpx
from fastapi import HTTPException

from app.clients import ollama_client, openai_client
from app.clients.ollama_client import ModelUnavailable, available_models, model_present
from app.core.settings import load_model_settings
from app.models.inference import ChatRequest, EmbedRequest
from app.views.inference_view import EmbedView

log = logging.getLogger(__name__)


def require_supported_provider() -> None:
    if load_model_settings().provider not in {"auto", "ollama", "openai"}:
        raise HTTPException(status_code=501, detail="supported providers: auto, ollama, openai")


async def _provider(settings, model_name: str) -> str:
    if settings.provider != "auto":
        return settings.provider
    if model_present(await available_models(settings), model_name):
        return "ollama"
    if settings.openai_api_key:
        return "openai"
    raise ModelUnavailable("local model and fallback unavailable")


async def stream_chat(request: ChatRequest) -> AsyncIterator[str]:
    settings = load_model_settings()
    messages = [{"role": "system", "content": request.system}]
    messages.extend(message.model_dump(exclude_none=True) for message in request.messages)
    emitted = False
    try:
        async with asyncio.timeout(300):
            provider = await _provider(settings, settings.name)
            client = ollama_client if provider == "ollama" else openai_client
            try:
                async for delta in client.chat_stream(settings, messages, request.json_mode):
                    emitted = True
                    yield json.dumps({"delta": delta}, ensure_ascii=False) + "\n"
            except (ModelUnavailable, httpx.HTTPError, TimeoutError):
                # 일부 답변을 이미 보낸 뒤에는 다른 모델의 답변을 덧붙이지 않는다.
                if provider != "ollama" or settings.provider != "auto" or not settings.openai_api_key or emitted:
                    raise
                async for delta in openai_client.chat_stream(settings, messages, request.json_mode):
                    emitted = True
                    yield json.dumps({"delta": delta}, ensure_ascii=False) + "\n"
    except (ModelUnavailable, httpx.HTTPError, TimeoutError) as error:
        # 입력·답변·이미지·내부 키는 로그에 남기지 않는다.
        log.warning("chat unavailable: %s", type(error).__name__)
        yield json.dumps({"error": "llm_unavailable"}) + "\n"


async def embed(request: EmbedRequest) -> EmbedView:
    settings = load_model_settings()
    try:
        async with asyncio.timeout(125):
            provider = await _provider(settings, settings.embedding_model)
            if provider == "ollama":
                try:
                    vectors = await ollama_client.embed_texts(settings, request.texts)
                    model = settings.embedding_model
                except (ModelUnavailable, httpx.HTTPError, TimeoutError):
                    if settings.provider != "auto" or not settings.openai_api_key:
                        raise
                    provider = "openai"
                    model, vectors = await openai_client.embed_texts(settings, request.texts)
            else:
                model, vectors = await openai_client.embed_texts(settings, request.texts)
    except (ModelUnavailable, httpx.HTTPError, TimeoutError) as error:
        log.warning("embedding unavailable: %s", type(error).__name__)
        raise HTTPException(status_code=503, detail="embedding unavailable") from error
    return EmbedView(model=f"{provider}:{model}:{settings.embedding_dimensions}", vectors=vectors)
