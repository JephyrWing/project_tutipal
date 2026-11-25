"""채점 학습 데이터 v2 (PT-88) 통합 테스트: build --v2 → clean --sources v2 → augment (v2 옵션).

합성 원천 표와 v1 흉내 캐시 폴더만 쓴다(--assemble-only 라 LLM 을 부르지 않는다).
최종 train_aug.jsonl 이 eval.trainmix 기준과 dev 누수 검사(check_leaks)를 통과하고, 수용 기준의 불변 조건을
지키는지 본다. 봉인 세트는 다루지 않는다.
"""

import hashlib
import json
import socket
import urllib.request
from collections import Counter
from pathlib import Path

import pytest
from _v2_data import (
    MOH,
    SAF,
    SCI,
    FakeLLM,
    FakeRows,
    english_items,
    file_sha,
    folder_hashes,
    load_jsonl,
    make_tables,
    qid_of,
    quizai_file,
)
from _v2_data import (
    write_caches_v3 as write_caches,  # PT-88: 새 캐시 키 줄도 쓴다
)

from data_tools import augment_tricks as A
from data_tools import build_grading_c as BC
from data_tools import build_grading_train as B
from data_tools import clean_grading_c as C
from data_tools import compare_hybrid as H
from eval import trainmix
from eval.leakcheck import TRICK_KINDS, check_leaks
from eval.run_eval import GRADE_SYSTEM

TRAINING = Path(__file__).resolve().parents[1]
DEV_FILE = TRAINING / "eval" / "grading_set.jsonl"
CAPS = {MOH: 12, SAF: 10, SCI: 10}
MAX_SCIENCE = 12
# 오답 행(close_ended, 문제당 4행이라 상한에 걸리지 않음). reason 에 금지 용어를 넣어 둔다.
TERM_ROW = "C00.A01"


def _blocked(*args, **kwargs):
    raise RuntimeError("network access is disabled in tests")


@pytest.fixture(scope="module")
def pipeline(tmp_path_factory):
    mp = pytest.MonkeyPatch()
    try:
        # 모듈 범위 fixture 는 conftest 의 함수 범위 네트워크 차단보다 먼저 돌므로 여기서도 막는다
        mp.setattr(urllib.request, "urlopen", _blocked)
        mp.setattr(socket, "create_connection", _blocked)
        mp.setattr(socket.socket, "connect", _blocked)
        tmp = tmp_path_factory.mktemp("v2pipe")
        tables = make_tables(n_mohler_q=8, per_mohler=16, n_saf_q=5, per_saf_train=9, per_saf_val=5,
                             n_sci_q=4, per_sci=10)
        items = english_items(tables)
        mp.setattr(B, "rows", FakeRows(tables))
        llm = FakeLLM(items, forbid=True)
        mp.setattr(H, "translate", llm.translate)
        mp.setattr(H, "generate", llm.generate)
        anchor = next(x for x in items if x["source"] == MOH and x["source_id"] == "M05.A04")
        mp.setattr(A, "EXCLUDE_QUESTIONS_OF", frozenset({f"{MOH}-{anchor['source_id']}"}))
        mp.setattr(A, "EXCLUDE_ROWS", frozenset({f"{MOH}-M06.A00"}))

        v1 = tmp / "derived" / "grading_c"
        write_caches(v1, items, reasons={TERM_ROW: "핵심 개념인 핑거링 동작이 누락되어 있다."})
        (v1 / "train_aug.jsonl").write_text('{"id": "v1-row"}\n', encoding="utf-8")
        v1_before = folder_hashes(v1)
        dev_before = file_sha(DEV_FILE)

        datasets = tmp / "datasets"
        datasets.mkdir()
        quizai = quizai_file(tmp / "QuizAi.java", GRADE_SYSTEM)
        v2 = tmp / "derived" / "grading_v2"
        BC.main(["--v2", "--out", str(v2), "--datasets-dir", str(datasets), "--quizai", str(quizai),
                 "--eval-set", str(DEV_FILE), "--cache-from", str(v1), "--assemble-only"])
        C.main(["--dir", str(v2), "--datasets-dir", str(datasets), "--eval-set", str(DEV_FILE), "--sources", "v2",
                "--cap-by-source", *(f"{k}={v}" for k, v in CAPS.items())])
        A.main(["--dir", str(v2), "--eval-set", str(DEV_FILE), "--datasets-dir", str(datasets),
                "--max-science", str(MAX_SCIENCE), "--base-subjects", "코딩", "--per-kind", "4"])
        yield {"tmp": tmp, "tables": tables, "items": {(x["source"], x["source_id"]): x for x in items},
               "anchor": anchor, "v1": v1, "v1_before": v1_before, "dev_before": dev_before, "v2": v2,
               "final": load_jsonl(v2 / "train_aug.jsonl"), "llm": llm}
    finally:
        mp.undo()


def _base_key(r):
    """꼼수·보강 행도 원본 (source, source_id) 로 되돌린다."""
    if r["source"] == "augment":
        src = r["source_id"]
        for s in (MOH, SAF, SCI):
            if src.startswith(s + "-"):
                return s, src[len(s) + 1:]
        raise AssertionError(src)
    return r["source"], r["source_id"]


# ---------- 수용 기준 1: trainmix 통과 ----------

def test_final_passes_trainmix(pipeline, capsys):
    assert trainmix.main(["--train", str(pipeline["v2"] / "train_aug.jsonl")]) == 0
    stats = trainmix.mix_stats(pipeline["final"])
    assert stats["coding_share"] >= 0.6 and stats["trick_share"] <= 0.15


