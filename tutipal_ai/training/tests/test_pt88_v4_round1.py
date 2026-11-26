"""PT-88 v4 Round 1 판정 반영 테스트(강지 2026-11-26). 통과/탈락을 짝으로 둔다.

결정 1 a~e(사람이 확인한 고정 번역 5건이 걸린 규칙 수정), 실제 고정 번역 시트 회귀, 결정 2(consistency 통계),
F2(AI 이름 Codex·OpenAI), F4(사람 확인 표시), F6·F7·Z6·Z7·Z8·Z10.
"""

import json
import os
from pathlib import Path

import pytest
from _v2_data import MOH, mohler_q, qid_of
from _v4_data import by_short_id, tr_of, x_of
from test_pt88_v4_pin import (
    PIN_COLUMNS,
    _argv,
    _exit_code,
    _install,
    _nothing_written,
    env,
    fix_q,
    fix_ref,
    pin_row,
    write_pin,
)

from data_tools import build_grading_c as BC
from data_tools import build_grading_train as B
from data_tools import quality as Q

__all__ = ["env"]

HOME_DATA = Path.home() / "tutipal_datasets"
REAL_PIN = Path(os.environ.get("TUTIPAL_PIN_FILE") or HOME_DATA / "derived" / "pins" / "pt88_v4_pin_20261126.csv")
REAL_DATASETS = Path(os.environ.get("TUTIPAL_DATASETS_DIR") or HOME_DATA / "datasets")


def _terms(text):
    return {e["en"] for e in Q.glossary_for(text)}


# ---------- 결정 1 a: confirmed connectionless ----------

def test_a_confirmed_connectionless_symmetric_form_passes():
    assert Q.trans_check("2. confirmed connectionless - with ACK", "2. 수신 확인 있는 비연결형 - ACK 있음") is None


def test_a_confirmed_connectionless_old_form_still_fails():
    assert Q.trans_check("2. confirmed connectionless - with ACK", "2. 확인된 비연결형 - ACK 있음") == \
        "glossary:confirmed connectionless"


# ---------- 결정 1 b: 붙임표 합성어의 한 부분은 용어가 아니다 ----------

@pytest.mark.parametrize("text, term", [
    ("in the 60-second observation time-frame", "frame"),
    ("a collision-free channel", "collision"),
])
def test_b_hyphen_compound_part_is_not_a_term(text, term):
    assert term not in _terms(text)


@pytest.mark.parametrize("text, term", [
    ("each data frame is acknowledged", "frame"),
    ("frames are concatenated", "frame"),
    ("a collision occurs", "collision"),
])
def test_b_standalone_term_is_matched(text, term):
    assert term in _terms(text)


def test_b_time_frame_idiom_is_not_frame():
    """'time frame'(시간 구간)은 데이터 프레임이 아니다. 판정 b 의 '띄어쓰기는 매칭' 과 다르게 정한 부분(보고함):
    같은 고정 번역(8471e96a)에 'the time frame' 이 띄어쓰기로도 있어 매칭하면 사람 번역 '관찰 시간' 이 걸린다."""
    assert "frame" not in _terms("we simply multiply the time frame with P")
    assert "frame" in _terms("the time frame and the frame header")  # 다른 곳의 frame 은 그대로 잡는다


# ---------- 결정 1 c: perfect clock 어순 ----------

@pytest.mark.parametrize("ko", ["각자의 클록이 완벽한 로컬 네트워크", "클럭이 완벽하다", "시계가 완벽하게 맞는다"])
def test_c_perfect_clock_word_order_passes(ko):
    assert Q.trans_check("users that have perfect clocks", ko) is None


def test_c_perfect_time_still_fails():
    assert Q.trans_check("users that have perfect clocks", "완벽한 시간을 가진 사용자") == "glossary:perfect clock"


# ---------- 결정 1 d: 'N분의 1' ----------

