"""채점 학습 데이터 v2 (PT-88): 사람 확인 시트(data_tools.review_sheet) 만들기·검사 테스트.

데이터는 모두 합성이다. 원천 parquet 은 가짜 표로 바꾼다.
"""

import csv
import json
import os
import subprocess
import sys
from collections import Counter
from pathlib import Path

import pytest
from _v2_data import MOH, SAF, SCI, FakeRows, english_items, make_tables, written

from data_tools import build_grading_train as B

try:
    from data_tools import review_sheet as RS
except ImportError:  # 모듈이 생기기 전에는 수집 오류로 전체 실행을 멈추지 않고 테스트마다 실패하게 한다
    RS = None

TRAINING = Path(__file__).resolve().parents[1]

# PT-88 v4 계획서 "열 형식은 v3 사람 시트와 같게"(강지 판정 2026-11-26: 항상 17열): 기존 14열 + 사람 점수 3열.
# check 는 verdict 만 읽는다(점수 열은 판정에 쓰지 않음).
SCORE_COLUMNS = ["사람 검토 - 번역 정확도·뉘앙스", "사람 검토 - 라벨 정확도", "사람 검토 - 판정 이유·피드백 정확도"]
COLUMNS = ["id", "source", "q_en", "ref_en", "ans_en", "q_ko", "ref_ko", "ans_ko", "label", "kind",
           "reason", "feedback", "verdict", "note", *SCORE_COLUMNS]
CHEAT = {"injection", "injection_with_answer", "tag_escape"}


def _row(source, sid, subject="코딩", kind="paraphrase", label=True, rid=None):
    return B.record(rid or f"{source}-{sid}", {"source": source, "source_id": sid}, f"문항 {sid}", "모범 답안",
                    f"학생 답안 {sid}", label, kind, "이유", "피드백", subject)


def _pool(n_moh, n_saf, n_cheat=10, n_sci=30):
    rows = [_row(MOH, f"M{i:03d}", kind=["paraphrase", "partial", "wrong"][i % 3], label=i % 3 == 0)
            for i in range(n_moh)]
    rows += [_row(SAF, f"S{i:03d}", kind=["paraphrase", "partial", "wrong"][i % 3], label=i % 3 == 0)
             for i in range(n_saf)]
    rows += [_row("augment", f"{MOH}-M{i:03d}", kind="tag_escape", label=False, rid=f"aug-tag_escape-{i:03d}")
             for i in range(n_cheat)]
    rows += [_row(MOH, f"M{i:03d}", kind="injection_with_answer", label=False,
                  rid=f"{MOH}-M{i:03d}-injection_with_answer") for i in range(0, 30, 3)]
    rows += [_row(SCI, f"C{i:03d}", subject="과학") for i in range(n_sci)]
    return rows


def test_columns_and_verdicts():
    assert RS.SHEET_COLUMNS == COLUMNS
    assert RS.VERDICTS == ("OK", "LABEL_ERR", "TRANS_ERR", "EXPL_ERR")


def test_pick_rows_30_non_trick_coding_with_at_least_8_saf():
    rows = _pool(100, 20)
    picked = RS.pick_rows(rows, n=30, min_saf=8, seed=5)
    assert len(picked) == 30
    assert all(r["subject"] == "코딩" and r["kind"] not in CHEAT and r["source"] != "augment" for r in picked)
    c = Counter(r["source"] for r in picked)
    assert c[SAF] >= 8
    assert c[MOH] == 30 - c[SAF]
    assert len({r["id"] for r in picked}) == 30


def test_pick_rows_proportional_when_saf_is_large():
    picked = RS.pick_rows(_pool(50, 50), n=30, min_saf=8, seed=5)
    c = Counter(r["source"] for r in picked)
    assert abs(c[SAF] - 15) <= 1 and abs(c[MOH] - 15) <= 1


def test_pick_rows_same_seed_same_ids_and_order_independent():
    rows = _pool(100, 20)
    a = [r["id"] for r in RS.pick_rows(rows, n=30, min_saf=8, seed=5)]
    b = [r["id"] for r in RS.pick_rows(rows, n=30, min_saf=8, seed=5)]
    c = {r["id"] for r in RS.pick_rows(list(reversed(rows)), n=30, min_saf=8, seed=5)}
    assert a == b and set(a) == c


# ---------- make CLI ----------

@pytest.fixture
def v2_final(tmp_path, monkeypatch):
    tables = make_tables(n_mohler_q=6, per_mohler=14, n_saf_q=4, per_saf_train=8, per_saf_val=4)
    monkeypatch.setattr(B, "rows", FakeRows(tables))
    items = english_items(tables)
    rows = []
    for x in items:
        o = written(x)
        r = B.record(f"{x['source']}-{x['source_id']}", x, o["question"], o["model_answer"], o["student_answer"],
                     x["label"], x["kind"], o["reason"], o["feedback"], x["subject"])
        r["qid"] = x["qid"]
        rows.append(r)
    d = tmp_path / "grading_v2"
    d.mkdir()
    (d / "train_aug.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows),
                                       encoding="utf-8")
    (tmp_path / "datasets").mkdir()
    return {"dir": d, "datasets": tmp_path / "datasets", "items": {(x["source"], x["source_id"]): x for x in items},
            "rows": {r["id"]: r for r in rows}}


