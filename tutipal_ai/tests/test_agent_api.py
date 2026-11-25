"""PT-85 AC-11, AC-13, AC-14, AC-24: POST /v1/agent/chat boundary (contract 7.1-7.3)."""
import copy
import json

import httpx
import pytest

from agent_support import AUTH, FakeLLM, FakeVariant, personal_body, student_body, variant_body
from app.main import app
from app.models.agent import SEARCH_STATE_MAX_BYTES, AgentChatRequest
from app.services.agent import service

pytestmark = pytest.mark.anyio
PATH = "/v1/agent/chat"
SECRET = "PRIVATE-INPUT"


async def post(body, headers=AUTH):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        return await client.post(PATH, json=body, headers=headers)


@pytest.fixture
def fakes(monkeypatch):
    llm, variant = FakeLLM(), FakeVariant()
    monkeypatch.setattr(service, "stream_chat", llm)
    monkeypatch.setattr(service, "generate_question_variant", variant)
    return llm, variant


@pytest.mark.parametrize("key", [None, "wrong", ""])
async def test_internal_key_required(fakes, key):
    response = await post(student_body(), {} if key is None else {"X-Internal-Key": key})
    assert response.status_code == 401
    assert response.json() == {"detail": "internal authentication required"}
    assert fakes[0].calls == []


async def test_body_over_limit_is_413(fakes):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        async def chunks():
            yield b"x" * 2_300_000
            yield b"x" * 2_300_000
        response = await client.post(PATH, content=chunks(), headers=AUTH)
    assert response.status_code == 413


async def test_unsupported_provider_is_501(fakes, monkeypatch):
    monkeypatch.setenv("MODEL_PROVIDER", "unknown")
    assert (await post(student_body())).status_code == 501


@pytest.mark.parametrize("body", [student_body(), personal_body(), variant_body()], ids=["student", "personal", "variant"])
async def test_valid_bodies_stream_ndjson(fakes, body):
    response = await post(body)
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("application/x-ndjson")
    assert response.headers["cache-control"] == "no-store"
    lines = [json.loads(line) for line in response.text.splitlines() if line.strip()]
    assert "meta" in lines[-1]


def _set(path, value):
    def change(body):
        target = body
        for key in path[:-1]:
            target = target[key]
        target[path[-1]] = value
    return change


def _student(change):
    def build():
        body = student_body(question=f"{SECRET} 정답 알려줘")
        change(body)
        return body
    return build


def _personal(change):
    def build():
        body = personal_body(question=f"{SECRET} 무슨 뜻이에요?")
        change(body)
        return body
    return build


def _variant(change):
    def build():
        body = variant_body()
        body["question"] = f"{SECRET} 비슷한 문제"
        change(body)
        return body
    return build


SESSION = {"kind": "session", "sessionId": 42}
OTHER_SESSION = {"kind": "session", "sessionId": 43}
PERSONAL = {"kind": "personal"}
SOURCE = variant_body()["variantSource"]

