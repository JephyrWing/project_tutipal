"""채점 리포트의 과목별·형식별 지표, 신뢰 구간(eval.report) 테스트. 모두 합성 기록이다."""

import json
import re

import pytest

from eval import leakcheck, report

METRIC_KEYS = {"n", "accuracy", "balanced_accuracy", "false_accept", "injection_defense", "valid_json"}


def grade(model, item, label, predicted, kind="exact", status=None, subject="코딩", **kw):
    rec = {"kind": "grade", "model": model, "item_id": item, "qid": "q1", "subject": subject,
           "answer_kind": kind, "label": label, "predicted": predicted,
           "status": status or ("ok" if predicted is not None else "invalid_json"),
           "response": "{}", "wall_seconds": 1.0, "tokens_per_second": 30.0} | kw
    if subject is None:
        del rec["subject"]
    return rec


def _cells(line: str) -> list[str]:
    return [c.strip() for c in line.strip().strip("|").split("|")]


def _tables(md: str) -> list[tuple[list[str], list[list[str]]]]:
    """마크다운 표 목록: (머리글 칸, 본문 행 칸들). 구분선(|---|)은 뺀다."""
    tables, block = [], []
    for line in md.splitlines() + [""]:
        if line.strip().startswith("|"):
            block.append(line)
        elif block:
            rows = [_cells(l) for l in block if not re.fullmatch(r"\|?(\s*:?-+:?\s*\|)+\s*", l.strip())]
            tables.append((rows[0], rows[1:]))
            block = []
    return tables


def _table_with(md: str, column: str) -> tuple[list[str], list[list[str]]]:
    found = [t for t in _tables(md) if t[0] and t[0][0] == "모델" and column in t[0]]
    assert found, f"'{column}' 열이 있는 표가 없음:\n{md}"
    return found[0]


def _cell(table, model: str, column: str) -> str:
    header, rows = table
    row = next(r for r in rows if r[0] == model)
    return row[header.index(column)]


# ---------- A1 과목별 지표 ----------

def test_summarize_grade_breaks_down_metrics_by_subject():
    recs = [grade("m", "1", True, True, subject="코딩"),
            grade("m", "2", True, False, subject="코딩"),
            grade("m", "3", False, False, "wrong", subject="수학")]
    g = report.summarize_grade(recs)
    by = g["by_subject"]
    assert set(by) == {"코딩", "수학"}
    assert by["코딩"]["accuracy"] == 0.5
    assert by["코딩"]["n"] == 2
    assert by["수학"]["n"] == 1
    assert by["수학"]["accuracy"] == 1.0
    for metrics in by.values():
        assert METRIC_KEYS <= set(metrics)


def test_by_subject_metrics_follow_overall_definitions():
    recs = [grade("m", "1", True, True),
            grade("m", "2", False, True, "injection"),        # 꼼수에 속음
            grade("m", "3", False, None, "tag_escape"),       # JSON 깨짐: 틀림, 방어는 함
            grade("m", "4", False, False, "wrong")]
    c = report.summarize_grade(recs)["by_subject"]["코딩"]
    assert c["accuracy"] == 2 / 4
    assert c["false_accept"] == 1 / 3
    assert c["injection_defense"] == 1 / 2
    assert c["valid_json"] == 3 / 4
    assert c["balanced_accuracy"] == (1.0 + 1 / 3) / 2  # 오답 인식: predicted False인 것만(1/3)


def test_records_without_subject_go_to_unspecified():
    recs = [grade("m", "1", True, True, subject=None), grade("m", "2", True, True, subject="코딩")]
    by = report.summarize_grade(recs)["by_subject"]
    assert by["미지정"]["n"] == 1
    assert by["코딩"]["n"] == 1


# ---------- A2 모델 × 과목 표 ----------

def test_markdown_has_model_by_subject_table_with_unspecified_last():
    recs = [grade("m", "1", True, True, subject="코딩"),
            grade("m", "2", True, False, subject="코딩"),
            grade("m", "3", False, False, "wrong", subject=None),
            grade("m", "4", False, False, "wrong", subject="수학")]
    md = report.to_markdown(report.summarize(recs), {"m": "모델M"})
    table = _table_with(md, "코딩")
    header = table[0]
    assert "수학" in header
    assert header[-1] == "미지정"
    assert _cell(table, "모델M", "코딩") == "50.0% (2)"
    assert _cell(table, "모델M", "수학") == "100.0% (1)"
    assert _cell(table, "모델M", "미지정") == "100.0% (1)"


def test_markdown_subject_table_printed_even_for_single_subject():
    recs = [grade("m", "1", True, True), grade("m", "2", False, False, "wrong")]
    md = report.to_markdown(report.summarize(recs))
    table = _table_with(md, "코딩")
    assert _cell(table, "m", "코딩") == "100.0% (2)"


# ---------- A3 윌슨 신뢰 구간 ----------

def test_wilson_ci_returns_none_for_empty_sample():
    assert report.wilson_ci(0, 0) is None


def test_wilson_ci_all_hits_is_clipped_to_one():
    lo, hi = report.wilson_ci(10, 10)
    assert round(lo, 3) == 0.722
    assert hi == pytest.approx(1.0)
    assert hi <= 1.0


def test_wilson_ci_no_hits_starts_at_zero():
    lo, hi = report.wilson_ci(0, 10)
    assert lo == pytest.approx(0.0)
    assert lo >= 0.0
    assert round(hi, 3) == 0.278


def test_wilson_ci_is_symmetric_around_half_and_returns_tuple():
    ci = report.wilson_ci(5, 10)
    assert isinstance(ci, tuple) and len(ci) == 2
    lo, hi = ci
    assert round(lo, 3) == 0.237
    assert round(hi, 3) == 0.763


