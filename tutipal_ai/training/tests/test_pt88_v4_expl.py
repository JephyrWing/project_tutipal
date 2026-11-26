"""PT-88 v4 설명(reason·feedback) 규칙 테스트: AC-5(설명 규칙), AC-3(expl:glossary), AC-6(작성 프롬프트).

v3 사람 검수 fixture 행(tests/fixtures/pt88_v3_review_regression.csv)을 그대로 쓴다.

호출 형태(v4에서 정함):
  Q.expl_check(row, label, kind=None)                       — vague_missing, wrong_affirmed 등 설명 규칙
  BC.check(o, label, expl=False, kind=None, en="")          — v2 작성·조립 검사. en 은 그 행의 영어 원문(문제·모범 답안·
                                                              학생 답안을 줄바꿈으로 이은 것). expl:foreign 이 원문 낱말을 허용할 때 쓴다.
규칙 이름: 'expl:vague_missing', 'expl:wrong_affirmed', 'expl:foreign', 'expl:glossary:<영어 용어>'.
"""

import hashlib

import pytest
from _v4_data import by_short_id, en_of, label_of, o_of, tr_of, x_of

from data_tools import build_grading_c as BC
from data_tools import build_grading_train as B
from data_tools import compare_hybrid as H
from data_tools import quality as Q

ROWS = by_short_id()
NEW_EXPL_RULES = {"expl:vague_missing", "expl:wrong_affirmed", "expl:foreign"}
V3_WRITE_HASH = "50e339f3"  # derived/grading_v3/stats.json prompt_hash.write
V1_WRITER_SYSTEM_SHA256 = "9f8df47891e7bffea5e6c450b0b31f8a3c1bd38c6e3703e0a765d12e5b3c1d03"


def _check(r, **override):
    return BC.check(o_of(r, **override), label_of(r), expl=True, kind=r["kind"], en=en_of(r))


# ---------- AC-5: expl:vague_missing (MohlerASAG-E03.Q07.A29) ----------

def test_vague_missing_detected_for_e03_q07_a29():
    r = ROWS["E03.Q07.A29"]
    assert Q.expl_check(o_of(r), False, kind="partial") == "expl:vague_missing"
    assert _check(r) == "expl:vague_missing"


def test_reason_naming_model_answer_point_passes_vague_missing():
    r = ROWS["E03.Q07.A29"]
    row = o_of(r, reason="두 경우 모두 무한히 반복될 수 있다는 공통점을 짚지 못했습니다.",
               feedback="두 경우 모두 무한히 반복될 수 있는지 생각해 보세요.")
    assert Q.expl_check(row, False, kind="partial") is None


def test_vague_missing_does_not_apply_to_true_rows():
    row = {"question": "재귀 함수란 무엇인가요?", "model_answer": "자기 자신을 호출하는 함수.",
           "student_answer": "기저 조건까지 자기 자신을 호출하는 함수입니다.",
           "reason": "모범 답안과 같은 뜻을 담고 있다.", "feedback": "좋아요. 계속 이렇게 해 보세요."}
    assert Q.expl_check(row, True, kind="paraphrase") is None


def test_no_answer_template_row_passes_new_rules():
    r = ROWS["E07.Q07.A09"]
    o = o_of(r, student_answer="답 없음", reason=Q.NO_ANSWER_REASON, feedback=Q.NO_ANSWER_FEEDBACK)
    assert BC.check(o, False, expl=True, kind="unknown", en="Not answered.") is None


# ---------- AC-5: expl:wrong_affirmed (MohlerASAG-E11.Q03.A12) ----------

def test_wrong_affirmed_detected_for_e11_q03_a12():
    r = ROWS["E11.Q03.A12"]
    assert r["kind"] == "wrong"
    assert Q.expl_check(o_of(r), False, kind="wrong") == "expl:wrong_affirmed"
    assert _check(r) == "expl:wrong_affirmed"


def test_same_text_on_partial_row_is_not_wrong_affirmed():
    """부분 정답(partial)은 맞은 부분을 인정해도 된다."""
    r = ROWS["E11.Q03.A12"]
    assert Q.expl_check(o_of(r), False, kind="partial") is None


@pytest.mark.parametrize("cue", [
    "변수 초기화에 대한 설명은 맞습니다.",
    "변수 설명은 맞지만 생성자가 없다.",
    "변수 설명은 옳은 내용이다.",
    "변수 설명은 정확합니다.",
])
def test_wrong_affirmed_detects_each_positive_cue(cue):
    r = ROWS["E11.Q03.A12"]
    row = o_of(r, reason=f"{cue} 모범 답안의 핵심인 생성자를 쓰지 않았다.", feedback="생성자를 다시 확인해 보세요.")
    assert Q.expl_check(row, False, kind="wrong") == "expl:wrong_affirmed"


def test_wrong_row_without_positive_cue_passes():
    r = ROWS["E11.Q03.A12"]
    row = o_of(r, reason="객체 초기화의 핵심인 생성자를 쓰지 않았다.", feedback="생성자를 다시 확인해 보세요.")
    assert Q.expl_check(row, False, kind="wrong") is None


# ---------- AC-5: expl:foreign (MohlerASAG-E01.Q02.A08 feedback 잔해) ----------

def test_foreign_detected_for_e01_q02_a08_feedback():
    r = ROWS["E01.Q02.A08"]
    assert r["feedback"].rstrip().endswith("slovnost:")
    assert _check(r) == "expl:foreign"


