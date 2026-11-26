"""PT-88 v4 고정 번역(--pin) 테스트 (가짜 원천·가짜 LLM, 네트워크 없음).

AC-4(형식·종료 2·바뀐 문항만 재작성), 사용자 결정 3(AI 초안 출처를 stats 에 기록, 고정값도 trans_check),
AC-6(작성 모델이 새 WRITER_SYSTEM_V2 를 받음), AC-7(SAF 용어집 적용·SAF 탈락 수 stats 기록),
AC-8 고정 행(MohlerASAG-E01.Q02.A08 은 데이터 단계에서 고정값으로 바뀜).

고정 번역 시트 열(UTF-8 BOM): priority, source, qid, example_id, rows_using_this, question_en, model_answer_en,
q_ko_current, ref_ko_current, q_ko_fixed, ref_ko_fixed, note.
q_ko_fixed / ref_ko_fixed 가 빈 칸이면 그 칸은 고정하지 않는다. qid 로 매칭하고, question_en·model_answer_en 이
데이터 원문과 다르면 종료 2.

stats.json['pin'] 형태(v4에서 정함):
  {"file": <시트 파일 이름>, "qids": <고정한 qid 수>, "by_ai": {<note 에서 뽑은 AI 이름>|"unspecified": 개수},
   "unconfirmed": <note 에 '확인' 표시가 없는 고정 qid 수>}
stats.json['drops_by_source']: {출처: 뺀 행 수 합계}.
"""

import csv
import json
from pathlib import Path

import pytest
from _v2_data import (
    MOH,
    SAF,
    english_items,
    load_jsonl,
    make_tables,
    mohler_q,
    mohler_ref,
    qid_of,
    quizai_file,
    write_caches,
)
from _v4_data import FakeLLM, by_short_id, fake_ko

from data_tools import build_grading_c as BC
from data_tools import build_grading_train as B
from data_tools import compare_hybrid as H
from data_tools import quality as Q
from eval.run_eval import GRADE_SYSTEM

TRAINING = Path(__file__).resolve().parents[1]
DEV_FILE = TRAINING / "eval" / "grading_set.jsonl"

PIN_COLUMNS = ["priority", "source", "qid", "example_id", "rows_using_this", "question_en", "model_answer_en",
               "q_ko_current", "ref_ko_current", "q_ko_fixed", "ref_ko_fixed", "note"]


def fix_q(n, word="요소"):
    return f"가상의 제타 구조 {n}번이 새 {word}를 넣는 방법을 설명하세요."


def fix_ref(n):
    return f"제타 구조 {n}은 새 요소를 끝에 두고 앞선 모든 요소의 순서를 그대로 유지한다."


def pin_row(n, q_fixed="", ref_fixed="", note="", q_en=None, ref_en=None, qid=None):
    return {"priority": "필수", "source": MOH, "qid": qid or qid_of(MOH, mohler_q(n)), "example_id": f"M{n:02d}.A00",
            "rows_using_this": "13", "question_en": mohler_q(n) if q_en is None else q_en,
            "model_answer_en": mohler_ref(n) if ref_en is None else ref_en, "q_ko_current": "예전 문제 번역",
            "ref_ko_current": "예전 모범 답안 번역", "q_ko_fixed": q_fixed, "ref_ko_fixed": ref_fixed, "note": note}


def write_pin(path, rows, columns=PIN_COLUMNS):
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=columns, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)
    return path


@pytest.fixture
def env(tmp_path, monkeypatch):
    tables = make_tables()
    monkeypatch.setattr(B, "rows", _Rows(tables))
    v1 = tmp_path / "derived" / "grading_v3"
    write_caches(v1, english_items(make_tables()))
    datasets = tmp_path / "datasets"
    datasets.mkdir()
    return {"tables": tables, "v1": v1, "out": tmp_path / "derived" / "grading_v4", "datasets": datasets,
            "quizai": quizai_file(tmp_path / "QuizAi.java", GRADE_SYSTEM), "tmp": tmp_path, "mp": monkeypatch}


class _Rows:
    def __init__(self, tables):
        self.tables = tables

    def __call__(self, datasets_dir, rel):
        if rel not in self.tables:
            raise AssertionError(f"준비하지 않은 원천 파일: {rel}")
        return [dict(r) for r in self.tables[rel]]


def _argv(env, *extra):
    return ["--v2", "--out", str(env["out"]), "--datasets-dir", str(env["datasets"]), "--quizai", str(env["quizai"]),
            "--eval-set", str(DEV_FILE), "--cache-from", str(env["v1"]), *extra]