def _code(argv):
    try:
        ret = RS.main(argv)
    except SystemExit as e:
        return 0 if e.code is None else e.code
    return 0 if ret is None else ret


def _read_sheet(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        return reader.fieldnames, list(reader)


def test_make_writes_sheet_with_english_and_korean(v2_final):
    d = v2_final["dir"]
    assert _code(["make", "--dir", str(d), "--datasets-dir", str(v2_final["datasets"]), "--seed", "5"]) == 0
    header, rows = _read_sheet(d / "review_sheet.csv")
    assert header == COLUMNS
    assert len(rows) == 30
    assert sum(r["source"] == SAF for r in rows) >= 8
    for s in rows:
        src = v2_final["rows"][s["id"]]
        x = v2_final["items"][(src["source"], src["source_id"])]
        assert src["subject"] == "코딩" and src["kind"] not in CHEAT
        assert (s["q_en"], s["ref_en"], s["ans_en"]) == (x["q"], x["ref"], x["ans"])
        assert (s["q_ko"], s["ref_ko"], s["ans_ko"]) == (src["question"], src["model_answer"], src["student_answer"])
        assert s["kind"] == src["kind"] and s["reason"] == src["reason"] and s["feedback"] == src["feedback"]
        assert s["verdict"] == "" and s["note"] == ""


def test_make_same_seed_same_ids(v2_final, tmp_path):
    d = v2_final["dir"]
    args = ["make", "--dir", str(d), "--datasets-dir", str(v2_final["datasets"]), "--seed", "5"]
    _code(args)
    first = [r["id"] for r in _read_sheet(d / "review_sheet.csv")[1]]
    _code(args)
    assert [r["id"] for r in _read_sheet(d / "review_sheet.csv")[1]] == first


def test_make_refuses_dir_inside_repo(v2_final):
    target = TRAINING / "results" / "review_sheet_should_not_exist"
    assert _code(["make", "--dir", str(target), "--datasets-dir", str(v2_final["datasets"])]) == 2
    assert not target.exists()


# ---------- check CLI ----------

def _made_sheet(v2_final, verdicts):
    """make 로 만든 실제 시트(meta.json 포함)에 verdict 를 순서대로 채운다."""
    d = v2_final["dir"]
    assert _code(["make", "--dir", str(d), "--datasets-dir", str(v2_final["datasets"]), "--seed", "5"]) == 0
    sheet = d / "review_sheet.csv"
    with open(sheet, encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        fields, rows = reader.fieldnames, list(reader)
    assert len(rows) == len(verdicts) == 30
    for r, v in zip(rows, verdicts, strict=True):
        r["verdict"] = v
    with open(sheet, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)
    return sheet


def _write_sheet(path, verdicts):
    """make 를 거치지 않은 손으로 만든 시트(meta.json 없음). 종료 코드 2 검사에만 쓴다."""
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(COLUMNS)
        for i, v in enumerate(verdicts):
            w.writerow([f"{MOH}-M{i:03d}", MOH, "q", "r", "a", "문", "모", "답", "True", "paraphrase", "이유",
                        "피드백", v, "", "", "", ""])
    return path


@pytest.mark.parametrize("verdicts,expected", [
    pytest.param(["OK"] * 30, 0, id="all_ok"),
    pytest.param(["OK"] * 27 + ["LABEL_ERR", "TRANS_ERR", "EXPL_ERR"], 0, id="three_errors"),
    pytest.param(["OK"] * 26 + ["LABEL_ERR", "TRANS_ERR", "EXPL_ERR", "LABEL_ERR"], 1, id="four_errors"),
    pytest.param(["OK"] * 29 + [""], 2, id="blank_verdict"),
    pytest.param(["OK"] * 20 + ["LABEL_ERR"] * 9 + [""], 2, id="blank_wins_over_errors"),
    pytest.param(["OK"] * 29 + ["MAYBE"], 2, id="unknown_verdict"),
])
def test_check_exit_codes(v2_final, verdicts, expected):
    sheet = _made_sheet(v2_final, verdicts)
    assert _code(["check", "--sheet", str(sheet)]) == expected


def test_check_without_meta_exits_2(tmp_path, capsys):
    sheet = _write_sheet(tmp_path / "filled.csv", ["OK"] * 30)
    assert _code(["check", "--sheet", str(sheet)]) == 2
    assert "review_sheet.meta.json" in capsys.readouterr().err


def test_check_meta_deleted_after_make_exits_2(v2_final):
    sheet = _made_sheet(v2_final, ["OK"] * 30)
    (v2_final["dir"] / "review_sheet.meta.json").unlink()
    assert _code(["check", "--sheet", str(sheet)]) == 2


def test_check_cli_subprocess_exit_code(v2_final):
    sheet = _made_sheet(v2_final, ["OK"] * 26 + ["LABEL_ERR"] * 4)
    env = dict(os.environ)
    env.pop("PYTHONPATH", None)
    env["PYTHONIOENCODING"] = "utf-8"
    proc = subprocess.run([sys.executable, "-m", "data_tools.review_sheet", "check", "--sheet", str(sheet)],
                          cwd=TRAINING, capture_output=True, text=True, encoding="utf-8", env=env, timeout=120,
                          check=False)
    assert "No module named" not in proc.stderr, proc.stderr
    assert proc.returncode == 1, proc.stderr
