"""PT-85 AC-20, AC-21, AC-23 (FastAPI side): token estimate and context budget (contract 6.3, 6.4)."""
import pytest

from app.services.agent.context import BUDGET, estimate_tokens, fit_context

POLICY = "정책 지시문. 정답이나 완성된 코드를 바로 알려주지 않는다."


def msg(role, content):
    return {"role": role, "content": content}


def memory(index, text):
    return {"id": f"m_{index}", "scope": {"kind": "session", "sessionId": 42}, "text": text}


def material(index, content):
    return {"id": f"c_{index}", "source": f"자료{index}.pdf", "page": index, "content": content}


def fit(**overrides):
    args = dict(system=POLICY, question="range가 뭐예요?", messages=[], summary=None, memories=[],
                materials=[], screen_summary=None)
    args.update(overrides)
    return fit_context(**args)


def test_budget_is_3000():
    assert BUDGET == 3000


@pytest.mark.parametrize("text,tokens", [
    ("", 0),
    ("abc", 1),
    ("abcd", 2),
    (" ", 1),
    ("가나다", 3),
    ("가a", 2),
    ("ㄱㅏ", 2),
    ("漢字", 2),
    ("かなカナ", 4),
    ("for문 i", 3),
    ("a" * 300, 100),
    ("가" * 8000, 8000),
])
def test_estimate_tokens_counts_cjk_as_one_and_others_as_a_third(text, tokens):
    assert estimate_tokens(text) == tokens


# AC-20: one message, no materials/memories -> small and nothing dropped.
def test_short_conversation_keeps_everything():
    messages = [msg("user", "range(5)는 뭐예요?"), msg("assistant", "0부터 4까지 만들어요.")]
    fitted = fit(messages=messages)
    assert fitted.estimated_input_tokens <= 900
    assert fitted.dropped == {"messages": 0, "memories": 0, "materials": 0, "screenSummary": 0}
    assert fitted.truncated == []
    assert fitted.messages == messages
    assert fitted.question == "range가 뭐예요?"


def test_under_budget_nothing_is_truncated_even_if_one_part_exceeds_its_share():
    summary = {"id": "s_1", "scope": {"kind": "session", "sessionId": 42}, "text": "요" * 1000}
    memories = [memory(i, "기" * 100) for i in range(5)]
    fitted = fit(summary=summary, memories=memories)
    assert fitted.estimated_input_tokens <= BUDGET
    assert fitted.summary == summary
    assert fitted.memories == memories
    assert fitted.truncated == []
    assert sum(fitted.dropped.values()) == 0


def test_drops_oldest_messages_outside_protected_window_first():
    older = [msg("user" if i % 2 == 0 else "assistant", f"{i:02d}" + "가" * 200) for i in range(20)]
    protected = [msg("user" if i % 2 == 0 else "assistant", f"보호{i}") for i in range(6)]
    memories = [memory(i, "기억") for i in range(3)]
    fitted = fit(messages=older + protected, memories=memories, materials=[material(1, "자료")])
    assert fitted.estimated_input_tokens <= BUDGET
    assert fitted.dropped["messages"] > 0
    assert fitted.dropped["memories"] == 0
    assert fitted.dropped["materials"] == 0
    assert fitted.truncated == []
    kept_older = fitted.messages[:-6]
    assert kept_older == older[len(older) - len(kept_older):]
    assert fitted.messages[-6:] == protected
    assert fitted.dropped["messages"] == len(older) - len(kept_older)


def test_drops_memories_from_the_back_when_messages_are_gone():
    memories = [memory(i, f"{i:02d}" + "기" * 300) for i in range(20)]
    fitted = fit(memories=memories)
    assert fitted.estimated_input_tokens <= BUDGET
    assert fitted.dropped["memories"] >= 1
    assert fitted.memories == memories[:len(fitted.memories)]
    assert fitted.dropped["memories"] == 20 - len(fitted.memories)
    assert fitted.dropped["messages"] == 0
    assert fitted.truncated == []