@pytest.mark.parametrize("field, tail", [
    ("feedback", '"}'),
    ("feedback", " {"),
    ("reason", " pyeong 단위로 말하면 공간이 더 필요하다."),
])
def test_foreign_detects_json_debris_and_unknown_latin_words(field, tail):
    r = ROWS["E07.Q07.A09"]
    assert _check(r) is None  # 바탕 행은 통과한다
    assert _check(r, **{field: r[field] + tail}) == "expl:foreign"


@pytest.mark.parametrize("extra", [
    " 즉 back pointers 를 둘 공간입니다.",   # 영어 원문에 있는 낱말
    " 이는 FIFO 구조와는 다른 이야기입니다.",  # 약어 허용 목록
])
def test_foreign_allows_source_words_and_known_acronyms(extra):
    r = ROWS["E07.Q07.A09"]
    assert _check(r, reason=r["reason"] + extra) is None


def test_foreign_allows_code_identifier_from_translated_answer():
    r = ROWS["E02.Q03.A05"]
    reason = r["reason"] + " 예시의 Employee 객체도 생성자로 만든다."
    assert _check(r, reason=reason) not in NEW_EXPL_RULES


# ---------- AC-5: MohlerASAG-E01.Q07.A19 는 통과(중요도 판단은 자동 검출 불가) ----------

def test_e01_q07_a19_passes_all_rules_as_known_limit():
    r = ROWS["E01.Q07.A19"]
    assert BC.trans_error(x_of(r), tr_of(r)) is None
    assert _check(r) is None


# ---------- 사람 검수 OK 였던 오답 행은 새 설명 규칙에 걸리지 않는다 ----------

@pytest.mark.parametrize("sid", ["E02.Q03.A05", "E07.Q07.A09", "E08.Q05.A03", "7405e5a7"])
def test_v3_ok_rows_pass_new_explanation_rules(sid):
    assert _check(ROWS[sid]) not in NEW_EXPL_RULES


# ---------- AC-3: 설명에 금지 표기 → expl:glossary:<용어> ----------

def test_forbidden_term_in_reason_fails_for_e10_q06_a16():
    """MohlerASAG-E10.Q06.A16: reason·feedback 에 '인오더 트래버설'."""
    r = ROWS["E10.Q06.A16"]
    rule = _check(r)
    assert rule in {"expl:glossary:inorder", "expl:glossary:traversal", "expl:glossary:traverse"}


def test_forbidden_term_in_feedback_fails():
    r = ROWS["E04.Q05.A28"]
    rule = _check(r, model_answer="첫 번째를 제외한 모든 차원.",
                  feedback="정답이에요. 첫 번째 치수는 명시하지 않는다는 점까지 짚으면 더 좋아요.")
    assert rule == "expl:glossary:dimension"


def test_forbidden_form_of_term_absent_from_english_source_passes():
    """강지 판정 3: 금지형은 그 행 영어 원문(en)에 있는 용어만 검사한다. E07.Q07.A09 원문에는 argument 가 없다."""
    r = ROWS["E07.Q07.A09"]
    assert "argument" not in en_of(r).lower()
    assert _check(r, reason=r["reason"] + " 학생의 주장은 노드 관리가 번거롭다는 것이다.") is None


def test_forbidden_form_of_term_in_english_source_fails():
    """E04.Q05.A07 원문 문제에 'argument' 가 있어 reason 의 '주장' 은 금지형이다."""
    r = ROWS["E04.Q05.A07"]
    assert "argument" in en_of(r)
    assert _check(r) is None  # 바탕 행은 통과한다
    assert _check(r, reason=r["reason"] + " 학생의 주장은 첫 번째 차원만 명시하면 된다는 것이다.") == \
        "expl:glossary:argument"


# ---------- expl:foreign: 대소문자 무시, JSON 잔해는 원문과 무관하게 실패 ----------

def test_foreign_compares_source_words_case_insensitively():
    r = ROWS["E07.Q07.A09"]
    assert _check(r, reason=r["reason"] + " 즉 Back Pointers 를 둘 공간입니다.") is None


@pytest.mark.parametrize("tail, en_extra", [
    ('"}', '\nprint({"a": 1"})'),
    (" {", "\nint a[]={0,1};"),
])
def test_json_debris_fails_even_if_english_source_contains_braces(tail, en_extra):
    r = ROWS["E07.Q07.A09"]
    o = o_of(r, feedback=r["feedback"] + tail)
    assert BC.check(o, False, expl=True, kind=r["kind"], en=en_of(r) + en_extra) == "expl:foreign"


# ---------- v1 경로는 새 규칙을 쓰지 않는다 ----------

def test_v1_check_does_not_apply_new_rules():
    r = ROWS["E11.Q03.A12"]
    assert BC.check(o_of(r), False) is None


# ---------- AC-6: 작성 프롬프트 보강(해시 변경·문구) ----------

def test_writer_prompt_hash_changed_from_v3():
    assert BC.writer_prompt_hash() != V3_WRITE_HASH


@pytest.mark.parametrize("phrase", ["구체적", "힌트", "틀린 주장", "단정", "사소한"])
def test_writer_system_v2_has_new_instructions(phrase):
    assert phrase in H.WRITER_SYSTEM_V2


def test_v1_writer_system_and_kind_desc_unchanged():
    assert hashlib.sha256(H.WRITER_SYSTEM.encode()).hexdigest() == V1_WRITER_SYSTEM_SHA256
    assert B.KIND_DESC["wrong"] == "오답 (틀린 개념이거나 질문과 무관)"
    assert "<hint>" in H.WRITER_SYSTEM_V2
