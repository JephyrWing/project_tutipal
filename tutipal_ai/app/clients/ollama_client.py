"""모델 서버와의 HTTP 통신만 담당한다. DB나 원본 자료는 접근하지 않는다."""

import asyncio
import errno
import json
import math
import time
from collections.abc import AsyncIterator
from urllib.parse import urlsplit

import httpx

from app.core.settings import ModelSettings

TIMEOUT = httpx.Timeout(connect=5.0, read=180.0, write=10.0, pool=5.0)
_models_cache: dict[str, tuple[float, set[str]]] = {}


class ModelUnavailable(Exception):
    pass


class EmbeddingTemporarilyUnavailable(ModelUnavailable):
    pass


def confirmed_embedding_outage(error: Exception) -> bool:
    if isinstance(error, (EmbeddingTemporarilyUnavailable, httpx.TimeoutException, TimeoutError)):
        return True
    if isinstance(error, httpx.HTTPStatusError):
        return error.response.status_code in {502, 504}
    if isinstance(error, httpx.NetworkError):
        cause = error.__cause__
        while cause is not None:
            if isinstance(cause, OSError) and cause.errno in {
                errno.ECONNREFUSED, errno.ECONNRESET, errno.ECONNABORTED,
            }:
                return True
            cause = cause.__cause__
    return False


def _base_url(settings: ModelSettings) -> str:
    parsed = urlsplit(settings.base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ModelUnavailable("invalid model URL")
    return settings.base_url


async def chat_stream(settings: ModelSettings, messages: list[dict], json_mode: bool) -> AsyncIterator[str]:
    payload = {"model": settings.name, "messages": messages, "stream": True}
    if json_mode:
        payload["format"] = "json"
    output_size = 0
    # 한 번의 모델 호출은 최대 5분. 클라이언트 중단 시 HTTP 스트림도 닫힌다.
    async with asyncio.timeout(300), httpx.AsyncClient(timeout=TIMEOUT, trust_env=False) as client:
        async with client.stream("POST", f"{_base_url(settings)}/api/chat", json=payload) as response:
            if response.status_code != 200:
                raise ModelUnavailable("model rejected request")
            async for line in response.aiter_lines():
                if not line.strip():
                    continue
                if len(line) > 1_000_000:
                    raise ModelUnavailable("model response too large")
                try:
                    event = json.loads(line)
                except (ValueError, TypeError) as error:
                    raise ModelUnavailable("invalid model response") from error
                if not isinstance(event, dict) or event.get("error"):
                    raise ModelUnavailable("invalid model response")
                message = event.get("message") or {}
                if not isinstance(message, dict):
                    raise ModelUnavailable("invalid model message")
                delta = message.get("content") or ""
                if not isinstance(delta, str):
                    raise ModelUnavailable("invalid model delta")
                output_size += len(delta)
                if output_size > 1_000_000:
                    raise ModelUnavailable("model response too large")
                if delta:
                    yield delta
                if event.get("done"):
                    return
            raise ModelUnavailable("model stream ended before completion")


async def embed_texts(settings: ModelSettings, texts: list[str]) -> list[list[float]]:
    async with asyncio.timeout(125), httpx.AsyncClient(timeout=httpx.Timeout(120.0, connect=5.0), trust_env=False) as client:
        async with client.stream("POST", f"{_base_url(settings)}/api/embed", json={"model": settings.embedding_model, "input": texts}) as response:
            if response.status_code != 200:
                if response.status_code in {502, 504}:
                    raise EmbeddingTemporarilyUnavailable("embedding gateway unavailable")
                raise ModelUnavailable("embedding unavailable")
            raw = bytearray()
            async for part in response.aiter_bytes():
                raw.extend(part)
                if len(raw) > 12_000_000:
                    raise ModelUnavailable("embedding response too large")
    try:
        payload = json.loads(raw)
        vectors = payload.get("embeddings") if isinstance(payload, dict) else None
        if not isinstance(vectors, list) or len(vectors) != len(texts):
            raise ValueError("vector count mismatch")
        dimension = len(vectors[0]) if isinstance(vectors[0], list) else 0
        if dimension != settings.embedding_dimensions:
            raise ValueError("invalid vector dimension")
        for vector in vectors:
            if not isinstance(vector, list) or len(vector) != dimension:
                raise ValueError("vector dimension mismatch")
            if any(isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) for value in vector):
                raise ValueError("invalid vector value")
        return vectors
    except (ValueError, TypeError, OverflowError) as error:
        raise ModelUnavailable("invalid embedding response") from error


async def available_models(settings: ModelSettings, *, report_embedding_outage: bool = False) -> set[str]:
    cached = _models_cache.get(settings.base_url)
    if cached and cached[0] > time.monotonic():
        return cached[1]
    try:
        async with httpx.AsyncClient(timeout=1.5, trust_env=False) as client:
            response = await client.get(f"{_base_url(settings)}/api/tags")
            response.raise_for_status()
        payload = response.json()
        models = payload.get("models", []) if isinstance(payload, dict) else []
        names = {model.get("name") for model in models if isinstance(model, dict)}
        names = {name for name in names if isinstance(name, str)}
        # 한 프로세스에서 동일 모델 목록 조회를 짧게 공유한다. 실패는 오래 캐시하지 않는다.
        if len(_models_cache) >= 32:
            _models_cache.clear()
        _models_cache[settings.base_url] = (time.monotonic() + 3, names)
        return names
    except (httpx.HTTPError, ModelUnavailable, ValueError, TypeError) as error:
        if report_embedding_outage and confirmed_embedding_outage(error):
            raise EmbeddingTemporarilyUnavailable("embedding model discovery unavailable") from error
        return set()


def model_present(names: set[str], name: str) -> bool:
    return name in names or f"{name}:latest" in names
