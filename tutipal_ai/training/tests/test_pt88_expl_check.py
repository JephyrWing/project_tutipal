"""PT-88 설명(이유·피드백) 검사 테스트: data_tools.quality.expl_check 와 build_grading_c.check 연결.

수용 기준 12(이미 쓴 내용을 빠졌다고 함), 13(근거 없는 수량·비교 표현), 14(정답인데 누락·오류 표현).
row 는 작성 결과와 같은 dict(question, model_answer, student_answer, reason, feedback) — 모두 한국어.
"""

import pytest

from data_tools import build_grading_c as BC
from data_tools import quality as Q


def _row(**kw):
    base = {"question": "배열로 스택을 어떻게 구현하나요?",
            "model_answer": "스택의 맨 위를 배열 끝에 두고 푸시와 팝을 오른쪽 끝에서 수행한다.",
            "student_answer": "배열 크기를 미리 정해 두고 크기를 늘릴 수 없습니다.",
            "reason": "푸시와 팝을 배열 끝에서 수행한다는 핵심을 설명하지 못했다.",
            "feedback": "스택의 맨 위가 배열의 어디에 있는지 다시 생각해 보세요."}
    return {**base, **kw}


# ---------- 수용 기준 12: 이미 쓴 내용을 빠졌다고 함 (오답 행) ----------

ALREADY_SAID = "배열 크기를 미리 정해 두고 크기를 늘릴 수 없다는 점이 빠졌습니다."


@pytest.mark.parametrize("field", ["reason", "feedback"])
def test_already_present_content_called_missing_fails(field):
    assert Q.expl_check(_row(**{field: ALREADY_SAID}), False) == "expl:already_present"


@pytest.mark.parametrize("cue_sentence", [
    "배열 크기를 미리 정해 두고 크기를 늘릴 수 없다는 내용이 누락되었습니다.",
    "배열 크기를 미리 정해 두고 크기를 늘릴 수 없다는 점을 언급하지 않았습니다.",
    "배열 크기를 미리 정해 두고 크기를 늘릴 수 없다는 점이 포함되지 않았습니다.",
])
def test_already_present_detects_each_missing_cue(cue_sentence):
    assert Q.expl_check(_row(reason=cue_sentence), False) == "expl:already_present"


def test_missing_claim_about_absent_content_passes():
    row = _row(reason="푸시와 팝을 배열 오른쪽 끝에서 수행한다는 점이 빠졌습니다.",
               student_answer="스택은 데이터를 쌓아 두는 구조입니다.")
    assert Q.expl_check(row, False) is None


def test_clean_false_row_passes():
    assert Q.expl_check(_row(), False) is None


# ---------- 수용 기준 13: 근거 없는 수량·비교 표현 ----------

BST = {"question": "이진 검색 트리에서 노드를 찾는 단계 수는?",
       "model_answer": "트리의 높이.",
       "student_answer": "루트에서 시작해 작으면 왼쪽, 크면 오른쪽으로 이동합니다.",
       "reason": "단계 수를 직접 답하지 않았다."}


def test_unsupported_quantity_in_feedback_fails():
    row = {**BST, "feedback": "단계 수는 최대 log2(n) 단계입니다. 높이를 떠올려 보세요."}
    assert Q.expl_check(row, False) == "expl:unsupported_quantity"


def test_unsupported_number_in_reason_fails():
    row = {**BST, "reason": "단계 수가 3단계라는 점을 답하지 않았다.", "feedback": "트리 높이를 떠올려 보세요."}
    assert Q.expl_check(row, False) == "expl:unsupported_quantity"


def test_quantity_supported_by_model_answer_passes():
    row = {**BST, "model_answer": "균형 트리에서는 최대 log2(n) 단계가 걸린다.",
           "feedback": "단계 수는 최대 log2(n) 단계입니다. 높이를 떠올려 보세요."}
    assert Q.expl_check(row, False) is None


def test_quantity_supported_by_student_answer_passes():
    row = {**BST, "student_answer": "노드가 3개면 3단계 이동합니다.",
           "feedback": "3단계라는 답은 특정 트리에만 맞아요. 높이와 연결해 보세요."}
    assert Q.expl_check(row, False) is None