def test_d_fraction_one_counts_only_denominator():
    assert Q.trans_check("reduced by a factor of 10", "10분의 1로 줄어든다") is None


def test_d_other_denominator_fails_number():
    assert Q.trans_check("reduced by a factor of 10", "5분의 1로 줄어든다") == "number"


def test_d_general_fraction_is_not_relaxed():
    assert Q.trans_check("reduced by a factor of 10", "10분의 3으로 줄어든다") == "number"


# ---------- 결정 1 e: 'instead of only <수>' + '대신', baud 허용형 ----------

EN_E = "you get a full bit per baud instead of only 0.5"


def test_e_instead_of_only_with_daesin_passes():
    assert Q.trans_check(EN_E, "0.5비트 대신 보(baud)당 비트 하나를 온전히 얻는다") is None


def test_e_instead_of_only_without_daesin_still_fails():
    assert Q.trans_check(EN_E, "보(baud)당 비트 하나를 온전히 얻는다, 0.5보다 크다") == "only"


@pytest.mark.parametrize("en, ko", [
    ("it is not only fast", "대신 빠르지 않다고 할 수 없다"),
    ("send only 2 frames", "대신 2개 프레임을 보낸다"),
])
def test_e_other_only_unchanged(en, ko):
    assert Q.trans_check(en, ko) == "only"


@pytest.mark.parametrize("ko", ["보(baud)당 1비트", "보당 1비트", "보 당 1비트", "보드당 1비트", "바우드당 1비트"])
def test_e_baud_forms_pass(ko):
    assert Q.trans_check("1 bit per baud", ko) is None


@pytest.mark.parametrize("ko", ["1비트를 보면 된다", "1비트보다 많다"])
def test_e_single_bo_is_not_baud(ko):
    assert Q.trans_check("1 bit per baud", ko) == "glossary:baud"


# ---------- 실제 고정 번역 시트 회귀(파일 없으면 건너뜀). 이 테스트 통과 전 생성 금지 ----------

@pytest.mark.skipif(not (REAL_PIN.is_file() and REAL_DATASETS.is_dir()), reason="실제 고정 번역 시트 또는 원천 없음")
def test_real_pin_sheet_loads_with_67_qids_and_named_ai():
    by_qid: dict[str, dict] = {}
    for x in B.load_sources_v2(REAL_DATASETS):
        by_qid.setdefault(x["qid"], {"q": x["q"], "refs": set()})["refs"].add(BC._norm_en(x["ref"]))
    pins, stats = BC.load_pins(REAL_PIN, by_qid)
    assert stats["qids"] == 67 and len(pins) == 67
    assert "unspecified" not in stats["by_ai"]
    assert stats["unconfirmed"] == 0


# ---------- 결정 2: consistency 탈락 통계 ----------

def test_consistency_drops_are_counted_by_term_and_pinned_qid(env):
    """고정한 문제는 '원소', 가짜 번역 모범 답안은 '요소'(둘 다 element 허용형) → 그 문항 행이 consistency 로 빠진다."""
    q_fixed = fix_q(0, word="원소")
    assert Q.trans_check(mohler_q(0), q_fixed) is None
    pin = write_pin(env["tmp"] / "pin.csv", [pin_row(0, q_fixed=q_fixed, note="팀원 확인함")])
    _install(env)
    BC.main(_argv(env, "--pin", str(pin)))
    s = json.loads((env["out"] / "stats.json").read_text(encoding="utf-8"))
    cd = s["consistency_drops"]
    qid = qid_of(MOH, mohler_q(0))
    assert cd["by_term"]["element"] >= 1
    assert cd["by_pinned_qid"][qid] == cd["by_term"]["element"]
    assert cd["by_source"][MOH] >= 1


def test_consistency_drops_empty_without_failures(env):
    _install(env)
    BC.main(_argv(env))
    cd = json.loads((env["out"] / "stats.json").read_text(encoding="utf-8"))["consistency_drops"]
    assert cd == {"by_term": {}, "by_source": {}, "by_pinned_qid": {}}


