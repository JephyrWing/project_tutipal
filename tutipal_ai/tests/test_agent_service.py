"""PT-85 run_agent behaviour with a fake answer LLM and a fake PT-84 variant tool.

AC-2, 3, 4, 5, 6, 10, 14 (node re-check), 15 (FastAPI), 17, 18, 19 (FastAPI), 20-23 via meta, 26 (FastAPI).
"""
import asyncio
import json
import logging

import pytest

from agent_support import (
    CHOICE_SOURCE,
    CHOICE_VARIANT,
    FakeLLM,
    FakeVariant,
    kinds,
    meta_of,
    parse,
    personal_body,
    run,
    student_body,
    variant_body,
)
from app.services.agent import prompts, service
from app.services.agent.classify import HELP_SUGGESTION_MARKER
from app.services.agent.context import estimate_tokens

pytestmark = pytest.mark.anyio


# ---------------------------------------------------------------- normal turn (AC-5, AC-8 order, meta)

async def test_student_turn_streams_start_delta_meta_with_one_llm_call(monkeypatch):
    events, llm, variant = await run(student_body(), monkeypatch)

    assert kinds(events) == ["start", "delta", "delta", "meta"]
    assert {"start": {"materialIds": ["c_1032", "c_1047"]}} in events
    assert [e["delta"] for e in events if "delta" in e] == ["답변", "입니다"]
    assert len(llm.calls) == 1
    assert variant.originals == []
    meta = meta_of(events)
    assert meta["agent"] == "student"
    assert meta["route"] == "answer_request"
    assert meta["llmCalls"] == 1
    assert meta["hintLevel"] == 1
    assert meta["suggestHelp"] is False
    assert meta["searchRound"] == 0
    assert meta["variant"] is None
    assert meta["retryable"] is False
    assert meta["usedMemoryIds"] == []
    assert meta["usedSummaryId"] is None
    assert meta["context"]["budget"] == 3000
    assert set(meta["context"]["dropped"]) == {"messages", "memories", "materials", "screenSummary"}
    assert isinstance(meta["context"]["truncated"], list)
    assert all(isinstance(node, str) for node in meta["nodes"])
    assert "classify" in meta["nodes"]
    assert meta["nodes"][-1] == "respond"


async def test_meta_never_contains_question_or_material_text(monkeypatch):
    body = student_body(question="QUESTION-MARKER-7781 정답 알려줘")
    events, _, _ = await run(body, monkeypatch)
    dumped = json.dumps(meta_of(events), ensure_ascii=False)
    assert "QUESTION-MARKER-7781" not in dumped
    assert "range(n)은" not in dumped


async def test_each_request_calls_the_model_again(monkeypatch):
    llm = FakeLLM()
    await run(student_body(), monkeypatch, llm=llm)
    await run(student_body(), monkeypatch, llm=llm)
    assert len(llm.calls) == 2


async def test_question_is_the_last_user_message_after_history(monkeypatch):
    body = student_body(question="range(5)의 마지막 값은?")
    events, llm, _ = await run(body, monkeypatch)
    sent = llm.calls[0].messages
    assert sent[-1].role == "user"
    assert sent[-1].content == "range(5)의 마지막 값은?"
    assert [(m.role, m.content) for m in sent[:-1]] == \
        [(m["role"], m["content"]) for m in body["context"]["messages"]]


async def test_materials_are_given_to_the_model(monkeypatch):
    _, llm, _ = await run(student_body(), monkeypatch)
    text = llm.full_text()
    assert "range(n)은 0부터 n-1까지..." in text
    assert "for 변수 in 반복가능객체:" in text


async def test_model_error_line_ends_stream_without_meta(monkeypatch):
    events, _, _ = await run(student_body(), monkeypatch, llm=FakeLLM("부분", error="llm_unavailable"))
    assert kinds(events) == ["start", "delta", "error"]
    assert events[-1] == {"error": "llm_unavailable"}


async def test_model_error_before_first_delta(monkeypatch):
    events, _, _ = await run(student_body(), monkeypatch, llm=FakeLLM(error="llm_unavailable"))
    assert events[-1] == {"error": "llm_unavailable"}
    assert "meta" not in kinds(events)
    assert "delta" not in kinds(events)


