"""채점 학습 데이터 v2 (PT-88): build_grading_c --v2 (캐시 재사용·조립만·v1 폴더 불변) 테스트.

원천 parquet·LLM·네트워크를 쓰지 않는다. 데이터와 캐시는 모두 tmp_path 의 합성 데이터다.
"""

import csv
import json
from pathlib import Path

import pytest
from _v2_data import (
    MOH,
    SAF,
    SCI,
    FakeLLM,
    FakeRows,
    english_items,
    folder_hashes,
    load_jsonl,
    make_tables,
    qid_of,
    quizai_file,
    write_caches,
)

from data_tools import build_grading_c as BC
from data_tools import build_grading_train as B
from data_tools import compare_hybrid as H
from eval.run_eval import GRADE_SYSTEM

TRAINING = Path(__file__).resolve().parents[1]
DEV_FILE = TRAINING / "eval" / "grading_set.jsonl"

UNCACHED_MOH = "M00.A00"     # 정답 행, 캐시 없음 → LLM 으로 번역·작성해야 한다
SCI_TRANS_ONLY = "SC.0.01.1"  # 번역 캐시만 있음 → v2 에서 고르지 않는다
SCI_NONE = "SC.0.02.1"        # 캐시 없음 → v2 에서 고르지 않는다


@pytest.fixture
def env(tmp_path, monkeypatch):
    tables = make_tables()
    items = english_items(tables)
    monkeypatch.setattr(B, "rows", FakeRows(tables))
    v1 = tmp_path / "derived" / "grading_c"
    cached = [x for x in items if x["source_id"] not in {UNCACHED_MOH, SCI_NONE}]
    write_caches(v1, cached, trans_only={SCI_TRANS_ONLY})
    (v1 / "train_aug.jsonl").write_text('{"id": "v1-row"}\n', encoding="utf-8")
    (v1 / "stats.json").write_text('{"train": 893}\n', encoding="utf-8")
    datasets = tmp_path / "datasets"
    datasets.mkdir()
    quizai = quizai_file(tmp_path / "QuizAi.java", GRADE_SYSTEM)
    out = tmp_path / "derived" / "grading_v2"
    return {"tables": tables, "items": items, "by_key": {(x["source"], x["source_id"]): x for x in items},
            "v1": v1, "out": out, "datasets": datasets, "quizai": quizai, "monkeypatch": monkeypatch}


def _argv(env, *extra):
    return ["--v2", "--out", str(env["out"]), "--datasets-dir", str(env["datasets"]), "--quizai", str(env["quizai"]),
            "--eval-set", str(DEV_FILE), "--cache-from", str(env["v1"]), *extra]


def _install_llm(env, forbid=False):
    llm = FakeLLM(env["items"], forbid=forbid)
    env["monkeypatch"].setattr(H, "translate", llm.translate)
    env["monkeypatch"].setattr(H, "generate", llm.generate)
    return llm


def _stats(env):
    return json.loads((env["out"] / "stats.json").read_text(encoding="utf-8"))


# ---------- 수용 기준 15: 캐시 재사용과 LLM 호출 수 ----------

def test_v2_build_calls_llm_only_for_uncached_rows(env):
    llm = _install_llm(env)
    BC.main(_argv(env))
    x = env["by_key"][(MOH, UNCACHED_MOH)]
    assert sorted(llm.translated) == sorted([x["q"], x["ref"], x["ans"]])
    assert len(llm.generated) == 1
    stats = _stats(env)
    assert stats["llm_translate_calls"] == 3
    assert stats["llm_write_calls"] == 1


def test_v2_build_second_run_makes_no_llm_calls(env):
    _install_llm(env)
    BC.main(_argv(env))
    llm = _install_llm(env)
    BC.main(_argv(env))
    assert llm.translated == [] and llm.generated == []
    stats = _stats(env)
    assert stats["llm_translate_calls"] == 0 and stats["llm_write_calls"] == 0


def test_v2_build_uses_new_row_and_cached_rows(env):
    _install_llm(env)
    BC.main(_argv(env))
    train = load_jsonl(env["out"] / "train.jsonl")
    ids = {r["id"] for r in train}
    assert f"{MOH}-{UNCACHED_MOH}" in ids
    assert len({r["source"] for r in train}) == 3


# ---------- 수용 기준 14: v1 폴더 불변, 캐시는 v2 폴더로 복사해 이어 씀 ----------

def test_v2_build_leaves_v1_folder_byte_identical(env):
    before = folder_hashes(env["v1"])
    _install_llm(env)
    BC.main(_argv(env))
    assert folder_hashes(env["v1"]) == before
    assert sorted(p.name for p in env["v1"].iterdir()) == sorted(before)


def test_v2_build_copies_v1_caches_then_appends_in_out(env):
    v1_trans = (env["v1"] / "trans_cache.jsonl").read_text(encoding="utf-8")
    v1_write = (env["v1"] / "write_cache.jsonl").read_text(encoding="utf-8")
    _install_llm(env)
    BC.main(_argv(env))
    out_trans = (env["out"] / "trans_cache.jsonl").read_text(encoding="utf-8")
    out_write = (env["out"] / "write_cache.jsonl").read_text(encoding="utf-8")
    assert out_trans.startswith(v1_trans) and len(out_trans) > len(v1_trans)
    assert out_write.startswith(v1_write) and len(out_write) > len(v1_write)
    for name in ("train.jsonl", "rejected.jsonl", "stats.json"):
        assert (env["out"] / name).is_file()
        assert not (env["v1"] / name).exists() or name == "stats.json"


