"""PT-85 demo bug: a stuck turn routed to concept must still carry its hint-level rule.

Found with gemma3:4b: "여전히 모르겠어요. 막혔어요" got hintLevel=2 but no HINT_RULES[2] in the system prompt.
"""
import pytest

from agent_support import meta_of, run, student_body
from app.services.agent import prompts
from app.services.agent.classify import decide

pytestmark = pytest.mark.anyio


def user(text: str) -> dict:
    return {"role": "user", "content": text}


def bot(text: str = "힌트를 드릴게요.") -> dict:
    return {"role": "assistant", "content": text}


TURN1 = "for문으로 합계를 어떻게 구해요?"
TURN2 = "모르겠어요, 그냥 1부터 10까지 더하는 코드 짜 주세요"
TURN3 = "여전히 모르겠어요. 막혔어요"


def _hint_rules_in(system: str) -> list[int]:
    return [level for level, rule in prompts.HINT_RULES.items() if rule in system]


async def _turn(monkeypatch, question: str, history: list[dict]):
    body = student_body(question=question)
    body["context"]["messages"] = history
    events, llm, _ = await run(body, monkeypatch)
    return meta_of(events), llm.systems[0]


async def test_demo_turn2_answer_request_gets_level_1(monkeypatch):
    meta, system = await _turn(monkeypatch, TURN2, [user(TURN1), bot()])
    assert meta["route"] == "answer_request"
    assert meta["hintLevel"] == 1
    assert _hint_rules_in(system) == [1]


async def test_demo_turn3_stuck_concept_gets_level_2_rule(monkeypatch):
    meta, system = await _turn(monkeypatch, TURN3, [user(TURN1), bot(), user(TURN2), bot()])
    assert meta["route"] == "concept"
    assert meta["hintLevel"] == 2
    assert _hint_rules_in(system) == [2]
    assert prompts.HIDE_ANSWERS_RULE in system


async def test_plain_concept_first_question_has_no_hint_rule(monkeypatch):
    meta, system = await _turn(monkeypatch, "for문이 뭐예요?", [])
    assert meta["route"] == "concept"
    assert meta["hintLevel"] == 1
    assert _hint_rules_in(system) == []
    assert prompts.HIDE_ANSWERS_RULE in system


async def test_stuck_three_times_reaches_level_3_and_suggests_help(monkeypatch):
    history: list[dict] = []
    seen = []
    for _ in range(3):
        meta, system = await _turn(monkeypatch, "막혔어요", history)
        seen.append((meta["route"], meta["hintLevel"], meta["suggestHelp"], _hint_rules_in(system)))
        history = history + [user("막혔어요"), bot()]
    assert seen == [
        ("concept", 1, False, [1]),
        ("concept", 2, False, [2]),
        ("concept", 3, True, [3]),
    ]


async def test_stuck_turn_without_hide_answers_has_no_hint_rule(monkeypatch):
    body = student_body(question=TURN3)
    body["policy"]["hideAnswers"] = False
    body["context"]["messages"] = [user(TURN1), bot(), user(TURN2), bot()]
    _, llm, _ = await run(body, monkeypatch)
    assert _hint_rules_in(llm.systems[0]) == []


def test_decide_marks_stuck_from_the_current_question_only():
    assert decide("student", TURN3, []).stuck is True
    assert decide("student", "정답 알려줘", []).stuck is True
    assert decide("student", "for문이 뭐예요?", [user("모르겠어요"), bot()]).stuck is False
    assert decide("personal", TURN3, []).stuck is False


@pytest.mark.parametrize("route,stuck,expected", [
    ("concept", False, []),
    ("concept", True, [2]),
    ("answer_request", False, [2]),
    ("answer_check", False, [2]),
    ("code_debug", False, [2]),
])
def test_build_instructions_hint_rule_condition(route, stuck, expected):
    text = prompts.build_instructions(agent="student", topic="반복문", difficulty=2, hide_answers=True,
                                      route=route, hint_level=2, stuck=stuck)
    assert _hint_rules_in(text) == expected
