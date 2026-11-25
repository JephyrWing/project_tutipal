"""PT-85 AC-1, AC-2, AC-4, AC-22: rule-based route, hint level and help suggestion (no LLM)."""
import pytest

from app.services.agent.classify import HELP_SUGGESTION_MARKER, PROTECTED_WINDOW, classify, decide


def user(text):
    return {"role": "user", "content": text}


def bot(text="다음 힌트를 볼까요?"):
    return {"role": "assistant", "content": text}


# AC-1: at least 16 Korean cases across the four routes.
@pytest.mark.parametrize("question,route", [
    ("```python\nfor i in range(5)\n    print(i)\n```\n이거 왜 안 돼요?", "code_debug"),
    ("Traceback (most recent call last):\n  File \"main.py\", line 3, in <module>", "code_debug"),
    ("NameError 에러가 나요", "code_debug"),
    ("실행하면 오류가 떠요", "code_debug"),
    ("12번 줄에서 자꾸 멈춰요", "code_debug"),
    ("3번째 줄이 이상해요", "code_debug"),
    ("제 답은 120이에요", "answer_check"),
    ("답이 3이라고 생각하는데 맞나요?", "answer_check"),
    ("저는 이렇게 했는데 괜찮을까요", "answer_check"),
    ("정답 알려줘", "answer_request"),
    ("정답 알려주세요", "answer_request"),
    ("이 문제 풀어줘", "answer_request"),
    ("반복문 코드 짜줘", "answer_request"),
    ("for문에서 i가 왜 4까지만 나와요? 정답 코드 알려줘", "answer_request"),
    ("range 함수가 뭐예요?", "concept"),
    ("재귀랑 반복문 차이가 뭔가요", "concept"),
    ("포인터는 왜 쓰나요?", "concept"),
    ("리스트 컴프리헨션 설명해 줘", "concept"),
])
def test_classify_korean_questions(question, route):
    assert classify(question) == route


def test_code_debug_wins_over_answer_request():
    assert classify("Traceback (most recent call last):\nValueError\n정답 알려줘") == "code_debug"


def test_answer_check_wins_over_answer_request():
    assert classify("제 답은 5인데 맞나요? 아니면 정답 알려줘") == "answer_check"


def test_protected_window_is_six_messages():
    assert PROTECTED_WINDOW == 6


# AC-2: hintLevel = min(3, 1 + n), n = consecutive stuck/answer-request user turns inside the last 6 messages.
@pytest.mark.parametrize("messages,level", [
    ([], 1),
    ([user("range가 뭐예요?"), bot()], 1),
    ([user("정답 알려줘"), bot()], 2),
    ([user("모르겠어요"), bot(), user("정답 알려줘"), bot()], 3),
    ([user("모르겠어요"), bot(), user("막혔어요"), bot(), user("정답 알려줘"), bot()], 3),
    ([user("모르겠어요"), bot(), user("range가 뭐예요?"), bot()], 1),
])
def test_hint_level_counts_recent_stuck_streak(messages, level):
    decision = decide("student", "정답 알려줘", messages)
    assert decision.hint_level == level


def test_hint_level_ignores_messages_outside_protected_window():
    old_stuck = [user("모르겠어요"), bot(), user("정답 알려줘"), bot()]
    recent = [user("range가 뭐예요?"), bot(), user("print는요?"), bot(), user("len은요?"), bot()]
    assert decide("student", "정답 알려줘", old_stuck + recent).hint_level == 1


# AC-4: help suggestion only for student.
def test_suggests_help_at_hint_level_three_when_still_stuck():
    history = [user("모르겠어요"), bot(), user("정답 알려줘"), bot()]
    decision = decide("student", "그래도 모르겠어요", history)
    assert decision.hint_level == 3
    assert decision.suggest_help is True


def test_no_help_at_level_three_when_question_is_conceptual():
    history = [user("모르겠어요"), bot(), user("정답 알려줘"), bot()]
    assert decide("student", "range 함수가 뭐예요?", history).suggest_help is False


def test_no_help_below_level_three():
    assert decide("student", "정답 알려줘", [user("모르겠어요"), bot()]).suggest_help is False


def test_suggests_help_on_third_code_debug_in_window():
    history = [user("실행하면 오류가 떠요"), bot(), user("NameError 에러가 나요"), bot()]
    decision = decide("student", "12번 줄에서 또 에러가 나요", history)
    assert decision.route == "code_debug"
    assert decision.suggest_help is True


def test_no_help_on_second_code_debug():
    decision = decide("student", "12번 줄에서 또 에러가 나요", [user("실행하면 오류가 떠요"), bot()])
    assert decision.suggest_help is False


def test_no_help_when_previous_assistant_already_suggested_it():
    history = [user("모르겠어요"), bot(), user("정답 알려줘"), bot("힌트를 더 줄게요. " + HELP_SUGGESTION_MARKER)]
    assert decide("student", "그래도 모르겠어요", history).suggest_help is False


def test_help_marker_text_is_the_teacher_help_question():
    # Spring AgentRequests.HELP_SUGGESTION contains this exact text, so it shows up in the next turn's history.
    assert HELP_SUGGESTION_MARKER == "선생님께 도움 요청할래요?"


def test_personal_never_suggests_help_and_has_no_hint_level():
    history = [user("모르겠어요"), bot(), user("정답 알려줘"), bot()]
    decision = decide("personal", "그래도 모르겠어요", history)
    assert decision.suggest_help is False
    assert decision.hint_level is None


# AC-22: only question + last 6 raw messages matter.
@pytest.mark.parametrize("prefix", [
    [],
    [user("모르겠어요"), bot()] * 17,
    [user("실행하면 오류가 떠요"), bot()] * 17,
    [user("가" * 8000), bot("나" * 8000)] * 17,
])
def test_decision_depends_only_on_protected_window(prefix):
    window = [user("모르겠어요"), bot(), user("정답 알려줘"), bot(), user("막혔어요"), bot()]
    baseline = decide("student", "정답 알려줘", window)
    decision = decide("student", "정답 알려줘", prefix + window)
    assert (decision.route, decision.hint_level, decision.suggest_help) == \
        (baseline.route, baseline.hint_level, baseline.suggest_help)