async def test_estimated_tokens_in_meta_match_what_was_sent(monkeypatch):
    events, llm, _ = await run(student_body(), monkeypatch)
    sent = estimate_tokens(llm.full_text())
    reported = meta_of(events)["context"]["estimatedInputTokens"]
    assert abs(sent - reported) <= 50


# ---------------------------------------------------------------- prompts (AC-2, AC-3, AC-6)

@pytest.mark.parametrize("question", ["정답 알려줘", "range 함수가 뭐예요?", "제 답은 120이에요",
                                      "실행하면 오류가 떠요"])
async def test_hide_answers_rule_is_always_in_system_when_policy_hides(monkeypatch, question):
    events, llm, _ = await run(student_body(question=question), monkeypatch)
    assert prompts.HIDE_ANSWERS_RULE in llm.systems[0]


async def test_hide_answers_rule_absent_when_policy_shows_answers(monkeypatch):
    body = student_body()
    body["policy"]["hideAnswers"] = False
    _, llm, _ = await run(body, monkeypatch)
    assert prompts.HIDE_ANSWERS_RULE not in llm.systems[0]


def test_hide_answers_rule_forbids_final_answer_and_complete_code():
    assert "정답" in prompts.HIDE_ANSWERS_RULE
    assert "코드" in prompts.HIDE_ANSWERS_RULE


@pytest.mark.parametrize("history,level", [
    ([], 1),
    ([{"role": "user", "content": "정답 알려줘"}, {"role": "assistant", "content": "힌트"}], 2),
    ([{"role": "user", "content": "모르겠어요"}, {"role": "assistant", "content": "힌트"},
      {"role": "user", "content": "정답 알려줘"}, {"role": "assistant", "content": "힌트"}], 3),
])
async def test_step_hint_instruction_matches_hint_level(monkeypatch, history, level):
    body = student_body(question="정답 알려줘")
    body["context"]["messages"] = history
    events, llm, _ = await run(body, monkeypatch)
    assert meta_of(events)["hintLevel"] == level
    assert prompts.HINT_RULES[level] in llm.systems[0]
    assert len(set(prompts.HINT_RULES.values())) == 3


async def test_answer_check_instruction(monkeypatch):
    events, llm, _ = await run(student_body(question="제 답은 120이에요. 맞나요?"), monkeypatch)
    assert meta_of(events)["route"] == "answer_check"
    assert prompts.ANSWER_CHECK_RULE in llm.systems[0]
    assert prompts.HIDE_ANSWERS_RULE in llm.systems[0]


def test_answer_check_rule_points_out_right_and_wrong_parts():
    assert "맞은" in prompts.ANSWER_CHECK_RULE
    assert "틀린" in prompts.ANSWER_CHECK_RULE


async def test_answer_check_rule_absent_for_concept_question(monkeypatch):
    _, llm, _ = await run(student_body(question="range 함수가 뭐예요?"), monkeypatch)
    assert prompts.ANSWER_CHECK_RULE not in llm.systems[0]


async def test_personal_puts_screen_summary_in_a_data_block(monkeypatch):
    events, llm, _ = await run(personal_body(), monkeypatch)
    text = llm.full_text()
    assert "<screen_summary>" in text and "</screen_summary>" in text
    start = text.index("<screen_summary>")
    assert "NullPointerException at Main.java:12" in text[start:text.index("</screen_summary>")]
    assert prompts.HIDE_ANSWERS_RULE not in llm.systems[0]
    meta = meta_of(events)
    assert meta["agent"] == "personal"
    assert meta["hintLevel"] is None
    assert meta["suggestHelp"] is False
    assert kinds(events)[0] == "start"
    assert {"start": {"materialIds": []}} in events


async def test_personal_without_screen_summary_has_no_block(monkeypatch):
    body = personal_body()
    body["context"]["screenSummary"] = None
    _, llm, _ = await run(body, monkeypatch)
    assert "<screen_summary>" not in llm.full_text()


