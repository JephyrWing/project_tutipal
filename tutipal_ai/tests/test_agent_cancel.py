"""PT-85 AC-16 (FastAPI side): client disconnect on /v1/agent/chat closes the model stream / variant tool."""
import asyncio
import json

import httpx
import pytest

from agent_support import FakeVariant, personal_body, variant_body
from app.main import app
from app.services.agent import service

pytestmark = pytest.mark.anyio


def scope_for(path="/v1/agent/chat"):
    return {"type": "http", "asgi": {"version": "3.0", "spec_version": "2.3"},
            "http_version": "1.1", "method": "POST", "scheme": "http", "path": path,
            "raw_path": path.encode(), "query_string": b"", "root_path": "",
            "headers": [(b"content-type", b"application/json"), (b"x-internal-key", b"pt26-test-key")],
            "client": ("127.0.0.1", 1), "server": ("test", 80)}


@pytest.mark.parametrize("provider", ["ollama", "openai"])
@pytest.mark.parametrize("after_first", [False, True], ids=["before-first-token", "after-first-token"])
async def test_disconnect_closes_provider_stream(monkeypatch, provider, after_first):
    monkeypatch.setenv("MODEL_PROVIDER", provider)
    monkeypatch.setenv("OPENAI_API_KEY", "test-only")
    opened, closed = asyncio.Event(), asyncio.Event()
    queue = asyncio.Queue()

    class PendingStream(httpx.AsyncByteStream):
        async def __aiter__(self):
            opened.set()
            if after_first:
                if provider == "ollama":
                    yield b'{"message":{"content":"one"},"done":false}\n'
                else:
                    yield b'data: {"choices":[{"delta":{"content":"one"}}]}\n\n'
            await asyncio.Event().wait()

        async def aclose(self):
            closed.set()

    original = httpx.AsyncClient
    transport = httpx.MockTransport(lambda request: httpx.Response(200, stream=PendingStream()))
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: original(**{**kwargs, "transport": transport}))
    body = personal_body()
    await queue.put({"type": "http.request", "body": json.dumps(body).encode(), "more_body": False})
    sent = []

    async def send(message):
        sent.append(message)
        if after_first and message["type"] == "http.response.body" and b'"delta"' in message.get("body", b""):
            await queue.put({"type": "http.disconnect"})

    async with asyncio.timeout(3):
        task = asyncio.create_task(app(scope_for(), queue.get, send))
        try:
            await opened.wait()
            if not after_first:
                await queue.put({"type": "http.disconnect"})
            await task
            await closed.wait()
        finally:
            if not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
    bodies = b"".join(m.get("body", b"") for m in sent if m["type"] == "http.response.body")
    assert b"llm_unavailable" not in bodies
    assert b'"meta"' not in bodies
    assert (b'"delta": "one"' in bodies) is after_first


async def test_disconnect_during_variant_cancels_the_tool(monkeypatch):
    monkeypatch.setattr(service, "PING_INTERVAL_SECONDS", 0.05)
    variant = FakeVariant(hang=True)
    monkeypatch.setattr(service, "generate_question_variant", variant)

    async def no_llm(request):
        raise AssertionError("no answer LLM while the variant tool runs")
        yield  # pragma: no cover

    monkeypatch.setattr(service, "stream_chat", no_llm)
    queue = asyncio.Queue()
    await queue.put({"type": "http.request", "body": json.dumps(variant_body()).encode(), "more_body": False})
    sent = []

    async def send(message):
        sent.append(message)

    async with asyncio.timeout(3):
        task = asyncio.create_task(app(scope_for(), queue.get, send))
        try:
            while not variant.originals:
                await asyncio.sleep(0.01)
            await queue.put({"type": "http.disconnect"})
            await task
            await variant.cancelled.wait()
        finally:
            if not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
    bodies = b"".join(m.get("body", b"") for m in sent if m["type"] == "http.response.body")
    assert b'"variant"' not in bodies
    assert b'"meta"' not in bodies


async def test_pings_keep_the_connection_alive_before_output(monkeypatch):
    monkeypatch.setattr(service, "PING_INTERVAL_SECONDS", 0.05)
    monkeypatch.setattr(service, "generate_question_variant", FakeVariant(delay=0.3))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post("/v1/agent/chat", json=variant_body(),
                                     headers={"X-Internal-Key": "pt26-test-key"})
    lines = [json.loads(line) for line in response.text.splitlines() if line.strip()]
    first_variant = next(i for i, line in enumerate(lines) if "variant" in line)
    assert lines[:first_variant] and all(line == {"ping": True} for line in lines[:first_variant])
    assert len(lines[:first_variant]) >= 2
