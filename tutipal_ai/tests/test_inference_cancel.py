"""Exercise ASGI disconnect while the real adapter is waiting for its next chunk."""
import asyncio
import json

import httpx
import pytest

from app.main import app


@pytest.mark.anyio
@pytest.mark.parametrize("provider", ["ollama", "openai"])
@pytest.mark.parametrize("after_first", [False, True], ids=["before-first-token", "after-first-token"])
async def test_disconnect_closes_provider_stream(monkeypatch, chat_body, provider, after_first):
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
    await queue.put({"type": "http.request", "body": json.dumps(chat_body).encode(), "more_body": False})
    sent = []
    async def send(message):
        sent.append(message)
        if after_first and message["type"] == "http.response.body" and message.get("body"):
            await queue.put({"type": "http.disconnect"})
    scope = {"type": "http", "asgi": {"version": "3.0", "spec_version": "2.3"},
             "http_version": "1.1", "method": "POST", "scheme": "http", "path": "/v1/chat",
             "raw_path": b"/v1/chat", "query_string": b"", "root_path": "",
             "headers": [(b"content-type", b"application/json"), (b"x-internal-key", b"pt26-test-key")],
             "client": ("127.0.0.1", 1), "server": ("test", 80)}
    async with asyncio.timeout(2):
        task = asyncio.create_task(app(scope, queue.get, send))
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
    assert (b'"delta": "one"' in bodies) is after_first