async def test_personal_never_suggests_help_even_when_stuck(monkeypatch):
    body = personal_body(question="그래도 모르겠어요")
    body["context"]["messages"] = [
        {"role": "user", "content": "모르겠어요"}, {"role": "assistant", "content": "힌트"},
        {"role": "user", "content": "정답 알려줘"}, {"role": "assistant", "content": "힌트"},
    ]
    events, _, _ = await run(body, monkeypatch)
    assert meta_of(events)["suggestHelp"] is False


async def test_student_help_suggestion_reported_in_meta(monkeypatch):
    body = student_body(question="그래도 모르겠어요")
    body["context"]["messages"] = [
        {"role": "user", "content": "모르겠어요"}, {"role": "assistant", "content": "힌트"},
        {"role": "user", "content": "정답 알려줘"}, {"role": "assistant", "content": "힌트"},
    ]
    events, _, _ = await run(body, monkeypatch)
    assert meta_of(events)["suggestHelp"] is True
    body["context"]["messages"][-1]["content"] = "힌트. " + HELP_SUGGESTION_MARKER
    events, _, _ = await run(body, monkeypatch)
    assert meta_of(events)["suggestHelp"] is False


# ---------------------------------------------------------------- isolation and memory (AC-10, AC-22, AC-23)

async def test_concurrent_requests_only_see_their_own_context(monkeypatch):
    llm = FakeLLM("a", "b", "c", pause=0.01)
    monkeypatch.setattr(service, "stream_chat", llm)
    monkeypatch.setattr(service, "generate_question_variant", FakeVariant())
    from app.models.agent import AgentChatRequest

    def body(tag):
        b = personal_body(question=f"질문-{tag}")
        b["context"]["messages"] = [{"role": "user", "content": f"이전-{tag}"},
                                    {"role": "assistant", "content": f"답-{tag}"}]
        b["context"]["summary"] = {"id": f"s_{tag}", "scope": {"kind": "personal"}, "text": f"요약-{tag}"}
        b["context"]["memories"] = [{"id": f"m_{tag}", "scope": {"kind": "personal"}, "text": f"기억-{tag}"}]
        b["context"]["screenSummary"] = f"화면-{tag}"
        return AgentChatRequest.model_validate(b)

    async def drain(request):
        return parse([line async for line in service.run_agent(request)])

    first, second = await asyncio.gather(drain(body("AAA")), drain(body("BBB")))
    assert len(llm.calls) == 2
    for index in range(2):
        text = llm.full_text(index)
        own, other = ("AAA", "BBB") if "질문-AAA" in text else ("BBB", "AAA")
        for prefix in ("질문-", "이전-", "답-", "요약-", "기억-", "화면-"):
            assert prefix + own in text
            assert prefix + other not in text
    assert meta_of(first)["usedMemoryIds"] == ["m_AAA"]
    assert meta_of(second)["usedMemoryIds"] == ["m_BBB"]
    assert meta_of(first)["usedSummaryId"] == "s_AAA"
    assert meta_of(second)["usedSummaryId"] == "s_BBB"


async def test_used_memory_ids_are_a_subset_of_request_ids(monkeypatch):
    body = student_body()
    body["context"]["memories"] = [
        {"id": f"m_{i}", "scope": {"kind": "session", "sessionId": 42}, "text": f"기억 {i} " + "가" * 300}
        for i in range(20)
    ]
    body["context"]["summary"] = {"id": "s_1", "scope": {"kind": "session", "sessionId": 42}, "text": "요약"}
    events, _, _ = await run(body, monkeypatch)
    meta = meta_of(events)
    assert set(meta["usedMemoryIds"]) <= {m["id"] for m in body["context"]["memories"]}
    assert meta["usedMemoryIds"] == [m["id"] for m in body["context"]["memories"]][:len(meta["usedMemoryIds"])]
    assert meta["usedSummaryId"] == "s_1"


