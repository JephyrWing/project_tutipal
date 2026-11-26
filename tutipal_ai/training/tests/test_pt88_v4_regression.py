"""PT-88 v4 회귀·검수 시트 테스트: AC-8(v3 사람 검수 14행 회귀), AC-11(새 시트 --exclude-ids, 회귀 리포트),
AC-12(윌슨 구간), AC-13(문서).

AC-8 분류(계획서 '14건 분류'):
  rule          — v4 규칙이 규칙 이름까지 잡는다.
  pin           — 규칙으로는 못 잡고 고정 번역(데이터 단계)으로 고친다. 같은 행 feedback 잔해는 expl:foreign.
                  고정값 적용은 test_pt88_v4_pin.py::test_e01_q02_a08_question_is_fixed_in_data_stage.
  undetectable  — 자동 검출 불가(한계). 설명 품질 규칙(vague_missing·wrong_affirmed·foreign)이 이 행들을 잡지 않음을 고정한다.

review_sheet v4 (v4에서 정함):
  시트 열은 --exclude-ids 와 무관하게 항상 v3 사람 시트와 같은 17열(기존 14열 + 점수 3열, 강지 판정 2026-11-26).
  check 는 verdict 만 읽으며 14열짜리 옛 시트도 그대로 읽는다.
  make --exclude-ids <v3 사람 검수 CSV>: CSV 의 id 를 시트 후보에서 뺀다. meta 에 exclude_ids_file(이름), exclude_ids_sha256, exclude_ids_count(뺀 id 수).
  같은 폴더에 review_regression.csv(열 id, verdict_v3, status)를 쓴다. 대상은 CSV 에서 verdict 가 OK 가 아닌 행.
  status: 'present:<새 텍스트 요약>'(train_aug.jsonl 에 있음) / 'dropped:<사유>'(rejected.jsonl 등에 있음) /
  'excluded_source'(어디에도 없음). 이 리포트는 check 의 게이트 계산에 들어가지 않는다.
  wilson_interval(k, n, z=1.96) -> (하한, 상한).
"""

import csv
import json
from pathlib import Path

import pytest
from _v2_data import MOH, SAF, FakeRows, english_items, make_tables, written
from _v4_data import by_short_id, en_of, label_of, o_of, review_rows, tr_of, x_of

from data_tools import build_grading_c as BC
from data_tools import build_grading_train as B
from data_tools import review_sheet as RS

TRAINING = Path(__file__).resolve().parents[1]
ROWS = by_short_id()

CLASS = {
    "E04.Q05.A07": "rule", "E04.Q05.A28": "rule", "E10.Q06.A16": "rule", "b610b9c1": "rule",
    "bca32dc7": "rule", "9838a386": "rule", "E03.Q07.A29": "rule", "E11.Q03.A12": "rule",
    "E01.Q02.A08": "pin",
    "E06.Q04.A20": "undetectable", "E01.Q07.A19": "undetectable", "d7f088bc": "undetectable",
    "b4ddfa2c": "undetectable", "61484f2a": "undetectable",
}
EXPL_QUALITY_RULES = {"expl:vague_missing", "expl:wrong_affirmed", "expl:foreign"}
SCORE_COLUMNS = ["사람 검토 - 번역 정확도·뉘앙스", "사람 검토 - 라벨 정확도", "사람 검토 - 판정 이유·피드백 정확도"]
SHEET_COLUMNS_V3 = ["id", "source", "q_en", "ref_en", "ans_en", "q_ko", "ref_ko", "ans_ko", "label", "kind",
                    "reason", "feedback", "verdict", "note", *SCORE_COLUMNS]


def _trans(sid):
    r = ROWS[sid]
    return BC.trans_error(x_of(r), tr_of(r))


def _check(sid):
    r = ROWS[sid]
    return BC.check(o_of(r), label_of(r), expl=True, kind=r["kind"], en=en_of(r))


