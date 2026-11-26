"""PT-88 v4 실제 데이터 측정 테스트: AC-9(오탐·탈락률·Mohler 행 수), AC-10(v4 결과 구성·누수·읽기 전용).

저장소 밖 데이터 폴더가 없으면 건너뛴다. 기본 위치는 홈의 tutipal_datasets 이고,
TUTIPAL_DATASETS_DIR(원천 datasets 폴더) / TUTIPAL_DERIVED_DIR(derived 폴더)로 바꿀 수 있다.
sealed 평가 세트는 읽지 않는다(누수 검사는 dev 만).
"""

import csv
import json
import os
from pathlib import Path

import pytest

from data_tools import build_grading_c as BC
from data_tools import build_grading_train as B
from data_tools.clean_grading_c import CHEAT
from data_tools.review_sheet import file_sha256
from eval import leakcheck, trainmix
from eval.run_eval import GRADE_SYSTEM

TRAINING = Path(__file__).resolve().parents[1]
DEV_FILE = TRAINING / "eval" / "grading_set.jsonl"
HOME_DATA = Path.home() / "tutipal_datasets"
REAL_DATASETS = Path(os.environ.get("TUTIPAL_DATASETS_DIR") or HOME_DATA / "datasets")
DERIVED = Path(os.environ.get("TUTIPAL_DERIVED_DIR") or HOME_DATA / "derived")
V3 = DERIVED / "grading_v3"
V4 = DERIVED / "grading_v4"
HUMAN_V3 = V3 / "review_sheet_human_20261126.csv"
MOH = "MohlerASAG"

MAX_OK_ROWS_FLAGGED = 2
MAX_MOHLER_DROP_RATE = 0.15
MIN_V4_MOHLER = 600

needs_human = pytest.mark.skipif(not HUMAN_V3.is_file(), reason="v3 사람 검수 시트 없음")
needs_v3 = pytest.mark.skipif(not ((V3 / "train_aug.jsonl").is_file() and REAL_DATASETS.is_dir()),
                              reason="v3 학습 데이터 또는 원천 datasets 폴더 없음")
needs_v4 = pytest.mark.skipif(not (V4 / "train_aug.jsonl").is_file(), reason="v4 학습 데이터가 아직 없음")


def _jsonl(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def _flag(x, o, label, kind):
    return BC.trans_error(x, o) or BC.check(o, label, expl=True, kind=kind,
                                            en="\n".join((x["q"], x["ref"], x["ans"])))


# ---------- AC-9: 오탐 (v3 사람 검수 OK 16행 중 v4 규칙에 걸리는 행 ≤ 2) ----------

@needs_human
def test_v3_ok_rows_flagged_by_v4_rules_at_most_two():
    with open(HUMAN_V3, encoding="utf-8-sig", newline="") as f:
        ok = [r for r in csv.DictReader(f) if r["verdict"] == "OK"]
    assert len(ok) == 16
    flagged = {}
    for r in ok:
        x = {"q": r["q_en"], "ref": r["ref_en"], "ans": r["ans_en"]}
        o = {"question": r["q_ko"], "model_answer": r["ref_ko"], "student_answer": r["ans_ko"],
             "reason": r["reason"], "feedback": r["feedback"]}
        rule = _flag(x, o, r["label"] == "TRUE", r["kind"])
        if rule:
            flagged[r["id"]] = rule
    assert len(flagged) <= MAX_OK_ROWS_FLAGGED, flagged


# ---------- AC-9: v3 Mohler 비꼼수 행에 v4 번역 규칙을 돌린 탈락률 ≤ 15% ----------

def _v3_mohler_non_trick():
    origin = {(x["source"], x["source_id"]): x for x in B.load_sources_v2(REAL_DATASETS)}
    rows = [r for r in _jsonl(V3 / "train_aug.jsonl") if r["source"] == MOH and r["kind"] not in CHEAT]
    return [(origin[(r["source"], r["source_id"])], r) for r in rows if (r["source"], r["source_id"]) in origin]


@needs_v3
def test_v4_translation_rules_drop_at_most_15_percent_of_v3_mohler():
    pairs = _v3_mohler_non_trick()
    assert pairs
    dropped = [r["id"] for x, r in pairs if BC.trans_error(x, r)]
    assert len(dropped) / len(pairs) <= MAX_MOHLER_DROP_RATE, f"{len(dropped)}/{len(pairs)}"


@needs_v3
def test_v3_mohler_after_v4_translation_rules_leaves_at_least_600():
    """생성 전 추정: v3 Mohler 비꼼수 행 중 v4 번역 규칙을 통과하는 행이 600 이상이어야 v4 목표가 가능하다."""
    pairs = _v3_mohler_non_trick()
    assert sum(1 for x, r in pairs if not BC.trans_error(x, r)) >= MIN_V4_MOHLER


# ---------- AC-9 / AC-10: v4 결과 (생성 뒤에만) ----------

@needs_v4
def test_v4_mohler_non_trick_rows_at_least_600():
    rows = _jsonl(V4 / "train_aug.jsonl")
    assert sum(1 for r in rows if r["source"] == MOH and r["kind"] not in CHEAT) >= MIN_V4_MOHLER


@needs_v4
def test_v4_trainmix_passes():
    stats = trainmix.mix_stats(_jsonl(V4 / "train_aug.jsonl"))
    assert trainmix.mix_problems(stats, 0.6, 0.15) == []


@needs_v4
def test_v4_has_no_dev_leak():
    train = leakcheck.load_rows(V4 / "train_aug.jsonl")
    dev = leakcheck.load_rows(DEV_FILE)
    assert leakcheck.check_leaks(train, dev, None)["failures"] == []


@needs_v4
def test_v4_messages_use_backend_grade_system():
    rows = _jsonl(V4 / "train_aug.jsonl")
    assert all(r["messages"][0]["role"] == "system" and r["messages"][0]["content"] == GRADE_SYSTEM for r in rows)


@needs_v4
def test_v4_stats_record_pin_and_saf_drops():
    stats = json.loads((V4 / "stats.json").read_text(encoding="utf-8"))
    assert "SAF-communication-networks" in stats["drops_by_source"]
    assert stats["pin"]["qids"] >= 1


# ---------- AC-10: grading_v3 는 읽기만 ----------

@pytest.mark.skipif(not (V3 / "review_sheet.meta.json").is_file(), reason="v3 시트 meta 없음")
def test_v3_train_aug_is_unchanged_since_v3_sheet():
    meta = json.loads((V3 / "review_sheet.meta.json").read_text(encoding="utf-8"))
    assert file_sha256(V3 / meta["train_file"]) == meta["train_sha256"]