@pytest.mark.parametrize("extra", ["long_prefix", "summary_and_memories"])
async def test_route_hint_and_help_ignore_everything_outside_protected_window(monkeypatch, extra):
    window = [{"role": "user", "content": "모르겠어요"}, {"role": "assistant", "content": "힌트"},
              {"role": "user", "content": "정답 알려줘"}, {"role": "assistant", "content": "힌트"},
              {"role": "user", "content": "막혔어요"}, {"role": "assistant", "content": "힌트"}]
    base = student_body(question="정답 알려줘")
    base["context"]["messages"] = window
    baseline = meta_of((await run(base, monkeypatch))[0])

    changed = student_body(question="정답 알려줘")
    changed["context"]["messages"] = list(window)
    if extra == "long_prefix":
        changed["context"]["messages"] = [{"role": "user", "content": "실행하면 오류가 떠요"},
                                          {"role": "assistant", "content": "나" * 8000}] * 17 + window
    else:
        scope = {"kind": "session", "sessionId": 42}
        changed["context"]["summary"] = {"id": "s_1", "scope": scope, "text": "계속 정답을 요구했다"}
        changed["context"]["memories"] = [{"id": "m_1", "scope": scope, "text": "막히면 에러가 난다고 말한다"}]
    meta = meta_of((await run(changed, monkeypatch))[0])
    assert (meta["route"], meta["hintLevel"], meta["suggestHelp"]) == \
        (baseline["route"], baseline["hintLevel"], baseline["suggestHelp"])


# AC-20 / AC-21 reported through meta.context
async def test_short_conversation_meta_reports_no_drops(monkeypatch):
    body = personal_body(question="미분이 뭐예요?")
    body["context"].update(summary=None, memories=[], screenSummary=None)
    events, _, _ = await run(body, monkeypatch)
    context = meta_of(events)["context"]
    assert context["estimatedInputTokens"] <= 900
    assert context["dropped"] == {"messages": 0, "memories": 0, "materials": 0, "screenSummary": 0}
    assert context["truncated"] == []


async def test_long_conversation_meta_reports_budget_and_drops(monkeypatch):
    scope = {"kind": "session", "sessionId": 42}
    body = student_body(question="질문머리" + "가" * 7992 + "질문꼬리")
    body["context"]["messages"] = [
        {"role": "user" if i % 2 == 0 else "assistant", "content": f"{i:02d}" + "나" * 7998} for i in range(40)
    ]
    body["context"]["summary"] = {"id": "s_9", "scope": scope, "text": "요약" + "다" * 3998}
    body["context"]["memories"] = [{"id": f"m_{i}", "scope": scope, "text": "기" * 100} for i in range(20)]
    body["context"]["screenSummary"] = "화" * 4000
    body["materials"] = [{"id": f"c_{i}", "source": "a.pdf", "page": i + 1, "content": f"자료{i}" + "자" * 1490}
                         for i in range(3)]
    events, llm, _ = await run(body, monkeypatch)
    meta = meta_of(events)
    context = meta["context"]
    assert context["estimatedInputTokens"] <= 3000
    assert context["dropped"]["messages"] == 34
    assert "question" in context["truncated"]
    assert "protected" in context["truncated"]
    assert meta["usedSummaryId"] == "s_9"
    text = llm.full_text()
    assert "질문머리" in text and "질문꼬리" in text
    start = next(e["start"] for e in events if "start" in e)
    assert 1 <= len(start["materialIds"]) <= 3
    assert set(start["materialIds"]) <= {"c_0", "c_1", "c_2"}
    for item in body["materials"]:
        assert (item["content"][:4] in text) == (item["id"] in start["materialIds"])


# ---------------------------------------------------------------- need_search (AC-5, AC-15 FastAPI side)

def search_body(**changes):
    body = student_body(materials=[])
    body.update(changes)
    return body


async def test_need_search_is_the_only_line_and_calls_no_model(monkeypatch):
    events, llm, _ = await run(search_body(), monkeypatch)
    assert kinds(events) == ["status"]
    line = events[-1]
    assert line["status"] == "need_search"
    assert 1 <= len(line["query"]) <= 500
    assert isinstance(line["state"], dict)
    assert len(json.dumps(line["state"], ensure_ascii=False).encode()) <= 65536
    assert llm.calls == []


async def test_need_search_query_is_capped_for_long_questions(monkeypatch):
    events, _, _ = await run(search_body(question="가" * 8000), monkeypatch)
    assert 1 <= len(events[-1]["query"]) <= 500