def _install(env, **kw):
    llm = FakeLLM(**kw)
    env["mp"].setattr(H, "translate", llm.translate)
    env["mp"].setattr(H, "generate", llm.generate)
    env["mp"].setattr(H, "unload", llm.unload)
    return llm


def _train(env):
    return load_jsonl(env["out"] / "train.jsonl")


def _stats(env):
    return json.loads((env["out"] / "stats.json").read_text(encoding="utf-8"))


def _rows_of(env, n):
    qid = qid_of(MOH, mohler_q(n))
    return [r for r in _train(env) if r.get("qid") == qid and r["kind"] not in
            ("injection", "injection_with_answer", "tag_escape")]


def _exit_code(fn):
    with pytest.raises(SystemExit) as exc:
        fn()
    return exc.value.code


def _nothing_written(env):
    for name in ("train.jsonl", "stats.json", "rejected.jsonl", "trans_cache.jsonl", "write_cache.jsonl"):
        assert not (env["out"] / name).exists(), name


def test_fixed_values_pass_v4_trans_check():
    """테스트에 쓰는 고정값 자체는 검사를 통과한다(아래 실패 테스트가 다른 이유로 실패하지 않게)."""
    assert Q.trans_check(mohler_q(0), fix_q(0)) is None
    assert Q.trans_check(mohler_ref(0), fix_ref(0)) is None


# ---------- AC-4: 고정값 적용 ----------

def test_pinned_qid_rows_use_fixed_question_and_model_answer(env):
    pin = write_pin(env["tmp"] / "pin.csv", [pin_row(0, fix_q(0), fix_ref(0), "확인함")])
    _install(env)
    BC.main(_argv(env, "--pin", str(pin)))
    rows = _rows_of(env, 0)
    assert rows
    assert all(r["question"] == fix_q(0) and r["model_answer"] == fix_ref(0) for r in rows)
    other = _rows_of(env, 1)
    assert other and all(r["question"] == fake_ko(mohler_q(1)) for r in other)


def test_pinned_english_text_is_not_sent_to_translator(env):
    pin = write_pin(env["tmp"] / "pin.csv", [pin_row(0, fix_q(0), fix_ref(0), "확인함")])
    llm = _install(env)
    BC.main(_argv(env, "--pin", str(pin)))
    assert mohler_q(0) not in llm.translated
    assert mohler_ref(0) not in llm.translated
    assert mohler_q(1) in llm.translated


def test_empty_fixed_cells_do_not_pin(env):
    pin = write_pin(env["tmp"] / "pin.csv", [pin_row(1)])
    _install(env)
    BC.main(_argv(env, "--pin", str(pin)))
    rows = _rows_of(env, 1)
    assert rows and all(r["question"] == fake_ko(mohler_q(1)) and r["model_answer"] == fake_ko(mohler_ref(1))
                        for r in rows)


def test_only_filled_cell_is_pinned(env):
    """q_ko_fixed 만 채우면 문제만 고정하고 모범 답안은 번역한다."""
    pin = write_pin(env["tmp"] / "pin.csv", [pin_row(2, q_fixed=fix_q(2), note="확인함")])
    _install(env)
    BC.main(_argv(env, "--pin", str(pin)))
    rows = _rows_of(env, 2)
    assert rows and all(r["question"] == fix_q(2) and r["model_answer"] == fake_ko(mohler_ref(2)) for r in rows)


# ---------- AC-4: 종료 2, 아무것도 쓰지 않음 ----------

def test_english_question_mismatch_exits_2(env, capsys):
    qid = qid_of(MOH, mohler_q(0))
    pin = write_pin(env["tmp"] / "pin.csv", [pin_row(0, fix_q(0), fix_ref(0), q_en=mohler_q(0) + " (edited)", qid=qid)])
    llm = _install(env)
    assert _exit_code(lambda: BC.main(_argv(env, "--pin", str(pin)))) == 2
    assert qid in capsys.readouterr().err
    assert llm.translated == [] and llm.prompts == []
    _nothing_written(env)


def test_english_model_answer_mismatch_exits_2(env, capsys):
    pin = write_pin(env["tmp"] / "pin.csv", [pin_row(0, fix_q(0), fix_ref(0), ref_en="Something else.")])
    _install(env)
    assert _exit_code(lambda: BC.main(_argv(env, "--pin", str(pin)))) == 2
    err = capsys.readouterr().err
    assert "unrecognized arguments" not in err  # --pin 옵션이 없어서 난 인자 오류가 아니어야 한다
    assert qid_of(MOH, mohler_q(0)) in err
    _nothing_written(env)


