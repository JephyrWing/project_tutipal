"""data_tools 생성 스크립트의 LLM 밖 순수 함수 테스트(validate·unify·record·sample·check).

LLM·네트워크는 부르지 않는다(conftest 가 막는다). 데이터는 모두 합성이다.
"""

import json
from collections import Counter

import pytest

from data_tools import build_grading_c as BC
from data_tools import build_grading_train as B
from eval.run_eval import GRADE_SYSTEM


def good_output(**kw):
    o = {
        "question": "식물은 왜 햇빛이 필요한가요?",
        "model_answer": "식물은 광합성으로 양분을 만들 때 햇빛을 이용한다.",
        "student_answer": "식물이 따뜻한 걸 좋아해서",
        "reason": "햇빛이 광합성에 쓰인다는 핵심 대신 따뜻함을 이유로 들어 틀렸다.",
        "feedback": "식물이 햇빛으로 무엇을 만드는지 떠올려 보세요.",
    }
    o.update(kw)
    return o


# ---------- validate ----------

def test_validate_accepts_good_output():
    assert B.validate(good_output(), False) is None


@pytest.mark.parametrize("obj,label,expected", [
    pytest.param({k: v for k, v in good_output().items() if k != "reason"}, False, "missing field", id="missing"),
    pytest.param(good_output(feedback="   "), False, "missing field", id="blank"),
    pytest.param("not a dict", False, "missing field", id="not_dict"),
    pytest.param(good_output(reason="가" * 301), False, "reason too long", id="too_long"),
    pytest.param(good_output(question="식물은 왜 光合成 이 필요한가요?"), False, "question has han", id="han"),
    pytest.param(good_output(student_answer="because plants like being warm"), False,
                 "student_answer not korean", id="english"),
    pytest.param(good_output(reason="핵심이 빠져 오답이다."), True, "reason contradicts label(true)", id="true_neg"),
    pytest.param(good_output(reason="모범 답안과 같아 정답이다."), False, "reason contradicts label(false)",
                 id="false_pos"),
    pytest.param(good_output(feedback="맞았어요! 다음에도 이렇게 써 보세요."), False,
                 "feedback contradicts label(false)", id="false_feedback_pos"),
])
def test_validate_rejects(obj, label, expected):
    assert B.validate(obj, label) == expected


# ---------- unify ----------

def test_unify_renames_ko_fields():
    o = {"question_ko": "질문", "model_answer_ko": "모범", "student_answer_ko": "답", "reason": "r", "feedback": "f"}
    assert B.unify(o) == {"question": "질문", "model_answer": "모범", "student_answer": "답",
                          "reason": "r", "feedback": "f"}


def test_unify_passes_through_non_dict_and_plain_fields():
    assert B.unify(None) is None
    plain = {"question": "질문", "model_answer": "모범", "student_answer": "답"}
    assert B.unify(dict(plain)) == plain


# ---------- record ----------

@pytest.mark.parametrize("label", [True, False])
def test_record_builds_training_row(label):
    r = B.record("SYN-1", {"source": "Synthetic", "source_id": "1"}, "문제", "모범", "학생 답", label,
                 "paraphrase" if label else "wrong", "이유 문장", "피드백 문장", "과학")
    assert {k: r[k] for k in ("id", "source", "source_id", "subject", "question", "model_answer",
                              "student_answer", "label", "kind", "reason", "feedback")} == {
        "id": "SYN-1", "source": "Synthetic", "source_id": "1", "subject": "과학", "question": "문제",
        "model_answer": "모범", "student_answer": "학생 답", "label": label,
        "kind": "paraphrase" if label else "wrong", "reason": "이유 문장", "feedback": "피드백 문장"}
    assert [m["role"] for m in r["messages"]] == ["system", "user", "assistant"]
    assert r["messages"][0]["content"] == GRADE_SYSTEM
    assert r["messages"][1]["content"] == ("<question>문제</question>\n<model_answer>모범</model_answer>\n"
                                           "<student_answer>학생 답</student_answer>")
    assert json.loads(r["messages"][2]["content"]) == {"reason": "이유 문장", "correct": label,
                                                       "feedback": "피드백 문장"}


# ---------- sample ----------

def _items():
    out = []
    for kind in ("paraphrase", "partial", "wrong", "unknown"):
        for i in range(100):
            src = "SAF-communication-networks" if i % 3 == 0 else "SciEntsBank"
            out.append({"source": src, "source_id": f"{kind}-{i}", "kind": kind})
    return out


def test_sample_follows_kind_quota():
    picked = B.sample(_items(), 20, seed=1)
    assert Counter(x["kind"] for x in picked) == {"paraphrase": 8, "partial": 5, "wrong": 6, "unknown": 1}
    assert len({x["source_id"] for x in picked}) == 20


def test_sample_is_deterministic_for_same_seed():
    a = [x["source_id"] for x in B.sample(_items(), 40, seed=3)]
    b = [x["source_id"] for x in B.sample(_items(), 40, seed=3)]
    assert a == b


def test_sample_takes_what_exists_when_bucket_is_small():
    items = [x for x in _items() if x["kind"] != "unknown" or x["source_id"] == "unknown-0"]
    picked = B.sample(items, 100, seed=1)
    assert Counter(x["kind"] for x in picked)["unknown"] == 1


# ---------- build_grading_c.check ----------

def test_check_rejects_praise_first_sentence_for_wrong_answer():
    assert BC.check(good_output(feedback="네, 좋은 시도예요. 광합성을 떠올려 보세요."), False) == \
        "feedback praises wrong answer"


def test_check_accepts_hint_feedback_for_wrong_answer():
    assert BC.check(good_output(), False) is None


def test_check_returns_validate_error_first():
    assert BC.check(good_output(question="光合成"), False) == "question has han"
