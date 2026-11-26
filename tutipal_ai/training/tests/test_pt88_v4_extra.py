"""PT-88 v4 구현 보강 테스트(개발자 추가): 리코 테스트 밖에서 구현이 정한 동작을 고정한다.

- 용어집 매칭 경계: 붙임표 표기('pre-order')는 공백 구문('pre order')에 맞지 않고, SAF 의 'in-order delivery' 는
  inorder 로 보지 않는다.
- trans_check 순서: v4 용어·단위 규칙은 예전 규칙 뒤에 본다(예전 사유 이름 유지).
- expl:glossary: 금지형 뒤에 동사를 만드는 말이 오면('기억해 주세요') 명사 번역이 아니다.
- expl:foreign: 한국어 낱말 뒤 괄호 속 영어 풀이('정적(static)')는 허용, 괄호 밖 낱말·잔해는 실패.
- expl:vague_missing: kind 를 주지 않은 예전 호출에는 적용하지 않는다.
- 텍스트 메모: --cache-from 의 text_cache.jsonl 을 복사해 새 용어가 없는 칸은 다시 번역하지 않는다.
- 고정 번역 시트: 같은 qid 를 두 번 고정하면 종료 2, CRLF 줄바꿈 영어 원문은 같은 원문으로 본다.
"""

import json

import pytest
from _v2_data import MOH, mohler_q, mohler_ref, qid_of
from test_pt88_v4_pin import (
    _argv,
    _exit_code,
    _install,
    _nothing_written,
    _rows_of,
    env,
    fix_q,
    fix_ref,
    pin_row,
    write_pin,
)

from data_tools import build_grading_c as BC
from data_tools import build_grading_train as B
from data_tools import quality as Q

__all__ = ["env"]  # test_pt88_v4_pin 의 fixture 를 그대로 쓴다


# ---------- 용어집 매칭·규칙 순서 ----------

@pytest.mark.parametrize("text", ["in order to finish", "stored in order", "pre order", "post order",
                                  "reliable bidirectional in-order byte stream"])
def test_hyphen_forms_do_not_match_spaced_phrases(text):
    assert not ({"inorder", "preorder", "postorder"} & {e["en"] for e in Q.glossary_for(text)})


@pytest.mark.parametrize("text", ["inorder traversal", "a pre-order walk", "post-order", "base-case check"])
def test_hyphen_and_space_forms_match(text):
    assert Q.glossary_for(text)


def test_old_rule_name_is_reported_before_v4_glossary():
    """'only' 와 baud 둘 다 걸리면 예전 규칙 이름(only)을 먼저 낸다(v3 사유 통계와 비교 가능)."""
    assert Q.trans_check("a full bit per baud instead of only 0.5", "0.5보다 훨씬 더 많은 정보") == "only"
    assert Q.trans_check("a full bit per baud instead of only 0.5", "0.5만이 아니라 신호당 한 비트를 온전히") == "glossary:baud"


def test_old_entries_keep_exact_word_matching():
    """처음 5개 항목도, v4 항목도(Round 1 판정 b) 붙임표로 붙은 말(collision-free)을 용어로 보지 않는다."""
    assert not ({"free", "collision"} & {e["en"] for e in Q.glossary_for("a collision-free channel")})
    assert "collision" in {e["en"] for e in Q.glossary_for("a collision occurred")}


def test_consistency_ignores_free_with_several_senses():
    x = {"q": "Is the memory free?", "ref": "The free list holds free blocks.", "ans": "free means unused"}
    tr = {"question": "메모리가 비어 있는가?", "model_answer": "해제 목록은 사용 가능한 블록을 담는다.",
          "student_answer": "자유롭다는 뜻은 쓰이지 않는다는 것이다"}
    assert BC.trans_error(x, tr) is None


# ---------- 설명 규칙 ----------

def test_expl_glossary_ignores_verb_use_of_forbidden_form():
    texts = ("변수는 메모리에 값을 둔다.", "이 점을 기억해 주세요.")
    assert Q.expl_glossary(texts, "What is stored in memory?") is None
    assert Q.expl_glossary(("값은 기억 장치에 있다.", "좋아요."), "What is stored in memory?") == "memory"
    assert Q.expl_glossary(("학생이 주장했지만 근거가 없다.",), "pass it as an argument") is None


def test_foreign_allows_parenthesized_english_gloss_after_korean():
    texts = ("배열은 값(value)이 아닌 참조로 전달된다.", "스택은 LIFO(Last-In, First-Out) 방식이에요.")
    assert not Q.expl_foreign(texts, "How are arrays passed?")
    assert Q.expl_foreign(("배열은 value 가 아니다.",), "How are arrays passed?")
    assert Q.expl_foreign(('배열은 값(value)이다."}',), "How are arrays passed?")