def test_unknown_qid_exits_2(env, capsys):
    pin = write_pin(env["tmp"] / "pin.csv", [pin_row(0, fix_q(0), fix_ref(0), qid=f"{MOH}|0000000000000000")])
    _install(env)
    assert _exit_code(lambda: BC.main(_argv(env, "--pin", str(pin)))) == 2
    assert f"{MOH}|0000000000000000" in capsys.readouterr().err
    _nothing_written(env)


def test_missing_column_exits_2(env, capsys):
    pin = write_pin(env["tmp"] / "pin.csv", [pin_row(0, fix_q(0), fix_ref(0))],
                    columns=[c for c in PIN_COLUMNS if c != "ref_ko_fixed"])
    _install(env)
    assert _exit_code(lambda: BC.main(_argv(env, "--pin", str(pin)))) == 2
    err = capsys.readouterr().err
    assert "unrecognized arguments" not in err
    assert "ref_ko_fixed" in err
    _nothing_written(env)


@pytest.mark.parametrize("q_fixed, ref_fixed", [
    ("가상의 제타 구조가 새 요소를 넣는 방법을 설명하세요.", None),   # 수 0 이 빠짐 → number
    (None, "제타 구조 0은 새 항목을 끝에 두고 앞선 항목의 순서를 유지한다."),  # element → 요소 아님 → glossary
])
def test_fixed_value_failing_trans_check_exits_2_with_qid(env, capsys, q_fixed, ref_fixed):
    """사용자 결정 3: 사람이 확인한 AI 초안이어도 고정값에 trans_check 를 그대로 적용한다."""
    qid = qid_of(MOH, mohler_q(0))
    pin = write_pin(env["tmp"] / "pin.csv", [pin_row(0, q_fixed or fix_q(0), ref_fixed or fix_ref(0),
                                                     "ChatGPT-4o 초안, 확인함")])
    llm = _install(env)
    assert _exit_code(lambda: BC.main(_argv(env, "--pin", str(pin)))) == 2
    assert qid in capsys.readouterr().err
    assert llm.translated == [] and llm.prompts == []
    _nothing_written(env)


# ---------- AC-4: 고정 표가 바뀌면 그 문항만 다시 작성 ----------

def test_adding_pin_rewrites_only_pinned_question_rows(env):
    _install(env)
    BC.main(_argv(env))
    pin = write_pin(env["tmp"] / "pin.csv", [pin_row(0, fix_q(0), fix_ref(0), "확인함")])
    llm = _install(env)
    BC.main(_argv(env, "--pin", str(pin)))
    assert llm.prompts, "고정한 문항의 행은 작성 캐시를 다시 쓰지 않고 새로 작성해야 한다"
    assert all(mohler_q(0) in p for p in llm.prompts)
    assert all(fix_q(0) in p for p in llm.prompts)
    assert all(r["question"] == fix_q(0) for r in _rows_of(env, 0))


def test_changed_pin_value_rewrites_only_that_question(env):
    first = write_pin(env["tmp"] / "pin1.csv", [pin_row(0, fix_q(0), fix_ref(0), "확인함"),
                                                pin_row(3, fix_q(3), fix_ref(3), "확인함")])
    _install(env)
    BC.main(_argv(env, "--pin", str(first)))
    changed_q = "가상의 제타 구조 0번에 새 요소를 넣는 방법을 설명하세요."
    assert Q.trans_check(mohler_q(0), changed_q) is None
    second = write_pin(env["tmp"] / "pin2.csv", [pin_row(0, changed_q, fix_ref(0), "확인함"),
                                                 pin_row(3, fix_q(3), fix_ref(3), "확인함")])
    llm = _install(env)
    BC.main(_argv(env, "--pin", str(second)))
    assert llm.translated == []
    assert llm.prompts and all(mohler_q(0) in p for p in llm.prompts)
    assert all(r["question"] == changed_q for r in _rows_of(env, 0))
    assert all(r["question"] == fix_q(3) for r in _rows_of(env, 3))


# ---------- 사용자 결정 3: pin 출처 요약 ----------

def test_stats_record_pin_provenance(env):
    pin = write_pin(env["tmp"] / "PT-88_v4_pin.csv", [
        pin_row(0, fix_q(0), fix_ref(0), "ChatGPT-4o 초안, 확인함"),
        pin_row(1),                                          # 빈 칸 → 고정 안 함, 세지 않음
        pin_row(2, q_fixed=fix_q(2)),                        # note 없음 → unspecified, 확인 표시 없음
        pin_row(3, fix_q(3), fix_ref(3), "Claude 초안"),     # 확인 표시 없음
        pin_row(4, fix_q(4), fix_ref(4), "확인함"),           # AI 이름 없음 → unspecified
    ])
    _install(env)
    BC.main(_argv(env, "--pin", str(pin)))
    p = _stats(env)["pin"]
    assert p["file"] == "PT-88_v4_pin.csv"
    assert p["qids"] == 4
    by_ai = p["by_ai"]
    assert sum(by_ai.values()) == 4
    assert by_ai["unspecified"] == 2
    assert sum(v for k, v in by_ai.items() if "ChatGPT" in k) == 1
    assert sum(v for k, v in by_ai.items() if "Claude" in k) == 1
    assert p["unconfirmed"] == 2


