"""PT-106 AC-22: POST /v1/agent/summarize boundary (docs/AI_WORKFLOW_CONTRACT.md 6.5).

Spring calls it outside the chat turn: {scope, messages (1..40, each <= 8000), previousSummary? (<= 4000)}
-> {summary (<= 4000)}. Internal key only, unknown fields rejected, bodies never logged, model always faked.
"""
import copy
import json
import logging

import httpx
import pytest

from agent_support import AUTH, FakeLLM
from app.clients.ollama_client import ModelUnavailable
from app.main import app
from app.services import summary_service

pytestmark = pytest.mark.anyio
PATH = "/v1/agent/summarize"
SECRET = "SECRET-SUMMARY-INPUT-9031"
SESSION = {"kind": "session", "sessionId": 42}
PERSONAL = {"kind": "personal"}


def body(**changes) -> dict:
    payload = {
        "scope": copy.deepcopy(SESSION),
        "messages": [
            {"role": "user", "content": "range(5)는 뭐예요?"},
            {"role": "assistant", "content": "0부터 4까지의 수를 차례로 만들어요."},
        ],
        "previousSummary": "파이썬 반복문을 배우는 중이다.",
    }
    payload.update(copy.deepcopy(changes))
    return payload


async def post(payload, headers=AUTH):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        return await client.post(PATH, json=payload, headers=headers)


def install(monkeypatch, fake):
    monkeypatch.setattr(summary_service, "stream_chat", fake)
    return fake


@pytest.fixture
def llm(monkeypatch):
    return install(monkeypatch, FakeLLM("  요약: range는 ", "0부터 센다.  \n"))


class RaisingLLM:
    """stream_chat stand-in whose stream raises instead of yielding an error line."""

    def __init__(self, error):
        self.error = error
        self.calls = []

    async def __call__(self, request):
        self.calls.append(request)
        raise self.error
        yield  # pragma: no cover - makes this an async generator


class SilentLLM:
    """stream_chat stand-in that ends without any delta."""

    def __init__(self):
        self.calls = []

    async def __call__(self, request):
        self.calls.append(request)
        return
        yield  # pragma: no cover


# ------------------------------------------------------------------ success


async def test_ok_returns_stripped_summary(llm):
    response = await post(body())

    assert response.status_code == 200
    assert response.json() == {"summary": "요약: range는 0부터 센다."}
    assert len(llm.calls) == 1


async def test_model_sees_previous_summary_and_every_message(llm):
    await post(body())

    seen = llm.full_text()
    assert "파이썬 반복문을 배우는 중이다." in seen
    assert "range(5)는 뭐예요?" in seen
    assert "0부터 4까지의 수를 차례로 만들어요." in seen


@pytest.mark.parametrize("changes", [
    {"scope": PERSONAL},
    {"previousSummary": None},
    {"messages": [{"role": "user", "content": "x" * 8000}] * 40},
    {"previousSummary": "y" * 4000},
], ids=["personal", "null_previous", "40_messages_8000_each", "previous_4000"])
async def test_valid_bodies_are_accepted(llm, changes):
    assert (await post(body(**changes))).status_code == 200


async def test_previous_summary_may_be_omitted(llm):
    payload = body()
    del payload["previousSummary"]

    assert (await post(payload)).status_code == 200


async def test_long_output_is_cut_to_4000(monkeypatch):
    install(monkeypatch, FakeLLM("가" * 3000, "나" * 3000))

    response = await post(body())

    assert response.status_code == 200
    summary = response.json()["summary"]
    assert len(summary) == 4000
    assert summary.startswith("가")


# ------------------------------------------------------------------ 422


def _without(key):
    payload = body()
    del payload[key]
    return payload