def test_wilson_ci_wider_z_gives_wider_interval():
    lo95, hi95 = report.wilson_ci(5, 10)
    lo99, hi99 = report.wilson_ci(5, 10, z=2.576)
    assert lo99 < lo95 and hi99 > hi95


GRADE_RECS = [
    grade("m", "1", True, True),
    grade("m", "2", True, False, "paraphrase"),
    grade("m", "3", False, False, "wrong"),
    grade("m", "4", False, True, "injection"),
    grade("m", "5", False, None, "tag_escape"),
    grade("m", "6", False, False, "injection_with_answer"),
    grade("m", "7", False, None, "unknown", status="llm_error"),  # 분모에서 빠짐
]


def test_summary_has_ci_with_same_denominators_as_metrics():
    g = report.summarize_grade(GRADE_RECS)
    # 정확도: llm_error 제외 6건 중 3건, 꼼수 방어: 꼼수 3건 중 2건
    assert list(g["accuracy_ci"]) == pytest.approx(list(report.wilson_ci(3, 6)))
    assert list(g["injection_defense_ci"]) == pytest.approx(list(report.wilson_ci(2, 3)))
    as_json = json.loads(json.dumps(g, ensure_ascii=False))
    assert isinstance(as_json["accuracy_ci"], list) and len(as_json["accuracy_ci"]) == 2


def test_summary_ci_is_none_when_no_denominator():
    g = report.summarize_grade([grade("m", "1", True, True)])
    assert g["injection_defense_ci"] is None
    g = report.summarize_grade([grade("m", "1", True, None, status="llm_error")])
    assert g["accuracy_ci"] is None


def _pct1(v: float) -> str:
    return f"{v * 100:.1f}"


def test_markdown_accuracy_and_injection_cells_show_ci():
    md = report.to_markdown(report.summarize(GRADE_RECS))
    table = _table_with(md, "꼼수 방어율")
    header = table[0]
    # CI 열은 행 맨 끝("응답 중앙값(초)" 뒤)에 이 순서로 붙는다
    assert header[-2:] == ["정확도 95% CI", "꼼수 방어 95% CI"]
    assert header.index("응답 중앙값(초)") == len(header) - 3
    acc_lo, acc_hi = report.wilson_ci(3, 6)
    inj_lo, inj_hi = report.wilson_ci(2, 3)
    acc = _cell(table, "m", "정확도 95% CI")
    inj = _cell(table, "m", "꼼수 방어 95% CI")
    assert re.fullmatch(rf"{re.escape(_pct1(acc_lo))}%?–{re.escape(_pct1(acc_hi))}%?", acc), acc
    assert re.fullmatch(rf"{re.escape(_pct1(inj_lo))}%?–{re.escape(_pct1(inj_hi))}%?", inj), inj
    # 기존 칸은 그대로
    assert _cell(table, "m", "정확도") == "50.0%"
    assert _cell(table, "m", "꼼수 방어율") == "66.7%"


def test_markdown_ci_cells_are_dash_when_no_denominator():
    md = report.to_markdown(report.summarize([grade("m", "1", True, True)]))  # 꼼수 행 없음
    table = _table_with(md, "꼼수 방어 95% CI")
    assert _cell(table, "m", "꼼수 방어 95% CI") == "-"
    md = report.to_markdown(report.summarize([grade("m", "1", True, None, status="llm_error")]))  # 전부 llm_error
    table = _table_with(md, "정확도 95% CI")
    assert _cell(table, "m", "정확도 95% CI") == "-"


# ---------- A4 답안 형식별 지표 ----------

def test_by_form_counts_only_records_with_answer_form():
    recs = [grade("m", "1", True, True, answer_form="code"),
            grade("m", "2", True, False, answer_form="code"),
            grade("m", "3", False, False, "wrong", answer_form="text"),
            grade("m", "4", False, True, "wrong")]  # 형식 없음: by_form에서 빠짐
    g = report.summarize_grade(recs)
    assert set(g["by_form"]) == {"code", "text"}
    assert g["by_form"]["code"]["n"] == 2
    assert g["by_form"]["code"]["accuracy"] == 0.5
    assert g["by_form"]["text"]["n"] == 1
    assert g["by_form"]["text"]["accuracy"] == 1.0
    assert METRIC_KEYS <= set(g["by_form"]["code"])


def test_by_form_is_empty_without_answer_form():
    assert report.summarize_grade([grade("m", "1", True, True)])["by_form"] == {}


def _has_form_table(md: str) -> bool:
    return any(t[0] and t[0][0] == "모델" and ("code" in t[0] or "text" in t[0]) for t in _tables(md))


def test_markdown_form_table_only_when_forms_exist():
    without = report.to_markdown(report.summarize([grade("m", "1", True, True)]))
    assert not _has_form_table(without)
    recs = [grade("m", "1", True, True, answer_form="code"),
            grade("m", "2", True, False, answer_form="text")]
    md = report.to_markdown(report.summarize(recs))
    assert _has_form_table(md)
    table = next(t for t in _tables(md) if t[0] and t[0][0] == "모델" and "code" in t[0])
    assert _cell(table, "m", "code") == "100.0% (1)"


def test_existing_grade_keys_unchanged_by_new_breakdowns():
    g = report.summarize_grade(GRADE_RECS)
    assert g["n"] == 7 and g["errors"] == 1
    assert g["accuracy"] == 3 / 6
    assert g["injection_defense"] == 2 / 3
    assert g["by_kind"]["injection"]["rate"] == 0.0


# ---------- A10 꼼수 종류 일치 ----------

def test_report_injection_kinds_match_leakcheck_trick_kinds():
    assert set(report.INJECTION_KINDS) == set(leakcheck.TRICK_KINDS)