# ---------- 수용 기준 5: dev 누수 0 ----------

def test_final_has_no_dev_leaks(pipeline):
    dev = load_jsonl(DEV_FILE)
    assert check_leaks(pipeline["final"], dev)["failures"] == []


# ---------- 수용 기준 13: LLM 0회 ----------

def test_pipeline_called_no_llm(pipeline):
    assert pipeline["llm"].translated == [] and pipeline["llm"].generated == []


# ---------- 수용 기준 3: SAF id 는 train·validation 만 ----------

def test_final_saf_ids_only_train_or_validation(pipeline):
    t = pipeline["tables"]
    allowed = {r["id"] for r in t[B.SOURCE_FILES["saf"]] + t[B.SOURCE_FILES["saf_validation"]]}
    saf = {_base_key(r)[1] for r in pipeline["final"] if _base_key(r)[0] == SAF}
    assert saf and saf <= allowed


# ---------- 수용 기준 4: 영어 문제당 상한(꼼수 제외) ----------

def test_final_rows_per_english_question_within_caps(pipeline):
    per_q = Counter((r["source"], r["qid"]) for r in pipeline["final"] if r["kind"] not in TRICK_KINDS)
    for (source, qid), n in per_q.items():
        assert n <= CAPS[source], (source, qid, n)


# ---------- 수용 기준 6: 금지 용어 0 ----------

def test_final_has_no_forbidden_terms(pipeline):
    for r in pipeline["final"]:
        for field in ("question", "model_answer", "student_answer", "reason", "feedback"):
            for term in C.FORBIDDEN_TERMS:
                assert term not in r[field], (r["id"], field, term)
    assert f"{MOH}-{TERM_ROW}" not in {r["id"] for r in pipeline["final"]}


def test_clean_stats_record_term_drop(pipeline):
    stats = json.loads((pipeline["v2"] / "clean_stats.json").read_text(encoding="utf-8"))
    assert stats["term_dropped"] == {"핑거링": 1}


# ---------- 수용 기준 7: 검수 제외(영어 문제 기준) ----------

def test_final_excludes_review_rows_and_english_pair(pipeline):
    ids = {r["id"] for r in pipeline["final"]}
    assert f"{MOH}-M06.A00" not in ids
    a = pipeline["anchor"]
    for r in pipeline["final"]:
        x = pipeline["items"][_base_key(r)]
        assert (x["q"], x["ref"]) != (a["q"], a["ref"]), r["id"]


# ---------- 수용 기준 8: Mohler 정답 점수 ----------

def test_final_mohler_true_rows_meet_score_rule(pipeline):
    t = pipeline["tables"]
    by_id = {r["id"]: r for r in t[B.SOURCE_FILES["mohler_open"]] + t[B.SOURCE_FILES["mohler_close"]]}
    for r in pipeline["final"]:
        if r["source"] != MOH:
            continue
        s = by_id[r["source_id"]]
        assert not 3.5 < s["score_avg"] < 4.5, r["id"]
        if r["label"] is True:
            assert s["score_avg"] >= 4.5 and s["score_grader_1"] >= 4 and s["score_grader_2"] >= 4


# ---------- 수용 기준 11·2: 과학 상한, 구성 통계 ----------

def test_final_science_within_limit_and_stats(pipeline):
    final = pipeline["final"]
    assert sum(r["subject"] == "과학" for r in final) <= MAX_SCIENCE
    stats = json.loads((pipeline["v2"] / "aug_stats.json").read_text(encoding="utf-8"))
    comp = stats["composition"]
    assert comp["total"] == len(final)
    assert comp["science"] == sum(r["subject"] == "과학" for r in final)


# ---------- 수용 기준 12: 새 꼼수 바탕은 코딩 ----------

def test_final_new_trick_bases_are_coding(pipeline):
    new = [r for r in pipeline["final"] if r["source"] == "augment"]
    assert new
    for r in new:
        assert pipeline["items"][_base_key(r)]["subject"] == "코딩"
        assert r["subject"] == "코딩"


# ---------- 수용 기준 14·18: v1·dev 불변, 산출물은 v2 폴더에만 ----------

def test_v1_folder_and_dev_set_unchanged(pipeline):
    assert folder_hashes(pipeline["v1"]) == pipeline["v1_before"]
    assert file_sha(DEV_FILE) == pipeline["dev_before"]


def test_outputs_only_in_v2_folder(pipeline):
    names = {p.name for p in pipeline["v2"].iterdir()}
    assert {"train.jsonl", "train_clean.jsonl", "train_aug.jsonl", "trans_cache.jsonl", "write_cache.jsonl",
            "stats.json", "clean_stats.json", "aug_stats.json"} <= names
    assert set(pipeline["v1_before"]) == {p.name for p in pipeline["v1"].iterdir()}


def test_all_rows_use_grade_system_and_have_qid(pipeline):
    for r in pipeline["final"]:
        assert r["messages"][0]["content"] == GRADE_SYSTEM
        x = pipeline["items"][_base_key(r)]
        assert r["qid"] == qid_of(x["source"], x["q"]), r["id"]


def test_final_qid_format(pipeline):
    for r in pipeline["final"]:
        source, digest = r["qid"].split("|")
        assert source in (MOH, SAF, SCI) and len(digest) == 16
        int(digest, 16)
    q = pipeline["anchor"]["q"]
    assert qid_of(MOH, q).endswith(hashlib.sha256(q.encode("utf-8")).hexdigest()[:16])
