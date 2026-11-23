import asyncio
import json

import httpx
import pytest
from fastapi import HTTPException

from app.clients.ollama_client import ModelUnavailable
from app.models.inference import ChatRequest, EmbedRequest
from app.services import inference_service as service

pytestmark = pytest.mark.anyio


async def collect(body):
    return [json.loads(line) async for line in service.stream_chat(ChatRequest(**body))]


@pytest.mark.parametrize("installed,key,expected", [(True, True, "local"), (False, True, "remote"),
                                                   (False, False, "error")])
async def test_auto_selection(monkeypatch, chat_body, installed, key, expected):
    monkeypatch.setenv("MODEL_PROVIDER", "auto")
    if key:
        monkeypatch.setenv("OPENAI_API_KEY", "test-only")
    async def models(settings):
        return {settings.name} if installed else set()
    async def local(*args):
        yield "local"
    async def remote(*args):
        yield "remote"
    monkeypatch.setattr(service, "available_models", models)
    monkeypatch.setattr(service.ollama_client, "chat_stream", local)
    monkeypatch.setattr(service.openai_client, "chat_stream", remote)
    assert await collect(chat_body) == ([{"error": "llm_unavailable"}] if expected == "error" else [{"delta": expected}])


@pytest.mark.parametrize("emitted", [False, True])
@pytest.mark.parametrize("failure", [ModelUnavailable, httpx.ReadTimeout, TimeoutError])
async def test_fallback_only_before_first_delta(monkeypatch, chat_body, emitted, failure, caplog):
    monkeypatch.setenv("MODEL_PROVIDER", "auto")
    monkeypatch.setenv("OPENAI_API_KEY", "PRIVATE-KEY")
    async def models(settings):
        return {settings.name}
    async def local(*args):
        if emitted:
            yield "partial"
        raise failure("PRIVATE-QUESTION PRIVATE-KEY")
    calls = []
    async def remote(*args):
        calls.append(True)
        yield "fallback"
    monkeypatch.setattr(service, "available_models", models)
    monkeypatch.setattr(service.ollama_client, "chat_stream", local)
    monkeypatch.setattr(service.openai_client, "chat_stream", remote)
    result = await collect(chat_body)
    assert result == ([{"delta": "partial"}, {"error": "llm_unavailable"}] if emitted else [{"delta": "fallback"}])
    assert bool(calls) is not emitted
    assert "PRIVATE" not in caplog.text


async def test_explicit_local_does_not_fallback(monkeypatch, chat_body):
    monkeypatch.setenv("OPENAI_API_KEY", "test-only")
    async def fail(*args):
        raise ModelUnavailable("no model")
        yield  # async generator
    monkeypatch.setattr(service.ollama_client, "chat_stream", fail)
    assert await collect(chat_body) == [{"error": "llm_unavailable"}]


async def test_fallback_failure_is_sanitized(monkeypatch, chat_body, caplog):
    monkeypatch.setenv("MODEL_PROVIDER", "openai")
    async def fail(*args):
        raise httpx.ConnectError("PRIVATE-KEY")
        yield
    monkeypatch.setattr(service.openai_client, "chat_stream", fail)
    assert await collect(chat_body) == [{"error": "llm_unavailable"}]
    assert "PRIVATE-KEY" not in caplog.text


async def test_embedding_fallback_and_origin(monkeypatch):
    monkeypatch.setenv("MODEL_PROVIDER", "auto")
    monkeypatch.setenv("OPENAI_API_KEY", "test-only")
    async def models(settings):
        return {settings.embedding_model}
    async def fail(*args):
        raise ModelUnavailable("offline")
    async def remote(settings, texts):
        return "actual-model", [[0.25] * 1024 for _ in texts]
    monkeypatch.setattr(service, "available_models", models)
    monkeypatch.setattr(service.ollama_client, "embed_texts", fail)
    monkeypatch.setattr(service.openai_client, "embed_texts", remote)
    result = await service.embed(EmbedRequest(texts=["a", "b"]))
    assert result.model == "openai:actual-model:1024"
    assert result.vectors == [[0.25] * 1024] * 2


async def test_embedding_failure_is_503(monkeypatch, caplog):
    async def fail(*args):
        raise TimeoutError("PRIVATE-INPUT")
    monkeypatch.setattr(service.ollama_client, "embed_texts", fail)
    with pytest.raises(HTTPException) as error:
        await service.embed(EmbedRequest(texts=["x"]))
    assert error.value.status_code == 503
    assert error.value.detail == "embedding unavailable"
    assert "PRIVATE" not in caplog.text


@pytest.mark.parametrize("kind,seconds", [("chat", 300), ("embed", 125)])
async def test_total_deadline_cancels_inflight_work(monkeypatch, chat_body, kind, seconds):
    real_timeout = asyncio.timeout
    deadlines, closed = [], []
    def short_timeout(delay):
        deadlines.append(delay)
        return real_timeout(0.01)
    async def chat(*args):
        try:
            await asyncio.Event().wait()
            yield "never"
        finally:
            closed.append(True)
    async def embed(*args):
        try:
            await asyncio.Event().wait()
        finally:
            closed.append(True)
    monkeypatch.setattr(service.asyncio, "timeout", short_timeout)
    monkeypatch.setattr(service.ollama_client, "chat_stream", chat)
    monkeypatch.setattr(service.ollama_client, "embed_texts", embed)
    if kind == "chat":
        assert await collect(chat_body) == [{"error": "llm_unavailable"}]
    else:
        with pytest.raises(HTTPException):
            await service.embed(EmbedRequest(texts=["x"]))
    assert deadlines == [seconds]
    assert closed == [True]