# ---------- F2: AI 이름 Codex·OpenAI ----------

@pytest.mark.parametrize("note, name", [
    ("Codex (OpenAI) 번역 대조·검토 | 팀원 확인함", "Codex"),
    ("OpenAI 초안, 팀원 확인함", "OpenAI"),
])
def test_f2_codex_and_openai_are_recognized(env, note, name):
    pin = write_pin(env["tmp"] / "pin.csv", [pin_row(0, fix_q(0), fix_ref(0), note)])
    _install(env)
    BC.main(_argv(env, "--pin", str(pin)))
    by_ai = json.loads((env["out"] / "stats.json").read_text(encoding="utf-8"))["pin"]["by_ai"]
    assert by_ai == {name: 1}


# ---------- F4: 사람 확인 표시 ----------

@pytest.mark.parametrize("note", [
    "확인함", "ChatGPT-4o 초안, 확인함", "팀원 확인함", "Codex (OpenAI) 번역 대조·검토 | 팀원 확인함(사용자 확인 2026-11-26)",
    "검수자 확인", "팀원이 직접 확인함", "계산은 계산으로 확인함 | 팀원 확인함",
])
def test_f4_human_confirmation_counts(note):
    assert BC.pin_confirmed(note)


@pytest.mark.parametrize("note", [
    "", "Claude 초안", "미확인", "미 확인", "확인 안 함", "확인하지 않음", "확인 X", "GPT-4o 확인X", "확인되지 않음",
    "AI가 확인함", "Codex: 계산으로 확인함", "0.0508과 56.9512초는 계산으로 확인함.",
])
def test_f4_negated_or_non_human_confirmation_does_not_count(note):
    assert not BC.pin_confirmed(note)


# ---------- F6: 한 qid 안 줄마다 다른 문제 고정값 ----------

def test_f6_different_question_pins_in_one_qid_exit_2(env, capsys):
    other_ref = "Zeta structure 0 appends each new element."
    rel = B.SOURCE_FILES["mohler_open"]
    env["tables"][rel] = list(env["tables"][rel]) + [
        {"id": "M00.ALT0", "question": mohler_q(0), "instructor_answer": other_ref,
         "student_answer": "student M00 alt says zeta appends", "score_avg": 5.0, "score_grader_1": 5.0,
         "score_grader_2": 5.0}]
    changed_q = "가상의 제타 구조 0번에 새 요소를 넣는 방법을 설명하세요."
    assert Q.trans_check(mohler_q(0), changed_q) is None
    pin = write_pin(env["tmp"] / "pin.csv", [pin_row(0, fix_q(0), fix_ref(0), "팀원 확인함"),
                                             pin_row(0, changed_q, "", "팀원 확인함", ref_en=other_ref)])
    llm = _install(env)
    assert _exit_code(lambda: BC.main(_argv(env, "--pin", str(pin)))) == 2
    err = capsys.readouterr().err
    assert qid_of(MOH, mohler_q(0)) in err and "q_ko_fixed" in err
    assert llm.translated == [] and llm.prompts == []
    _nothing_written(env)


# ---------- F7: SAF-9838a386 규칙 이름까지 ----------

def test_f7_saf_9838a386_rule_names():
    r = by_short_id()["9838a386"]
    assert BC.trans_error(x_of(r), tr_of(r)) == "trans_check:glossary:perfect clock:question"
    # 학생 답안 칸: 'perfect clocks' → '완벽하게 연결된 클럭' 과 '1 bit per Baud' → '1 바이트당' 둘 다 걸린다
    assert Q.trans_check_all(r["ans_en"], r["ans_ko"]) == ["glossary:perfect clock", "unit"]


# ---------- Z6: 칸마다 걸린 규칙을 모두 알림 ----------