def test_negated_confirmation_notes_count_as_unconfirmed(env):
    """강지 판정 4(c): '미확인', '확인 안 함' 은 확인 표시가 아니다."""
    pin = write_pin(env["tmp"] / "pin.csv", [
        pin_row(0, fix_q(0), fix_ref(0), "ChatGPT-4o 초안, 미확인"),
        pin_row(3, fix_q(3), fix_ref(3), "Claude 초안, 확인 안 함"),
        pin_row(4, fix_q(4), fix_ref(4), "Claude 초안, 확인함"),
    ])
    _install(env)
    BC.main(_argv(env, "--pin", str(pin)))
    p = _stats(env)["pin"]
    assert p["qids"] == 3
    assert p["unconfirmed"] == 2


def test_stats_have_no_pin_summary_without_pin(env):
    _install(env)
    BC.main(_argv(env))
    assert _stats(env).get("pin") in (None, {})


# ---------- AC-6: 작성 모델이 보강된 v2 프롬프트를 받는다 ----------

def test_writer_receives_new_v2_system_prompt(env):
    llm = _install(env)
    BC.main(_argv(env))
    assert llm.systems and all(s == H.WRITER_SYSTEM_V2 for s in llm.systems)
    assert "틀린 주장" in llm.systems[0]


# ---------- AC-7: SAF 용어집 적용·SAF 탈락 수 ----------

def test_saf_glossary_failure_is_counted_per_source(env):
    saf = next(r for r in env["tables"][B.SOURCE_FILES["saf"]] if r["question"].endswith("protocol 0 handle lost frames?"))
    ref = saf["reference_answer"]
    receiver = [e for e in Q.glossary_for(ref) if "수신자" in e["ko"]]
    assert receiver, "SAF 모범 답안의 receiver 가 용어집에 걸려야 한다"
    term = receiver[0]["en"]
    _install(env, drop={ref: frozenset({term})})
    BC.main(_argv(env))
    s = _stats(env)
    key = f"trans_check:glossary:{term}:model_answer"
    assert s["drops"][key].get(SAF, 0) >= 1 and MOH not in s["drops"][key]
    assert s["drops_by_source"][SAF] >= s["drops"][key][SAF]
    assert any(r["source"] == SAF for r in _train(env))  # SAF 는 빼지 않는다


# ---------- AC-8 고정 행: MohlerASAG-E01.Q02.A08 ----------

E01_Q_FIXED = "소프트웨어 생명 주기에서 테스트 단계의 영향을 받는 단계는 무엇인가요?"
E01_REF_FIXED = "테스트 단계는 코딩 단계(작업 구간 5)와 해결책 개선 단계(작업 구간 7) 모두에 영향을 줄 수 있다."


def test_e01_q02_a08_question_is_fixed_in_data_stage(env):
    r = by_short_id()["E01.Q02.A08"]
    assert Q.trans_check(r["q_en"], E01_Q_FIXED) is None
    assert Q.trans_check(r["ref_en"], E01_REF_FIXED) is None
    rel = B.SOURCE_FILES["mohler_open"]
    env["tables"][rel] = list(env["tables"][rel]) + [
        {"id": "E01.Q02.A08", "question": r["q_en"], "instructor_answer": r["ref_en"], "student_answer": r["ans_en"],
         "score_avg": 3.0, "score_grader_1": 3.0, "score_grader_2": 3.0}]
    qid = qid_of(MOH, r["q_en"])
    pin = write_pin(env["tmp"] / "pin.csv", [{**pin_row(0), "qid": qid, "example_id": "E01.Q02.A02",
                                              "question_en": r["q_en"], "model_answer_en": r["ref_en"],
                                              "q_ko_current": r["q_ko"], "ref_ko_current": r["ref_ko"],
                                              "q_ko_fixed": E01_Q_FIXED, "ref_ko_fixed": E01_REF_FIXED,
                                              "note": "확인함"}])
    _install(env)
    BC.main(_argv(env, "--pin", str(pin)))
    row = next(t for t in _train(env) if t["id"] == f"{MOH}-E01.Q02.A08")
    assert row["question"] == E01_Q_FIXED and row["model_answer"] == E01_REF_FIXED