def test_unsupported_quantity_applies_to_true_rows_too():
    row = {**BST, "student_answer": "트리의 높이만큼 걸립니다.", "reason": "모범 답안과 같은 뜻이다.",
           "feedback": "맞았어요. 최대 log2(n) 단계라는 점도 기억해 두세요."}
    assert Q.expl_check(row, True) == "expl:unsupported_quantity"


# ---------- 수용 기준 14: 정답 행인데 누락·오류 표현 ----------

TRUE_ROW = {"question": "재귀 함수란 무엇인가요?", "model_answer": "자기 자신을 호출하는 함수.",
            "student_answer": "기저 조건까지 자기 자신을 호출하는 함수입니다.",
            "reason": "모범 답안과 같은 뜻을 담고 있다.",
            "feedback": "맞았어요. 기저 조건까지 짚어 주셨네요."}


def test_clean_true_row_passes():
    assert Q.expl_check(TRUE_ROW, True) is None


@pytest.mark.parametrize("field, text", [
    ("reason", "핵심을 담았지만 예외 처리가 누락되었다."),
    ("reason", "핵심을 담았지만 설명이 빠졌다."),
    ("feedback", "맞았어요. 다만 예외 처리 설명이 부족해요."),
    ("feedback", "맞았어요. 다만 용어를 잘못 썼어요."),
    ("feedback", "맞았어요. 다만 일부는 틀렸어요."),
])
def test_true_row_with_missing_or_error_cue_fails(field, text):
    assert Q.expl_check({**TRUE_ROW, field: text}, True) == "expl:label_contradiction"


def test_false_row_missing_cue_is_not_label_contradiction():
    row = _row(reason="푸시와 팝을 배열 오른쪽 끝에서 수행한다는 점이 누락되었다.",
               student_answer="스택은 데이터를 쌓아 두는 구조입니다.")
    assert Q.expl_check(row, False) is None


# ---------- build_grading_c.check 가 새 검사를 쓴다(작성 2회 재시도·조립 재검사에 그대로 적용) ----------

def test_check_reports_already_present_for_false_row():
    assert BC.check(_row(reason=ALREADY_SAID), False, expl=True) == "expl:already_present"


def test_check_reports_unsupported_quantity():
    row = {**BST, "feedback": "단계 수는 최대 log2(n) 단계입니다. 높이를 떠올려 보세요."}
    assert BC.check(row, False, expl=True) == "expl:unsupported_quantity"


def test_check_reports_label_contradiction_not_covered_by_validate():
    row = {**TRUE_ROW, "reason": "핵심을 담았지만 예외 처리가 누락되었다."}
    assert BC.check(row, True, expl=True) == "expl:label_contradiction"


def test_check_keeps_existing_praise_rule():
    row = _row(feedback="훌륭해요. 다만 맨 위 위치를 다시 생각해 보세요.")
    assert BC.check(row, False) == "feedback praises wrong answer"


def test_check_passes_clean_rows():
    assert BC.check(_row(), False) is None
    assert BC.check(TRUE_ROW, True) is None


def test_v1_check_keeps_old_rules_without_expl_check():
    """v1 경로(기본 expl=False)는 설명 검사를 하지 않아 v1 결과가 예전과 같다(수용 기준 'v1 output unchanged')."""
    assert BC.check(_row(reason=ALREADY_SAID), False) is None
    row = {**TRUE_ROW, "reason": "핵심을 담았지만 예외 처리가 누락되었다."}
    assert BC.check(row, True) == BC.check(row, True, expl=False)
    assert BC.check(_row(feedback="훌륭해요. 다만 맨 위 위치를 다시 생각해 보세요."), False) == \
        "feedback praises wrong answer"  # 예전 규칙은 그대로


def test_science_selection_ignores_new_expl_rules():
    """과학 행 선별(_ok_cached)은 예전 키 캐시가 있는지만 본다. 새 설명 규칙에 걸리는 예전 설명이어도 고른다."""
    import argparse
    x = {"source": "SciEntsBank", "source_id": "SC.9", "label": False}
    a = argparse.Namespace(translator="t", writer="w", seed=BC.DEFAULT_SEED, v2=True)
    tcache = {BC.old_tkey(x, a): {"out": {}, "error": None}}
    wcache = {BC.old_wkey(x, a): {"out": _row(reason=ALREADY_SAID), "error": None}}
    assert BC.check(wcache[BC.old_wkey(x, a)]["out"], False, expl=True) == "expl:already_present"
    assert BC._ok_cached(x, a, tcache, wcache)