def test_z6_all_rules_of_a_pin_cell_are_reported(env, capsys):
    bad_q = "가상의 제타 구조가 새 항목을 넣는 방법을 설명하세요."  # 수 0 빠짐(number) + element 아님(glossary)
    pin = write_pin(env["tmp"] / "pin.csv", [pin_row(0, bad_q, fix_ref(0), "팀원 확인함")])
    _install(env)
    assert _exit_code(lambda: BC.main(_argv(env, "--pin", str(pin)))) == 2
    err = capsys.readouterr().err
    assert "trans_check:number" in err and "trans_check:glossary:element" in err


def test_z6_trans_check_all_order_matches_trans_check():
    en, ko = "Explain how number 0 inserts a new element.", "새 항목을 넣는 방법"
    rules = Q.trans_check_all(en, ko)
    assert rules[0] == Q.trans_check(en, ko) and set(rules) == {"number", "glossary:element"}
    assert Q.trans_check_all(en, "0번이 새 요소를 넣는 방법") == []


# ---------- Z7: UTF-8 이 아닌 시트 ----------

def test_z7_non_utf8_sheet_exits_2_with_hint(env, capsys):
    path = env["tmp"] / "pin_cp949.csv"
    path.write_bytes((",".join(PIN_COLUMNS) + "\n" + ",".join(["필수"] + [""] * 11) + "\n").encode("cp949"))
    _install(env)
    assert _exit_code(lambda: BC.main(_argv(env, "--pin", str(path)))) == 2
    err = capsys.readouterr().err
    assert "pin_cp949.csv" in err and "UTF-8" in err and "codec" not in err
    _nothing_written(env)


# ---------- Z8: 빈 시트, 고정값 속 잔해 ----------

def test_z8_header_only_sheet_exits_2(env, capsys):
    pin = write_pin(env["tmp"] / "pin.csv", [])
    _install(env)
    assert _exit_code(lambda: BC.main(_argv(env, "--pin", str(pin)))) == 2
    assert "줄이 없음" in capsys.readouterr().err
    _nothing_written(env)


@pytest.mark.parametrize("q_fixed, ref_fixed", [(fix_q(0) + '"}', ""), ("", fix_ref(0) + " {")])
def test_z8_json_debris_in_pin_value_exits_2(env, capsys, q_fixed, ref_fixed):
    pin = write_pin(env["tmp"] / "pin.csv", [pin_row(0, q_fixed, ref_fixed, "팀원 확인함")])
    _install(env)
    assert _exit_code(lambda: BC.main(_argv(env, "--pin", str(pin)))) == 2
    assert "JSON 잔해" in capsys.readouterr().err
    _nothing_written(env)


# ---------- Z10: 긍정 낱말 뒤 부정은 인정이 아니다 ----------

def _wrong_row(reason):
    r = by_short_id()["E11.Q03.A12"]
    return {"question": r["q_ko"], "model_answer": r["ref_ko"], "student_answer": r["ans_ko"],
            "reason": f"{reason} 모범 답안의 핵심인 생성자를 쓰지 않았다.", "feedback": "생성자를 다시 확인해 보세요."}


@pytest.mark.parametrize("reason", [
    "변수 설명은 정확하다고 보기 어렵습니다.",
    "변수 설명이 옳다고 볼 수 없습니다.",
    "변수 초기화는 맞는 설명이 아닙니다.",
])
def test_z10_negated_affirmation_is_not_wrong_affirmed(reason):
    assert Q.expl_check(_wrong_row(reason), False, kind="wrong") is None


@pytest.mark.parametrize("reason", [
    "변수 설명은 정확합니다.",
    "변수 설명은 맞지만 생성자가 없다.",
    "변수 초기화는 맞는 설명입니다.",
])
def test_z10_plain_affirmation_still_fails(reason):
    assert Q.expl_check(_wrong_row(reason), False, kind="wrong") == "expl:wrong_affirmed"