async def test_need_search_state_has_no_memory_summary_or_material_text(monkeypatch):
    body = search_body()
    scope = {"kind": "session", "sessionId": 42}
    body["context"]["summary"] = {"id": "s_1", "scope": scope, "text": "SUMMARY-MARKER"}
    body["context"]["memories"] = [{"id": "m_1", "scope": scope, "text": "MEMORY-MARKER"}]
    events, _, _ = await run(body, monkeypatch)
    state = json.dumps(events[-1]["state"], ensure_ascii=False)
    assert "SUMMARY-MARKER" not in state
    assert "MEMORY-MARKER" not in state


async def test_state_round_trips_and_round_two_never_asks_again(monkeypatch):
    first, _, _ = await run(search_body(), monkeypatch)
    state = first[-1]["state"]

    second, llm, _ = await run(search_body(search={"round": 1, "state": state}), monkeypatch)
    assert kinds(second) == ["status"]
    assert llm.calls == []

    third, llm, _ = await run(search_body(search={"round": 2, "state": second[-1]["state"]}), monkeypatch)
    assert "status" not in kinds(third)
    assert kinds(third)[-1] == "meta"
    assert len(llm.calls) == 1
    assert meta_of(third)["searchRound"] == 2


async def test_no_need_search_without_material_search_tool(monkeypatch):
    events, llm, _ = await run(search_body(allowedTools=["answer_question"]), monkeypatch)
    assert "status" not in kinds(events)
    assert len(llm.calls) == 1


# ---------------------------------------------------------------- variant tool (AC-18, AC-19 FastAPI side)

async def test_variant_success_emits_variant_then_meta_without_answer_llm(monkeypatch):
    events, llm, variant = await run(variant_body(), monkeypatch)
    assert kinds(events) == ["variant", "meta"]
    assert {"variant": {"status": "ok", "attempts": 1, "variant": CHOICE_VARIANT}} in events
    assert llm.calls == []
    assert variant.originals == [CHOICE_SOURCE]
    meta = meta_of(events)
    assert meta["route"] == "variant"
    assert meta["variant"] == "ok"
    assert meta["llmCalls"] == 0
    assert meta["hintLevel"] is None
    assert meta["retryable"] is False


async def test_text_variant_source_is_passed_without_none_fields(monkeypatch):
    source = {"type": "TEXT", "question": "25%가 15인 수는?", "modelAnswer": "60"}
    _, _, variant = await run(variant_body(source=source), monkeypatch,
                              variant=FakeVariant({"status": "failed", "attempts": 3,
                                                   "failure_reasons": ["model_unavailable"]}))
    assert variant.originals == [source]


async def test_validation_failure_falls_back_to_one_general_answer_with_retry(monkeypatch):
    failed = FakeVariant({"status": "failed", "attempts": 3, "failure_reasons": ["concept_mismatch"]})
    events, llm, _ = await run(variant_body(), monkeypatch, variant=failed)
    assert kinds(events)[0] == "variant"
    assert kinds(events)[1] == "start"
    assert kinds(events)[-1] == "meta"
    assert "delta" in kinds(events)
    assert len(llm.calls) == 1
    assert prompts.HIDE_ANSWERS_RULE in llm.systems[0]
    meta = meta_of(events)
    assert meta["variant"] == "failed"
    assert meta["retryable"] is True
    assert meta["llmCalls"] == 1
    assert {"variant": {"status": "failed", "attempts": 3, "failure_reasons": ["concept_mismatch"]}} in events


async def test_model_unavailable_failure_uses_fixed_notice_without_llm(monkeypatch):
    failed = FakeVariant({"status": "failed", "attempts": 1, "failure_reasons": ["model_unavailable"]})
    events, llm, _ = await run(variant_body(), monkeypatch, variant=failed)
    assert kinds(events) == ["variant", "meta"]
    assert llm.calls == []
    assert meta_of(events)["retryable"] is True
    assert meta_of(events)["variant"] == "failed"


async def test_invalid_original_is_not_retryable(monkeypatch):
    failed = FakeVariant({"status": "failed", "attempts": 0, "failure_reasons": ["invalid_original"]})
    events, llm, _ = await run(variant_body(), monkeypatch, variant=failed)
    assert kinds(events) == ["variant", "meta"]
    assert llm.calls == []
    assert meta_of(events)["retryable"] is False