def _term(rule, prefix):
    assert rule and rule.startswith(prefix), rule
    return rule[len(prefix):].rsplit(":", 1)


# ---------- AC-8: fixture ----------

def test_fixture_has_14_not_ok_rows_and_4_ok_rows():
    rows = review_rows()
    not_ok = [r for r in rows if r["verdict"] != "OK"]
    assert len(not_ok) == 14 and len(rows) == 18
    assert set(ROWS) - {"E02.Q03.A05", "E07.Q07.A09", "E08.Q05.A03", "7405e5a7"} == set(CLASS)


# ---------- AC-8: 규칙 행 (규칙 이름까지) ----------

@pytest.mark.parametrize("sid", ["E04.Q05.A07", "E04.Q05.A28"])
def test_dimension_rows_fail_on_model_answer(sid):
    assert _trans(sid) == "trans_check:glossary:dimension:model_answer"


def test_inorder_row_fails_on_question():
    term, field = _term(_trans("E10.Q06.A16"), "trans_check:glossary:")
    assert term in {"inorder", "traversal", "traverse"} and field == "question"


@pytest.mark.parametrize("sid", ["b610b9c1", "bca32dc7"])
def test_service_class_rows_fail_on_model_answer(sid):
    term, field = _term(_trans(sid), "trans_check:glossary:")
    assert term in {"unconfirmed connectionless", "confirmed connectionless", "connection-oriented"}
    assert field == "model_answer"


def test_bit_byte_row_is_rejected_by_translation_rules():
    """SAF-9838a386: 학생 답안 '1 bit per Baud' → '1 바이트당 1 바우드'. 문장 단위 'unit' 은
    test_pt88_v4_glossary.py::test_unit_rule_fails_when_bit_and_byte_are_swapped 에서 고정한다."""
    assert _trans("9838a386") is not None


def test_e03_q07_a29_vague_missing():
    assert _check("E03.Q07.A29") == "expl:vague_missing"


def test_e11_q03_a12_wrong_affirmed():
    assert _check("E11.Q03.A12") == "expl:wrong_affirmed"


# ---------- AC-8: 고정 행 ----------

def test_pin_row_translation_is_not_caught_by_rules_but_debris_is():
    """MohlerASAG-E01.Q02.A08: phase/stage 를 모두 '단계'로 옮긴 건 규칙으로 못 잡는다(고정 번역으로 고친다)."""
    assert _trans("E01.Q02.A08") is None
    assert _check("E01.Q02.A08") == "expl:foreign"


# ---------- AC-8: 검출 불가 행 ----------

@pytest.mark.parametrize("sid", [k for k, v in CLASS.items() if v == "undetectable"])
def test_undetectable_rows_are_not_caught_by_explanation_quality_rules(sid):
    assert _check(sid) not in EXPL_QUALITY_RULES


# ---------- AC-11: 새 시트 (--exclude-ids) ----------

@pytest.fixture
def v4_dir(tmp_path, monkeypatch):
    tables = make_tables(n_mohler_q=6, per_mohler=14, n_saf_q=4, per_saf_train=8, per_saf_val=4)
    monkeypatch.setattr(B, "rows", FakeRows(tables))
    rows = []
    for x in english_items(tables):
        o = written(x)
        r = B.record(f"{x['source']}-{x['source_id']}", x, o["question"], o["model_answer"], o["student_answer"],
                     x["label"], x["kind"], o["reason"], o["feedback"], x["subject"])
        r["qid"] = x["qid"]
        rows.append(r)
    d = tmp_path / "grading_v4"
    d.mkdir()
    (d / "train_aug.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows),
                                       encoding="utf-8")
    (tmp_path / "datasets").mkdir()
    coding = sorted(r["id"] for r in rows if r["subject"] == "코딩")
    return {"dir": d, "datasets": tmp_path / "datasets", "coding": coding, "tmp": tmp_path}