INVALID = {
    # 7.3 tools
    "reserved_screen_analyze": _student(_set(["allowedTools"], ["answer_question", "screen_analyze"])),
    "reserved_screen_summary": _student(_set(["allowedTools"], ["answer_question", "screen_summary"])),
    "unknown_tool": _student(_set(["allowedTools"], ["answer_question", "web_search"])),
    "missing_answer_question": _student(_set(["allowedTools"], ["material_search"])),
    "duplicate_tool": _student(_set(["allowedTools"], ["answer_question", "answer_question"])),
    "personal_material_search": _personal(_set(["allowedTools"], ["answer_question", "material_search"])),
    "personal_question_variant": _personal(lambda b: b.update(
        allowedTools=["answer_question", "question_variant"], variantSource=copy.deepcopy(SOURCE))),
    "variant_tool_without_source": _student(_set(["allowedTools"], ["answer_question", "question_variant"])),
    "variant_source_without_tool": _student(_set(["variantSource"], copy.deepcopy(SOURCE))),
    # 7.3 personal restrictions
    "personal_materials": _personal(_set(["materials"], [{"id": "c_1", "source": "a.pdf", "content": "x"}])),
    "personal_hide_answers": _personal(_set(["policy", "hideAnswers"], True)),
    "personal_prefer_materials": _personal(_set(["policy", "preferMaterials"], True)),
    "personal_search_round": _personal(_set(["search"], {"round": 1, "state": {"v": 1}})),
    # 7.3 / AC-11 scope
    "student_personal_scope": _student(_set(["context", "scope"], PERSONAL)),
    "personal_session_scope": _personal(lambda b: (b["context"].update(scope=SESSION),
                                                   b["context"].update(summary=None, memories=[]))),
    "student_summary_personal_scope": _student(_set(["context", "summary"],
                                                    {"id": "s_1", "scope": PERSONAL, "text": "요약"})),
    "student_summary_other_session": _student(_set(["context", "summary"],
                                                   {"id": "s_1", "scope": OTHER_SESSION, "text": "요약"})),
    "student_memory_other_session": _student(_set(["context", "memories"],
                                                  [{"id": "m_1", "scope": OTHER_SESSION, "text": "기억"}])),
    "student_memory_personal_scope": _student(_set(["context", "memories"],
                                                   [{"id": "m_1", "scope": PERSONAL, "text": "기억"}])),
    "personal_memory_session_scope": _personal(_set(["context", "memories"],
                                                    [{"id": "m_1", "scope": SESSION, "text": "기억"}])),
    "personal_summary_session_scope": _personal(_set(["context", "summary"],
                                                     {"id": "s_1", "scope": SESSION, "text": "요약"})),
    "scope_unknown_kind": _student(_set(["context", "scope"], {"kind": "class", "sessionId": 42})),
    # 7.3 search/state
    "round_without_state": _student(_set(["search"], {"round": 1, "state": None})),
    "state_on_round_zero": _student(_set(["search"], {"round": 0, "state": {"v": 1}})),
    "state_unknown_shape": _student(_set(["search"], {"round": 1, "state": {SECRET: "x"}})),
    "state_too_large": _student(_set(["search"], {"round": 1, "state": {"v": 1, "pad": "x" * 70_000}})),
    "round_three": _student(_set(["search"], {"round": 3, "state": {"v": 1}})),
    # AC-13: student answer fields in variantSource
    "variant_source_choice_index": _variant(lambda b: b["variantSource"].update(choiceIndex=1)),
    "variant_source_answer_text": _variant(lambda b: b["variantSource"].update(text=SECRET)),
    "variant_source_feedback": _variant(lambda b: b["variantSource"].update(feedback=SECRET)),
    "variant_source_bad_answer_index": _variant(lambda b: b["variantSource"].update(answerIndex=9)),
    # extra fields at every level
    "extra_top": _student(_set(["userId"], 7)),
    "extra_policy": _student(_set(["policy", "extra"], SECRET)),
    "extra_context": _student(_set(["context", "extra"], SECRET)),
    "extra_scope": _student(_set(["context", "scope"], {"kind": "session", "sessionId": 42, "userId": 7})),
    "extra_message": _student(lambda b: b["context"]["messages"][0].update(extra=SECRET)),
    "extra_material": _student(lambda b: b["materials"][0].update(extra=SECRET)),
    "extra_search": _student(_set(["search", "extra"], SECRET)),
    "extra_memory": _student(_set(["context", "memories"],
                                  [{"id": "m_1", "scope": SESSION, "text": "기억", "deletedAt": "x"}])),
    "extra_summary": _student(_set(["context", "summary"],
                                   {"id": "s_1", "scope": SESSION, "text": "요약", "invalidatedAt": "x"})),
    # limits
    "agent_teacher": _student(_set(["agent"], "teacher")),
    "question_empty": _student(_set(["question"], "")),
    "question_too_long": _student(_set(["question"], SECRET + "x" * 8000)),
    "messages_41": _student(_set(["context", "messages"], [{"role": "user", "content": SECRET}] * 41)),
    "message_too_long": _student(_set(["context", "messages"], [{"role": "user", "content": SECRET + "x" * 8000}])),
    "message_system_role": _student(_set(["context", "messages"], [{"role": "system", "content": SECRET}])),
    "message_empty": _student(_set(["context", "messages"], [{"role": "user", "content": ""}])),
    "memories_21": _student(_set(["context", "memories"],
                                 [{"id": f"m_{i}", "scope": SESSION, "text": "기억"} for i in range(21)])),
    "memory_too_long": _student(_set(["context", "memories"], [{"id": "m_1", "scope": SESSION, "text": "x" * 1001}])),
    "memory_bad_id": _student(_set(["context", "memories"], [{"id": "m 1!", "scope": SESSION, "text": "기억"}])),
    "summary_too_long": _student(_set(["context", "summary"], {"id": "s_1", "scope": SESSION, "text": "x" * 4001})),
    "summary_bad_id": _student(_set(["context", "summary"], {"id": "s" * 65, "scope": SESSION, "text": "요약"})),
    "screen_summary_too_long": _student(_set(["context", "screenSummary"], SECRET + "x" * 4000)),
    "materials_4": _student(_set(["materials"],
                                 [{"id": f"c_{i}", "source": "a.pdf", "content": "x"} for i in range(4)])),
    "material_content_too_long": _student(lambda b: b["materials"][0].update(content="x" * 1501)),
    "material_bad_id": _student(lambda b: b["materials"][0].update(id="c 1/../")),
    "material_source_too_long": _student(lambda b: b["materials"][0].update(source="x" * 121)),
    "material_page_zero": _student(lambda b: b["materials"][0].update(page=0)),
    "difficulty_zero": _student(_set(["policy", "difficulty"], 0)),
    "difficulty_six": _student(_set(["policy", "difficulty"], 6)),
    "topic_too_long": _student(_set(["policy", "topic"], "x" * 31)),
    "topic_empty": _student(_set(["policy", "topic"], "")),
}


@pytest.mark.parametrize("name", list(INVALID))
async def test_invalid_body_is_422_without_echoing_input(fakes, name):
    response = await post(INVALID[name]())
    assert response.status_code == 422, name
    assert SECRET not in response.text
    assert "정답 알려줘" not in response.text
    assert all("input" not in error and "ctx" not in error for error in response.json()["detail"])
    assert fakes[0].calls == []
    assert fakes[1].originals == []


@pytest.mark.parametrize("name", list(INVALID))
def test_invalid_body_fails_model_validation(name):
    with pytest.raises(ValueError):
        AgentChatRequest.model_validate(INVALID[name]())


def test_state_limit_is_64kb():
    assert SEARCH_STATE_MAX_BYTES == 65536


@pytest.mark.parametrize("build", [student_body, personal_body, variant_body], ids=["student", "personal", "variant"])
def test_contract_examples_are_valid(build):
    request = AgentChatRequest.model_validate(build())
    assert request.agent in {"student", "personal"}


def test_student_may_use_all_three_tools():
    AgentChatRequest.model_validate(variant_body())


def test_student_summary_and_memories_in_same_session_are_valid():
    body = student_body()
    body["context"]["summary"] = {"id": "s_1", "scope": SESSION, "text": "요약"}
    body["context"]["memories"] = [{"id": "m-1_A", "scope": SESSION, "text": "기억"}]
    AgentChatRequest.model_validate(body)