def long_case(with_summary=True):
    question = "질문머리" + "가" * 7992 + "질문꼬리"
    messages = [msg("user" if i % 2 == 0 else "assistant", f"{i:02d}" + "나" * 7998) for i in range(40)]
    summary = {"id": "s_9", "scope": {"kind": "session", "sessionId": 42}, "text": "요약" + "다" * 3998}
    return dict(
        question=question,
        messages=messages,
        summary=summary if with_summary else None,
        memories=[memory(i, f"{i:02d}" + "기" * 98) for i in range(20)],
        materials=[material(i, f"{i}" + "자" * 1499) for i in range(3)],
        screen_summary="화면" + "라" * 3998,
    )


# AC-21: 8000 chars x 40 + 3 materials + 20 memories + summary.
def test_long_conversation_fits_budget_and_keeps_core_context():
    case = long_case()
    fitted = fit(**case)

    assert fitted.estimated_input_tokens <= BUDGET
    # current question: head and tail kept, at most 500 estimated tokens
    assert fitted.question.startswith("질문머리")
    assert fitted.question.endswith("질문꼬리")
    assert estimate_tokens(fitted.question) <= 500
    assert "question" in fitted.truncated
    # protected window: all six kept in order (shortened), none removed
    protected = fitted.messages[-6:]
    assert [m["role"] for m in protected] == [m["role"] for m in case["messages"][-6:]]
    for kept, original in zip(protected, case["messages"][-6:]):
        assert original["content"].startswith(kept["content"][:2])
        assert kept["content"][:2] == original["content"][:2]
    assert sum(estimate_tokens(m["content"]) for m in protected) <= 800
    assert "protected" in fitted.truncated
    # every older message dropped (each one alone is ~8000 tokens)
    assert len(fitted.messages) == 6
    assert fitted.dropped["messages"] == 34
    # summary kept (shortened), so no first-user pin
    assert fitted.summary is not None
    assert fitted.summary["id"] == "s_9"
    assert fitted.summary["text"].startswith("요약")
    assert estimate_tokens(fitted.summary["text"]) <= 250
    assert fitted.pin is None
    # memories dropped from the back, materials keep at least the best one
    assert fitted.memories == case["memories"][:len(fitted.memories)]
    assert fitted.dropped["memories"] == 20 - len(fitted.memories)
    assert 1 <= len(fitted.materials) <= 3
    assert fitted.materials[0]["id"] == "c_0"
    assert fitted.dropped["materials"] == 3 - len(fitted.materials)
    # screen summary shortened to <=200 or removed
    if fitted.screen_summary is None:
        assert fitted.dropped["screenSummary"] == 1
    else:
        assert estimate_tokens(fitted.screen_summary) <= 200
        assert "screenSummary" in fitted.truncated
    assert set(fitted.truncated) <= {"question", "protected", "summary", "pin", "screenSummary", "materials"}


def test_long_conversation_without_summary_pins_first_user_message():
    case = long_case(with_summary=False)
    first_user = case["messages"][0]["content"]
    fitted = fit(**case)
    assert fitted.estimated_input_tokens <= BUDGET
    assert fitted.summary is None
    assert fitted.pin is not None
    assert first_user.startswith(fitted.pin[:2]) and fitted.pin[:2] == first_user[:2]
    assert estimate_tokens(fitted.pin) <= 150


def test_pin_is_not_used_when_conversation_fits():
    messages = [msg("user", "첫 질문"), msg("assistant", "첫 답")]
    fitted = fit(messages=messages)
    assert fitted.messages == messages


# AC-23 (FastAPI): only request memories/summary are used.
def test_fitted_memories_are_a_subset_of_input():
    memories = [memory(i, "기억" + str(i)) for i in range(20)]
    fitted = fit(memories=memories)
    assert {m["id"] for m in fitted.memories} <= {m["id"] for m in memories}