# ---------- 과학: 캐시가 모두 있는 행만 (LLM 0회) ----------

def test_v2_build_never_sends_science_rows_to_llm(env):
    llm = _install_llm(env)
    BC.main(_argv(env))
    sci_text = {t for x in env["items"] if x["source"] == SCI for t in (x["q"], x["ref"], x["ans"])}
    assert not sci_text & set(llm.translated)
    train = load_jsonl(env["out"] / "train.jsonl")
    rejected = load_jsonl(env["out"] / "rejected.jsonl")
    for rows in (train, rejected):
        sids = {r["source_id"] for r in rows if r["source"] == SCI}
        assert SCI_TRANS_ONLY not in sids and SCI_NONE not in sids
    assert any(r["source"] == SCI for r in train)


# ---------- 수용 기준 13: --assemble-only ----------

def test_assemble_only_never_calls_llm_and_marks_missing(env):
    llm = _install_llm(env, forbid=True)
    BC.main(_argv(env, "--assemble-only"))
    assert llm.translated == [] and llm.generated == []
    rejected = load_jsonl(env["out"] / "rejected.jsonl")
    assert {"source": MOH, "source_id": UNCACHED_MOH, "error": "missing"} in [
        {k: r[k] for k in ("source", "source_id", "error")} for r in rejected]
    stats = _stats(env)
    assert stats["llm_translate_calls"] == 0 and stats["llm_write_calls"] == 0
    assert stats["train"] > 0


# ---------- 행 형식: qid, 시스템 프롬프트, SAF split ----------

def test_v2_rows_have_qid_from_english_question_and_grade_system(env):
    _install_llm(env)
    BC.main(_argv(env))
    train = load_jsonl(env["out"] / "train.jsonl")
    assert train
    for r in train:
        x = env["by_key"][(r["source"], r["source_id"])]
        assert r["qid"] == qid_of(x["source"], x["q"]), r["id"]
        assert r["messages"][0]["content"] == GRADE_SYSTEM


def test_v2_saf_rows_come_from_train_or_validation_ids(env):
    _install_llm(env)
    BC.main(_argv(env))
    t = env["tables"]
    train_ids = {r["id"] for r in t[B.SOURCE_FILES["saf"]]}
    val_ids = {r["id"] for r in t[B.SOURCE_FILES["saf_validation"]]}
    saf = {r["source_id"] for r in load_jsonl(env["out"] / "train.jsonl") if r["source"] == SAF}
    assert saf <= train_ids | val_ids
    assert saf & val_ids, "validation split 행이 학습에 들어와야 한다"


def test_v2_default_inject_ratio_is_015(env):
    _install_llm(env)
    BC.main(_argv(env))
    assert _stats(env)["inject_ratio"] == pytest.approx(0.15)
    assert BC.V2_INJECT_RATIO == pytest.approx(0.15)


# ---------- 수용 기준 9: 핵심 누락 정답 행 제외(검수 OK·WEAK_KEEP 예외) ----------

def test_v2_build_drops_short_true_rows_except_reviewed(env, tmp_path):
    rel = B.SOURCE_FILES["mohler_open"]
    tables = dict(env["tables"])
    base = tables[rel][0]
    short = {**base, "id": "M00.SHORT", "student_answer": "just a list", "score_avg": 5.0,
             "score_grader_1": 5.0, "score_grader_2": 5.0}
    kept = {**base, "id": "M00.SHORTOK", "student_answer": "a short list", "score_avg": 5.0,
            "score_grader_1": 5.0, "score_grader_2": 5.0}
    tables[rel] = list(tables[rel]) + [short, kept]
    env["monkeypatch"].setattr(B, "rows", FakeRows(tables))
    items = english_items(tables)
    write_caches(env["v1"], [x for x in items if x["source_id"] not in {UNCACHED_MOH, SCI_NONE}],
                 trans_only={SCI_TRANS_ONLY})
    review = tmp_path / "review_true_mohler.csv"
    with open(review, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["id", "scope", "verdict", "reason"])
        w.writerow([f"{MOH}-M00.SHORTOK", "row", "WEAK_KEEP", "합성 사유"])
    env["items"] = items
    _install_llm(env)
    BC.main(_argv(env, "--review-true", str(review)))
    stats = _stats(env)
    assert stats["missing_core_dropped"] == 1
    assert stats["missing_core_dropped_ids"] == [f"{MOH}-M00.SHORT"]
    ids = {r["id"] for r in load_jsonl(env["out"] / "train.jsonl")}
    assert f"{MOH}-M00.SHORT" not in ids
    assert f"{MOH}-M00.SHORTOK" in ids


# ---------- 저장소 밖 출력 ----------

def test_v2_out_inside_repo_exits_2_before_writing(env):
    target = TRAINING / "results" / "grading_v2_should_not_exist"
    args = _argv(env)
    args[args.index("--out") + 1] = str(target)
    with pytest.raises(SystemExit) as exc:
        BC.main(args)
    assert exc.value.code == 2
    assert not target.exists()