def _code(argv):
    try:
        ret = RS.main(argv)
    except SystemExit as e:
        return 0 if e.code is None else e.code
    return 0 if ret is None else ret


def _write_v3_review(path, rows):
    """v3 사람 검수 시트와 같은 열. rows = [(id, verdict)]."""
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(SHEET_COLUMNS_V3)
        for rid, verdict in rows:
            src = SAF if rid.startswith(SAF) else MOH
            w.writerow([rid, src, "q", "r", "a", "문", "모", "답", "TRUE", "paraphrase", "이유", "피드백", verdict,
                        "", "10", "10", "10"])
    return path


def _read(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        return reader.fieldnames, list(reader)


def _make(v4_dir, *extra):
    return _code(["make", "--dir", str(v4_dir["dir"]), "--datasets-dir", str(v4_dir["datasets"]),
                  "--seed", "20261126", "--n", "30", "--min-saf", "8", *extra])


def test_make_excludes_ids_from_v3_review(v4_dir):
    excluded = v4_dir["coding"][:20]
    review = _write_v3_review(v4_dir["tmp"] / "review_v3.csv", [(i, "OK") for i in excluded])
    assert _make(v4_dir, "--exclude-ids", str(review)) == 0
    header, rows = _read(v4_dir["dir"] / "review_sheet.csv")
    assert len(rows) == 30
    assert not ({r["id"] for r in rows} & set(excluded))
    assert sum(r["source"] == SAF for r in rows) >= 8


def test_make_with_exclude_ids_uses_v3_sheet_columns(v4_dir):
    review = _write_v3_review(v4_dir["tmp"] / "review_v3.csv", [(v4_dir["coding"][0], "OK")])
    assert _make(v4_dir, "--exclude-ids", str(review)) == 0
    header, rows = _read(v4_dir["dir"] / "review_sheet.csv")
    assert header == SHEET_COLUMNS_V3
    assert all(r[c] == "" for r in rows for c in SCORE_COLUMNS)


def test_make_without_exclude_ids_also_uses_17_columns(v4_dir):
    assert RS.SHEET_COLUMNS == SHEET_COLUMNS_V3
    assert _make(v4_dir) == 0
    header, rows = _read(v4_dir["dir"] / "review_sheet.csv")
    assert header == SHEET_COLUMNS_V3
    assert len(rows) == 30 and all(r[c] == "" for r in rows for c in SCORE_COLUMNS)


def test_check_reads_old_14_column_sheet(v4_dir):
    """make 로 만든 시트를 옛 14열(점수 열 없음)로 다시 써도 check 는 verdict 만 보고 판정한다."""
    assert _make(v4_dir) == 0
    sheet = v4_dir["dir"] / "review_sheet.csv"
    _, rows = _read(sheet)
    old_columns = SHEET_COLUMNS_V3[:14]
    with open(sheet, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=old_columns, extrasaction="ignore")
        w.writeheader()
        for i, r in enumerate(rows):
            w.writerow({**r, "verdict": "OK" if i >= 4 else "TRANS_ERR"})
    assert _code(["check", "--sheet", str(sheet)]) == 1  # OK 아닌 행 4개 → 1
    _, rows = _read(sheet)
    with open(sheet, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=old_columns, extrasaction="ignore")
        w.writeheader()
        for r in rows:
            w.writerow({**r, "verdict": "OK"})
    assert _code(["check", "--sheet", str(sheet)]) == 0


def test_meta_records_seed_and_exclude_file_hash(v4_dir):
    excluded = v4_dir["coding"][:5]
    review = _write_v3_review(v4_dir["tmp"] / "review_v3.csv", [(i, "OK") for i in excluded])
    assert _make(v4_dir, "--exclude-ids", str(review)) == 0
    meta = json.loads((v4_dir["dir"] / "review_sheet.meta.json").read_text(encoding="utf-8"))
    assert meta["seed"] == 20261126
    assert meta["exclude_ids_file"] == "review_v3.csv"
    assert meta["exclude_ids_sha256"] == RS.file_sha256(review)
    assert meta["exclude_ids_count"] == 5


def test_check_uses_verdict_only_with_score_columns(v4_dir):
    review = _write_v3_review(v4_dir["tmp"] / "review_v3.csv", [(v4_dir["coding"][0], "OK")])
    assert _make(v4_dir, "--exclude-ids", str(review)) == 0
    sheet = v4_dir["dir"] / "review_sheet.csv"
    fields, rows = _read(sheet)
    for i, r in enumerate(rows):
        r["verdict"] = "OK" if i >= 3 else "TRANS_ERR"
        r[SCORE_COLUMNS[0]] = "4" if i < 3 else ""  # 점수는 채우든 비우든 판정에 쓰지 않는다
    with open(sheet, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)
    assert _code(["check", "--sheet", str(sheet)]) == 0


def test_regression_report_classifies_v3_not_ok_ids(v4_dir):
    coding = v4_dir["coding"]
    present, dropped, ok_id = coding[0], coding[1], coding[2]
    gone = f"{MOH}-E99.Q99.A99"
    (v4_dir["dir"] / "rejected.jsonl").write_text(
        json.dumps({"source": MOH, "source_id": dropped.split("-", 1)[1], "error": "expl:foreign"}) + "\n",
        encoding="utf-8")
    train = (v4_dir["dir"] / "train_aug.jsonl").read_text(encoding="utf-8").splitlines()
    train = [line for line in train if json.loads(line)["id"] != dropped]
    (v4_dir["dir"] / "train_aug.jsonl").write_text("\n".join(train) + "\n", encoding="utf-8")
    review = _write_v3_review(v4_dir["tmp"] / "review_v3.csv",
                              [(present, "TRANS_ERR"), (dropped, "EXPL_ERR"), (gone, "TRANS_ERR"), (ok_id, "OK")])
    assert _make(v4_dir, "--exclude-ids", str(review)) == 0
    header, rows = _read(v4_dir["dir"] / "review_regression.csv")
    assert header[:3] == ["id", "verdict_v3", "status"]
    status = {r["id"]: r for r in rows}
    assert set(status) == {present, dropped, gone}
    assert status[present]["status"].startswith("present:")
    assert status[dropped]["status"] == "dropped:expl:foreign"
    assert status[gone]["status"] == "excluded_source"
    assert status[dropped]["verdict_v3"] == "EXPL_ERR"
    _, sheet_rows = _read(v4_dir["dir"] / "review_sheet.csv")
    assert not ({r["id"] for r in sheet_rows} & {present, dropped, gone})


# ---------- AC-12: 윌슨 구간 ----------

def test_wilson_interval_known_value():
    lo, hi = RS.wilson_interval(3, 30)
    assert lo == pytest.approx(0.0346, abs=5e-4)
    assert hi == pytest.approx(0.2562, abs=5e-4)


def test_wilson_interval_edges():
    lo, hi = RS.wilson_interval(0, 30)
    assert lo == pytest.approx(0.0, abs=1e-9) and 0 < hi < 0.15
    lo, hi = RS.wilson_interval(30, 30)
    assert hi == pytest.approx(1.0, abs=1e-9) and 0.85 < lo < 1
    with pytest.raises(ValueError):
        RS.wilson_interval(1, 0)


# ---------- AC-13: 문서 ----------

def test_readme_has_v4_section():
    text = (TRAINING / "README.md").read_text(encoding="utf-8")
    assert "grading_v4" in text and "--pin" in text and "--exclude-ids" in text


def test_troubleshooting_has_v4_section_with_limits():
    text = (TRAINING / "docs" / "PT-88_TROUBLESHOOTING.md").read_text(encoding="utf-8")
    assert "v4" in text
    assert "사실 오류" in text and "중요도" in text