def test_vague_missing_needs_kind():
    row = {"question": "반복과 재귀의 공통점은?", "model_answer": "둘 다 무한히 반복될 수 있다.",
           "student_answer": "종료 조건이 있다.", "reason": "핵심 내용을 누락했다.", "feedback": "다시 보세요."}
    assert Q.expl_check(row, False) is None
    assert Q.expl_check(row, False, kind="wrong") == "expl:vague_missing"


# ---------- 텍스트 메모 복사·재검사 ----------

def test_text_cache_is_copied_from_cache_from_and_reused(env):
    _install(env)
    BC.main(_argv(env))
    (env["v1"] / BC.TEXT_CACHE).write_text((env["out"] / BC.TEXT_CACHE).read_text(encoding="utf-8"), encoding="utf-8")
    first = json.loads((env["out"] / "stats.json").read_text(encoding="utf-8"))
    assert first["llm_translate_calls"] > 0
    for name in ("train.jsonl", "stats.json", "rejected.jsonl", "trans_cache.jsonl", "write_cache.jsonl",
                 BC.TEXT_CACHE):
        (env["out"] / name).unlink()
    llm = _install(env)
    BC.main(_argv(env))
    assert llm.translated == []  # 행 캐시가 없어도 메모가 모든 원문을 돌려준다
    assert BC.TEXT_CACHE in json.loads((env["out"] / "stats.json").read_text(encoding="utf-8"))["cache_copied"]


# ---------- 고정 번역 시트 ----------

def test_duplicate_pinned_qid_exits_2(env, capsys):
    pin = write_pin(env["tmp"] / "pin.csv", [pin_row(0, fix_q(0), fix_ref(0), "확인함"),
                                             pin_row(0, fix_q(0), fix_ref(0), "확인함")])
    llm = _install(env)
    assert _exit_code(lambda: BC.main(_argv(env, "--pin", str(pin)))) == 2
    assert qid_of(MOH, mohler_q(0)) in capsys.readouterr().err
    assert llm.translated == [] and llm.prompts == []
    _nothing_written(env)


def test_crlf_in_sheet_english_matches_source(env):
    pin = write_pin(env["tmp"] / "pin.csv", [pin_row(0, fix_q(0), fix_ref(0), "확인함",
                                                     ref_en=mohler_ref(0) + "\r\n")])
    _install(env)
    BC.main(_argv(env, "--pin", str(pin)))
    assert all(r["model_answer"] == fix_ref(0) for r in _rows_of(env, 0))


def test_pin_requires_v2(env, capsys):
    pin = write_pin(env["tmp"] / "pin.csv", [pin_row(0, fix_q(0), fix_ref(0))])
    argv = [a for a in _argv(env, "--pin", str(pin)) if a != "--v2"]
    argv = argv[:argv.index("--cache-from")] + argv[argv.index("--cache-from") + 2:]
    assert _exit_code(lambda: BC.main(argv)) == 2
    assert "--pin" in capsys.readouterr().err


def test_ref_pin_applies_only_to_rows_with_same_english_model_answer(env):
    """문제 문장은 같고 모범 답안이 다른 qid(Mohler 실데이터에 2개): 모범 답안 고정값은 같은 영어 모범 답안 행에만."""
    alt_ref = "Zeta structure 0 appends each new element and never reorders earlier elements."
    rel = B.SOURCE_FILES["mohler_open"]
    env["tables"][rel] = list(env["tables"][rel]) + [
        {"id": f"M00.ALT{i}", "question": mohler_q(0), "instructor_answer": alt_ref,
         "student_answer": f"student M00 alt {i} says zeta appends the element", "score_avg": 5.0,
         "score_grader_1": 5.0, "score_grader_2": 5.0} for i in range(2)]
    alt_fixed = "제타 구조 0은 새 요소를 끝에 붙이고 앞선 요소의 순서를 바꾸지 않는다."
    assert Q.trans_check(alt_ref, alt_fixed) is None
    pin = write_pin(env["tmp"] / "pin.csv", [pin_row(0, fix_q(0), alt_fixed, "확인함", ref_en=alt_ref)])
    llm = _install(env)
    BC.main(_argv(env, "--pin", str(pin)))
    rows = _rows_of(env, 0)
    alt = [r for r in rows if r["source_id"].startswith("M00.ALT")]
    base = [r for r in rows if not r["source_id"].startswith("M00.ALT")]
    assert alt and base
    assert all(r["question"] == fix_q(0) for r in rows)
    assert all(r["model_answer"] == alt_fixed for r in alt)
    assert all(r["model_answer"] != alt_fixed for r in base)
    assert mohler_ref(0) in llm.translated and alt_ref not in llm.translated