async def test_validation_failure_with_less_than_30_seconds_left_uses_fixed_notice(monkeypatch):
    monkeypatch.setenv("AGENT_DEADLINE_SECONDS", "20")
    failed = FakeVariant({"status": "failed", "attempts": 3, "failure_reasons": ["concept_mismatch"]})
    events, llm, _ = await run(variant_body(), monkeypatch, variant=failed)
    assert kinds(events) == ["variant", "meta"]
    assert llm.calls == []
    assert meta_of(events)["retryable"] is True


def test_fallback_threshold_is_30_seconds():
    assert service.FALLBACK_MIN_REMAINING_SECONDS == 30


async def test_variant_out_of_time_reports_model_unavailable_not_agent_timeout(monkeypatch):
    monkeypatch.setenv("AGENT_DEADLINE_SECONDS", "0.3")
    hanging = FakeVariant(hang=True)
    events, llm, _ = await run(variant_body(), monkeypatch, variant=hanging)
    assert {"error": "agent_timeout"} not in events
    variant_line = next(e["variant"] for e in events if "variant" in e)
    assert variant_line["status"] == "failed"
    assert variant_line["failure_reasons"] == ["model_unavailable"]
    assert kinds(events)[-1] == "meta"
    assert llm.calls == []
    assert hanging.cancelled.is_set()


async def test_same_variant_request_runs_the_tool_again(monkeypatch):
    variant = FakeVariant({"status": "failed", "attempts": 1, "failure_reasons": ["model_unavailable"]})
    await run(variant_body(), monkeypatch, variant=variant)
    await run(variant_body(), monkeypatch, variant=variant)
    assert len(variant.originals) == 2


# ---------------------------------------------------------------- node re-check (AC-14), deadline (AC-17)

async def test_node_recheck_rejects_tool_outside_allowed_list(monkeypatch):
    from app.models.agent import AgentChatRequest

    llm, variant = FakeLLM(), FakeVariant()
    monkeypatch.setattr(service, "stream_chat", llm)
    monkeypatch.setattr(service, "generate_question_variant", variant)
    valid = AgentChatRequest.model_validate(variant_body())
    tampered = valid.model_copy(update={"allowedTools": ["answer_question"]})
    events = parse([line async for line in service.run_agent(tampered)])
    assert events[-1] == {"error": "tool_not_allowed"}
    assert variant.originals == []
    assert llm.calls == []


async def test_answer_without_answer_tool_is_rejected_at_node(monkeypatch):
    from app.models.agent import AgentChatRequest

    llm = FakeLLM()
    monkeypatch.setattr(service, "stream_chat", llm)
    monkeypatch.setattr(service, "generate_question_variant", FakeVariant())
    valid = AgentChatRequest.model_validate(personal_body())
    tampered = valid.model_copy(update={"allowedTools": []})
    events = parse([line async for line in service.run_agent(tampered)])
    assert events[-1] == {"error": "tool_not_allowed"}
    assert llm.calls == []


def test_require_tool_raises_for_missing_tool():
    from app.services.agent.tools import ToolNotAllowed, require_tool

    require_tool(["answer_question", "question_variant"], "question_variant")
    with pytest.raises(ToolNotAllowed):
        require_tool(["answer_question"], "question_variant")


async def test_first_delta_deadline_ends_with_agent_timeout(monkeypatch):
    monkeypatch.setenv("AGENT_DEADLINE_SECONDS", "0.3")
    llm = FakeLLM(hang_before_first=True)
    events, _, _ = await run(personal_body(), monkeypatch, llm=llm)
    assert events[-1] == {"error": "agent_timeout"}
    assert "meta" not in kinds(events)
    assert llm.closed.is_set()


def test_default_deadline_is_120_seconds():
    assert service.deadline_seconds() == 120


async def test_deadline_does_not_cut_streaming_after_first_delta(monkeypatch):
    monkeypatch.setenv("AGENT_DEADLINE_SECONDS", "0.2")
    llm = FakeLLM("a", "b", "c", pause=0.15)
    events, _, _ = await run(personal_body(), monkeypatch, llm=llm)
    assert [e["delta"] for e in events if "delta" in e] == ["a", "b", "c"]
    assert kinds(events)[-1] == "meta"