INVALID = {
    "unknown_top_level": body(extra="x"),
    "unknown_in_message": body(messages=[{"role": "user", "content": "q", "name": "x"}]),
    "unknown_in_scope": body(scope={"kind": "personal", "sessionId": 1}),
    "zero_messages": body(messages=[]),
    "41_messages": body(messages=[{"role": "user", "content": "q"}] * 41),
    "content_8001": body(messages=[{"role": "user", "content": "x" * 8001}]),
    "empty_content": body(messages=[{"role": "user", "content": ""}]),
    "system_role": body(messages=[{"role": "system", "content": "지시"}]),
    "previous_4001": body(previousSummary="y" * 4001),
    "scope_kind_unknown": body(scope={"kind": "class", "sessionId": 42}),
    "session_without_id": body(scope={"kind": "session"}),
    "session_id_zero": body(scope={"kind": "session", "sessionId": 0}),
    "session_id_string": body(scope={"kind": "session", "sessionId": "42"}),
    "missing_scope": _without("scope"),
    "missing_messages": _without("messages"),
}


@pytest.mark.parametrize("payload", list(INVALID.values()), ids=list(INVALID))
async def test_invalid_bodies_are_422_without_calling_the_model(llm, payload):
    response = await post(payload)

    assert response.status_code == 422
    assert llm.calls == []


# ------------------------------------------------------------------ boundary


@pytest.mark.parametrize("key", [None, "wrong", ""])
async def test_internal_key_required(llm, key):
    response = await post(body(), {} if key is None else {"X-Internal-Key": key})

    assert response.status_code == 401
    assert response.json() == {"detail": "internal authentication required"}
    assert llm.calls == []


async def test_unsupported_provider_is_501(llm, monkeypatch):
    monkeypatch.setenv("MODEL_PROVIDER", "unknown")

    assert (await post(body())).status_code == 501
    assert llm.calls == []


# ------------------------------------------------------------------ model failures


@pytest.mark.parametrize("fake", [
    lambda: FakeLLM("   ", "\n\t"),
    lambda: SilentLLM(),
], ids=["whitespace_only", "no_delta"])
async def test_empty_model_output_is_502(monkeypatch, fake):
    install(monkeypatch, fake())

    response = await post(body())

    assert response.status_code == 502
    assert response.json() == {"detail": "empty_summary"}


@pytest.mark.parametrize("fake", [
    lambda: FakeLLM(error="llm_unavailable"),
    lambda: FakeLLM("부분 요약", error="llm_unavailable"),
    lambda: RaisingLLM(ModelUnavailable("local model and fallback unavailable")),
    lambda: RaisingLLM(httpx.ReadTimeout("slow")),
], ids=["error_line", "error_after_partial", "model_unavailable", "timeout"])
async def test_model_failure_is_503(monkeypatch, fake):
    install(monkeypatch, fake())

    response = await post(body())

    assert response.status_code == 503
    assert response.json() == {"detail": "llm_unavailable"}


# ------------------------------------------------------------------ privacy


@pytest.mark.parametrize("fake", [
    lambda: FakeLLM("SECRET-OUTPUT 요약"),
    lambda: FakeLLM(error="llm_unavailable"),
    lambda: FakeLLM("  "),
], ids=["ok", "llm_error", "empty"])
async def test_bodies_are_never_logged(monkeypatch, caplog, fake):
    caplog.set_level(logging.DEBUG)
    install(monkeypatch, fake())
    payload = body(messages=[{"role": "user", "content": f"{SECRET} 질문"}], previousSummary=f"{SECRET} 요약")

    await post(payload)

    assert SECRET not in caplog.text
    assert "SECRET-OUTPUT" not in caplog.text


async def test_validation_error_does_not_echo_long_input_into_logs(llm, caplog):
    caplog.set_level(logging.DEBUG)

    await post(body(messages=[{"role": "user", "content": SECRET + "x" * 8001}]))

    assert SECRET not in caplog.text


async def test_response_is_json_object_with_summary_only(llm):
    response = await post(body())

    assert response.headers["content-type"].startswith("application/json")
    assert list(json.loads(response.text)) == ["summary"]
