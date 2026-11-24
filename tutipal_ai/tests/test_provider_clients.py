import base64
import json

import httpx
import pytest

from app.clients import ollama_client, openai_client
from app.clients.ollama_client import ModelUnavailable
from app.core.settings import ModelSettings, load_model_settings

pytestmark = pytest.mark.anyio


@pytest.fixture
def transport(monkeypatch):
    original = httpx.AsyncClient
    requests = []
    def install(response):
        def handler(request):
            requests.append(request)
            return response(request) if callable(response) else response
        mock = httpx.MockTransport(handler)
        monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: original(**{**kwargs, "transport": mock}))
        return requests
    return install


@pytest.mark.parametrize("provider", [ollama_client, openai_client], ids=["ollama", "openai"])
async def test_embedding_validates_and_preserves_input_order(transport, provider):
    vectors = [[0.1] * 1024, [0.2] * 1024]
    payload = {"embeddings": vectors} if provider is ollama_client else {
        "model": "actual-model", "data": [{"index": 1, "embedding": vectors[1]}, {"index": 0, "embedding": vectors[0]}]}
    requests = transport(httpx.Response(200, json=payload))
    result = await provider.embed_texts(ModelSettings(openai_api_key="test-only"), ["a", "b"])
    assert (result if provider is ollama_client else result[1]) == vectors
    sent = json.loads(requests[0].content)
    assert sent["input"] == ["a", "b"]
    if provider is openai_client:
        assert result[0] == "actual-model"
        assert sent["dimensions"] == 1024


@pytest.mark.parametrize("provider", [ollama_client, openai_client], ids=["ollama", "openai"])
@pytest.mark.parametrize("status,temporary", [(403, False), (503, False), (504, True)])
async def test_embedding_provider_failure_classification(transport, provider, status, temporary):
    transport(httpx.Response(status))
    with pytest.raises(ModelUnavailable) as error:
        await provider.embed_texts(ModelSettings(openai_api_key="test-only"), ["x"])
    assert ollama_client.confirmed_embedding_outage(error.value) is temporary


@pytest.mark.parametrize("provider", [ollama_client, openai_client], ids=["ollama", "openai"])
@pytest.mark.parametrize("bad", ["count", "dimension", "bool", "nan", "string"])
async def test_embedding_rejects_invalid_vectors(transport, provider, bad):
    vector = [0.0] * (1023 if bad == "dimension" else 1024)
    if bad in {"bool", "nan", "string"}:
        vector[0] = {"bool": True, "nan": float("nan"), "string": "0.0"}[bad]
    vectors = [] if bad == "count" else [vector]
    payload = {"embeddings": vectors} if provider is ollama_client else {
        "data": [{"index": i, "embedding": v} for i, v in enumerate(vectors)]}
    transport(httpx.Response(200, content=json.dumps(payload).encode()))
    with pytest.raises(ModelUnavailable):
        await provider.embed_texts(ModelSettings(openai_api_key="test-only"), ["x"])


async def test_openai_duplicate_vector_indices_rejected(transport):
    transport(httpx.Response(200, json={"data": [{"index": 0, "embedding": [0] * 1024}] * 2}))
    with pytest.raises(ModelUnavailable):
        await openai_client.embed_texts(ModelSettings(openai_api_key="test-only"), ["a", "b"])


@pytest.mark.parametrize("provider", [ollama_client, openai_client], ids=["ollama", "openai"])
async def test_stream_normalization_and_json_image_payload(transport, provider):
    if provider is ollama_client:
        wire = json.dumps({"message": {"content": "안녕"}, "done": True}) + "\n"
    else:
        wire = 'data: ' + json.dumps({"choices": [{"delta": {"content": "안녕"}, "finish_reason": "stop"}]}) + '\n\ndata: [DONE]\n\n'
    requests = transport(httpx.Response(200, content=wire.encode()))
    image = base64.b64encode(b"\x89PNG\r\n\x1a\n").decode()
    messages = [{"role": "system", "content": "rules"}, {"role": "user", "content": "x", "images": [image]}]
    result = [chunk async for chunk in provider.chat_stream(ModelSettings(openai_api_key="test-only"), messages, True)]
    assert result == ["안녕"]
    payload = json.loads(requests[0].content)
    if provider is openai_client:
        assert payload["store"] is False
        assert payload["response_format"] == {"type": "json_object"}
        assert payload["messages"][0]["role"] == "developer"
        assert payload["messages"][1]["content"][1]["image_url"]["url"].startswith("data:image/png;base64,")
    else:
        assert payload["format"] == "json"
        assert payload["messages"][1]["images"] == [image]


@pytest.mark.parametrize("provider,wire", [
    (ollama_client, 'not-json\n'), (ollama_client, '{"message":{"content":4}}\n'),
    (ollama_client, '{"message":{"content":"partial"}}\n'),
    (openai_client, 'data: [DONE]\n\n'),
    (openai_client, 'data: {"choices":[{"delta":{},"finish_reason":"length"}]}\n\n'),
    (openai_client, 'data: {"choices":[{"delta":{"refusal":"no"}}]}\n\n'),
    (openai_client, 'data: broken\n\n'),
])
async def test_malformed_or_incomplete_stream_rejected(transport, provider, wire):
    transport(httpx.Response(200, content=wire.encode()))
    with pytest.raises(ModelUnavailable):
        _ = [part async for part in provider.chat_stream(ModelSettings(openai_api_key="test-only"), [], False)]


@pytest.mark.parametrize("provider", [ollama_client, openai_client], ids=["ollama", "openai"])
async def test_provider_http_error_rejected(transport, provider):
    transport(httpx.Response(503, text="PRIVATE-ERROR"))
    with pytest.raises(ModelUnavailable):
        _ = [part async for part in provider.chat_stream(ModelSettings(openai_api_key="test-only"), [], False)]


@pytest.mark.parametrize("url", ["http://external.invalid/v1", "https://user:pass@api.invalid/v1", "https://api.invalid/v1?key=private"])
async def test_unsafe_openai_urls_rejected_before_http(transport, url):
    requests = transport(httpx.Response(200, json={}))
    with pytest.raises(ModelUnavailable):
        await openai_client.embed_texts(ModelSettings(openai_api_key="test-only", openai_base_url=url), ["x"])
    assert requests == []


async def test_dimension_setting_must_match_spring(monkeypatch):
    monkeypatch.setenv("EMBEDDING_DIMENSIONS", "768")
    with pytest.raises(ValueError, match="vector\\(1024\\)"):
        load_model_settings()


async def test_model_inventory_failure_is_not_cached(transport):
    calls = []
    def respond(request):
        calls.append(True)
        if len(calls) == 1:
            raise httpx.ConnectError("unavailable")
        return httpx.Response(200, json={"models": [{"name": "bge-m3:latest"}]})
    transport(respond)
    settings = ModelSettings()
    assert await ollama_client.available_models(settings) == set()
    assert ollama_client.model_present(await ollama_client.available_models(settings), "bge-m3")
    await ollama_client.available_models(settings)
    assert len(calls) == 2