# ---------------------------------------------------------------- ping (AC-16 FastAPI side)

async def test_ping_every_interval_while_variant_runs(monkeypatch):
    monkeypatch.setattr(service, "PING_INTERVAL_SECONDS", 0.05)
    events, _, _ = await run(variant_body(), monkeypatch, variant=FakeVariant(delay=0.4))
    first_variant = next(i for i, e in enumerate(events) if "variant" in e)
    assert sum(1 for e in events[:first_variant] if e == {"ping": True}) >= 2
    assert all(e == {"ping": True} for e in events if "ping" in e)


def test_ping_interval_is_10_seconds():
    assert service.PING_INTERVAL_SECONDS == 10


# ---------------------------------------------------------------- logging (AC-26 FastAPI side)

async def test_logs_node_names_but_never_the_question(monkeypatch, caplog):
    caplog.set_level(logging.DEBUG)
    body = student_body(question="SECRET-QUESTION-4417 정답 알려줘")
    body["context"]["memories"] = [{"id": "m_1", "scope": {"kind": "session", "sessionId": 42},
                                    "text": "SECRET-MEMORY-4417"}]
    await run(body, monkeypatch)
    await run(search_body(question="SECRET-SEARCH-4417 설명해 줘"), monkeypatch)
    await run(variant_body(), monkeypatch,
              variant=FakeVariant({"status": "failed", "attempts": 3, "failure_reasons": ["concept_mismatch"]}))
    assert "SECRET-QUESTION-4417" not in caplog.text
    assert "SECRET-MEMORY-4417" not in caplog.text
    assert "SECRET-SEARCH-4417" not in caplog.text
    assert "range(n)은" not in caplog.text
    assert CHOICE_SOURCE["question"] not in caplog.text
    agent_records = [r for r in caplog.records if r.name.startswith("app.services.agent")]
    assert agent_records
    assert any("classify" in r.getMessage() for r in agent_records)


# ------------------------------------------------------------------ empty answer stream (Codex review 2)

class _SilentLLM(FakeLLM):
    """An answer model that ends without error and without a single delta line."""

    async def __call__(self, request):
        self.calls.append(request)
        self.closed.set()
        return
        yield  # pragma: no cover - makes this an async generator


_FALLBACK_FAILURE = {"status": "failed", "attempts": 3, "failure_reasons": ["concept_mismatch"]}


async def test_empty_answer_stream_ends_with_llm_unavailable_without_start_or_meta(monkeypatch):
    events, llm, _ = await run(student_body(), monkeypatch, llm=_SilentLLM())
    assert len(llm.calls) == 1
    assert kinds(events) == ["error"]
    assert events[-1] == {"error": "llm_unavailable"}


async def test_only_empty_deltas_count_as_an_empty_stream(monkeypatch):
    events, llm, _ = await run(student_body(), monkeypatch, llm=FakeLLM("", ""))
    assert len(llm.calls) == 1
    assert kinds(events) == ["error"]
    assert events[-1] == {"error": "llm_unavailable"}


async def test_empty_deltas_before_text_are_dropped_and_start_precedes_first_text(monkeypatch):
    events, _, _ = await run(student_body(), monkeypatch, llm=FakeLLM("", "답변"))
    assert kinds(events) == ["start", "delta", "meta"]
    assert {"delta": "답변"} in events
    assert {"delta": ""} not in events


async def test_empty_fallback_answer_after_variant_failure_ends_with_meta_for_fixed_notice(monkeypatch):
    events, llm, _ = await run(variant_body(), monkeypatch, llm=_SilentLLM(),
                               variant=FakeVariant(_FALLBACK_FAILURE))
    assert len(llm.calls) == 1
    assert kinds(events) == ["variant", "meta"]
    meta = meta_of(events)
    assert meta["variant"] == "failed"
    assert meta["retryable"] is True


async def test_empty_string_fallback_answer_matches_empty_fallback_stream(monkeypatch):
    events, _, _ = await run(variant_body(), monkeypatch, llm=FakeLLM(""),
                             variant=FakeVariant(_FALLBACK_FAILURE))
    assert kinds(events) == ["variant", "meta"]
