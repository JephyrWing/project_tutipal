"""채점 학습 데이터 v2 (PT-88): augment_tricks 의 영어 기준 검수 제외·과학 다운샘플·바탕 과목 제한 테스트.

데이터는 모두 합성이다. 누수 검사는 가짜 check_leaks 를 쓰고, CLI 테스트에서만 실제 eval.leakcheck 를 쓴다.
"""

import copy
import json
from collections import Counter
from pathlib import Path

import pytest
from _v2_data import (
    MOH,
    SAF,
    SCI,
    FakeRows,
    english_items,
    make_tables,
    qid_of,
    written,
)

from data_tools import augment_tricks as A
from data_tools import build_grading_train as B
from eval.leakcheck import normalize_n2

TRAINING = Path(__file__).resolve().parents[1]
DEV_FILE = TRAINING / "eval" / "grading_set.jsonl"


def _r(source, sid, *, subject="코딩", q="합성 문항: 가상의 구조 규칙을 설명하세요.", ref="합성 모범 답안: 순서를 지킨다.",
       ans=None, label=True, kind=None, q_en="Q?", rid=None):
    kind = kind or ("paraphrase" if label else "wrong")
    ans = ans or (f"학생 답안 {sid}번입니다 새 항목을 끝에 붙이고 순서를 지킵니다" if label
                  else f"학생 답안 {sid}번입니다 항목을 매번 무작위로 섞어 버립니다")
    reason = "모범 답안과 같은 뜻이다." if label else "핵심 개념이 누락되었다."
    feedback = "맞았어요. 핵심을 담았어요." if label else "핵심 개념을 다시 확인해 보세요."
    r = B.record(rid or f"{source}-{sid}", {"source": source, "source_id": sid}, q, ref, ans, label, kind,
                 reason, feedback, subject)
    r["qid"] = qid_of(source, q_en)
    return r


def no_leaks(train, dev, sealed=None):
    return {"failures": [], "review": [], "review_total": {}, "sealed": "not_checked"}


def flag_ids(ids):
    def fake(train, dev, sealed=None):
        fails = [{"rule": "S1", "pair": "train-dev", "a_id": r["id"], "b_id": "g-001", "score": 1.0,
                  "reason": "n1_equal"} for r in train if r["id"] in ids]
        return {"failures": fails, "review": [], "review_total": {}, "sealed": "not_checked"}
    return fake


DEV = [{"id": "g-001", "question": "dev", "model_answer": "dev", "student_answer": "dev", "kind": "wrong"}]


def coding_pool(n_q=20, n_true=2, n_false=3, source=MOH):
    rows = []
    for q in range(n_q):
        for j in range(n_true + n_false):
            rows.append(_r(source, f"C{q:02d}.{j}", q=f"합성 코딩 문항 {q}번: 가상의 구조 규칙을 설명하세요.",
                           ref=f"합성 코딩 모범 답안 {q}번: 순서를 지킨다.", label=j < n_true,
                           q_en=f"Coding question {q}?"))
    return rows


def science_pool(strata, n_q=4):
    """strata: [(kind, label, 개수)]. 문제는 n_q 개에 돌아가며 배정."""
    rows, i = [], 0
    for kind, label, n in strata:
        for _ in range(n):
            q = i % n_q
            rows.append(_r(SCI, f"S{i:03d}", subject="과학", q=f"합성 과학 문항 {q}번: 왜 따뜻해지나요?",
                           ref=f"합성 과학 모범 답안 {q}번: 열을 흡수한다.", label=label, kind=kind,
                           ans=(f"학생 답안 S{i:03d}번입니다 열을 받아서 따뜻해집니다" if label else
                                f"학생 답안 S{i:03d}번입니다 마법으로 차가워집니다 {kind}"),
                           q_en=f"Science question {q}?"))
            i += 1
    return rows


CODING = frozenset({"코딩"})


def run(train, *, seed=1, per_kind=4, check_leaks=no_leaks, **kw):
    return A.run(copy.deepcopy(train), DEV, seed=seed, per_kind=per_kind, check_leaks=check_leaks,
                 normalize_n2=normalize_n2, **kw)


# ---------- 수용 기준 7: 영어 (문제, 모범답안) 기준 검수 제외 ----------

def test_review_drop_uses_english_pair_across_translations(monkeypatch):
    monkeypatch.setattr(A, "EXCLUDE_ROWS", frozenset())
    monkeypatch.setattr(A, "EXCLUDE_QUESTIONS_OF", frozenset({f"{MOH}-X.1"}))
    anchor = _r(MOH, "X.1", q="번역 가: 큐는 무엇인가요?")
    other_translation = _r(MOH, "X.2", q="번역 나: 큐란 무엇입니까?", ref="다르게 옮긴 모범 답안")
    other_ref = _r(MOH, "X.3", q="번역 가: 큐는 무엇인가요?")
    other_q = _r(MOH, "Y.1", q="다른 문제")
    origin = {(MOH, "X.1"): ("What is a queue?", "FIFO structure."),
              (MOH, "X.2"): ("What is a queue?", "FIFO structure."),
              (MOH, "X.3"): ("What is a queue?", "A different reference."),
              (MOH, "Y.1"): ("What is a stack?", "LIFO structure.")}
    kept, info = A.review_drop([anchor, other_translation, other_ref, other_q], origin=origin)
    assert sorted(r["id"] for r in kept) == [f"{MOH}-X.3", f"{MOH}-Y.1"]
    assert info["questions"] == [f"{MOH}-X.1", f"{MOH}-X.2"]


def test_review_drop_english_pair_works_when_anchor_row_is_absent(monkeypatch):
    monkeypatch.setattr(A, "EXCLUDE_ROWS", frozenset())
    monkeypatch.setattr(A, "EXCLUDE_QUESTIONS_OF", frozenset({f"{MOH}-X.1"}))
    same = _r(MOH, "X.2", q="번역 나: 큐란 무엇입니까?")
    keep = _r(MOH, "Y.1", q="다른 문제")
    origin = {(MOH, "X.1"): ("What is a queue?", "FIFO structure."),
              (MOH, "X.2"): ("What is a queue?", "FIFO structure."),
              (MOH, "Y.1"): ("What is a stack?", "LIFO structure.")}
    kept, info = A.review_drop([same, keep], origin=origin)
    assert [r["id"] for r in kept] == [f"{MOH}-Y.1"]
    assert info["questions"] == [f"{MOH}-X.2"]


def test_review_drop_english_pair_resolves_cheat_suffixed_anchor(monkeypatch):
    monkeypatch.setattr(A, "EXCLUDE_ROWS", frozenset())
    monkeypatch.setattr(A, "EXCLUDE_QUESTIONS_OF", frozenset({f"{SAF}-abc-injection"}))
    same = _r(SAF, "def", q="번역 다른 문장")
    origin = {(SAF, "abc"): ("How are frames resent?", "After a timeout."),
              (SAF, "def"): ("How are frames resent?", "After a timeout.")}
    kept, _ = A.review_drop([same], origin=origin)
    assert kept == []


def test_review_drop_without_origin_keeps_v1_behaviour(monkeypatch):
    monkeypatch.setattr(A, "EXCLUDE_ROWS", frozenset())
    monkeypatch.setattr(A, "EXCLUDE_QUESTIONS_OF", frozenset({f"{MOH}-X.1"}))
    anchor = _r(MOH, "X.1", q="번역 가")
    other_translation = _r(MOH, "X.2", q="번역 나")
    kept, _ = A.review_drop([anchor, other_translation])
    assert [r["id"] for r in kept] == [f"{MOH}-X.2"]


def test_review_drop_excludes_rows_listed_in_exclude_rows(monkeypatch):
    monkeypatch.setattr(A, "EXCLUDE_ROWS", frozenset({f"{MOH}-X.1"}))
    monkeypatch.setattr(A, "EXCLUDE_QUESTIONS_OF", frozenset())
    rows = [_r(MOH, "X.1"), _r(MOH, "X.2")]
    origin = {(MOH, "X.1"): ("Q?", "R."), (MOH, "X.2"): ("Q?", "R.")}
    kept, _ = A.review_drop(rows, origin=origin)
    assert [r["id"] for r in kept] == [f"{MOH}-X.2"]


# ---------- 수용 기준 11: 과학 다운샘플 ----------

def test_largest_remainder_allocation():
    assert A.largest_remainder({"a": 5, "b": 4, "c": 1}, 7) == {"a": 3, "b": 3, "c": 1}


@pytest.mark.parametrize("counts,total", [
    ({"a": 9, "b": 7, "c": 3, "d": 1}, 7), ({"a": 100, "b": 1}, 10), ({"a": 3, "b": 3, "c": 3}, 4),
    ({"a": 50, "b": 25, "c": 13, "d": 7, "e": 5}, 33),
])
def test_largest_remainder_sums_to_total_within_one(counts, total):
    alloc = A.largest_remainder(counts, total)
    n = sum(counts.values())
    assert sum(alloc.values()) == total
    for k, c in counts.items():
        assert abs(alloc.get(k, 0) - total * c / n) < 1, k
        assert alloc.get(k, 0) <= c


STRATA = [("paraphrase", True, 9), ("wrong", False, 7), ("partial", False, 3), ("injection_with_answer", False, 1)]


def _strata_counts(rows):
    return Counter((r["kind"], r["label"]) for r in rows if r["subject"] == "과학")


def test_downsample_science_allocates_proportionally():
    rows = science_pool(STRATA) + coding_pool(n_q=2)
    kept, dropped = A.downsample_science(rows, 7, seed=3)
    assert _strata_counts(kept) == {("paraphrase", True): 3, ("wrong", False): 3, ("partial", False): 1}
    assert len(dropped) == 13
    assert sum(r["subject"] == "코딩" for r in kept) == 10, "과학이 아닌 행은 건드리지 않는다"


def test_downsample_science_error_per_stratum_at_most_one():
    strata = [("paraphrase", True, 41), ("wrong", False, 23), ("partial", False, 17), ("unknown", False, 5),
              ("tag_escape", False, 3)]
    rows = science_pool(strata)
    n = len(rows)
    kept, _ = A.downsample_science(rows, 25, seed=9)
    got = _strata_counts(kept)
    assert sum(got.values()) == 25
    for kind, label, c in strata:
        assert abs(got.get((kind, label), 0) - 25 * c / n) <= 1, kind


def test_downsample_science_is_deterministic_and_order_independent():
    rows = science_pool(STRATA) + coding_pool(n_q=2)
    a, _ = A.downsample_science(rows, 7, seed=3)
    b, _ = A.downsample_science(list(reversed(rows)), 7, seed=3)
    c, _ = A.downsample_science(rows, 7, seed=3)
    assert {r["id"] for r in a} == {r["id"] for r in b} == {r["id"] for r in c}


def test_downsample_science_noop_when_under_limit():
    rows = science_pool(STRATA)
    kept, dropped = A.downsample_science(rows, 250, seed=3)
    assert {r["id"] for r in kept} == {r["id"] for r in rows} and dropped == []


def test_downsample_science_does_not_mutate_input():
    rows = science_pool(STRATA)
    before = copy.deepcopy(rows)
    A.downsample_science(rows, 5, seed=1)
    assert rows == before


# ---------- run(): 순서·통계 ----------

def test_run_max_science_limits_science_rows_and_records_stats():
    train = coding_pool() + science_pool(STRATA)
    final, _, stats = run(train, max_science=7, base_subjects=CODING)
    assert sum(r["subject"] == "과학" for r in final) == 7
    assert stats["science_before"] == 20 and stats["science_after"] == 7
    assert len(stats["science_dropped_ids"]) == 13
    assert stats["max_science"] == 7


def test_run_without_max_science_keeps_all_science():
    train = coding_pool() + science_pool(STRATA)
    final, _, _ = run(train)
    assert sum(r["subject"] == "과학" and r["source"] != "augment" for r in final) == 20


def test_run_leak_drop_happens_before_downsample():
    sci = science_pool(STRATA)
    flagged = {r["id"] for r in sci[:5]}
    final, _, stats = run(coding_pool() + sci, max_science=12, check_leaks=flag_ids(flagged), base_subjects=CODING)
    assert sum(r["subject"] == "과학" for r in final) == 12
    assert not flagged & {r["id"] for r in final}
    assert stats["science_before"] == 15


def test_run_review_drop_happens_before_downsample(monkeypatch):
    sci = science_pool(STRATA)
    excluded = frozenset(r["id"] for r in sci[:6])
    monkeypatch.setattr(A, "EXCLUDE_ROWS", excluded)
    monkeypatch.setattr(A, "EXCLUDE_QUESTIONS_OF", frozenset())
    final, _, stats = run(coding_pool() + sci, max_science=12, base_subjects=CODING)
    assert sum(r["subject"] == "과학" for r in final) == 12
    assert not excluded & {r["id"] for r in final}
    assert stats["science_before"] == 14 and stats["science_after"] == 12


# ---------- 수용 기준 10: QUESTION_FIXES 는 다운샘플보다 먼저 ----------

def test_question_fix_applies_even_if_anchor_is_downsampled(monkeypatch):
    marker = " (정답: 열 흡수)"
    sci = science_pool([("paraphrase", True, 4), ("wrong", False, 4)], n_q=1)
    for r in sci:
        r.update(B.record(r["id"], r, r["question"] + marker, r["model_answer"], r["student_answer"], r["label"],
                          r["kind"], r["reason"], r["feedback"], r["subject"]))
    anchor = sci[0]["id"]
    monkeypatch.setattr(A, "QUESTION_FIXES", {anchor: ((marker, ""),)})
    anchor_dropped_once = False
    for seed in range(12):
        final, _, stats = run(coding_pool() + sci, seed=seed, max_science=2, base_subjects=CODING)
        science = [r for r in final if r["subject"] == "과학"]
        assert len(science) == 2
        assert all(marker not in r["question"] for r in final)
        assert stats["question_fix_missing_ids"] == []
        anchor_dropped_once |= anchor not in {r["id"] for r in final}
    assert anchor_dropped_once, "대표 행이 다운샘플로 빠지는 경우가 한 번은 있어야 검사가 의미 있다"


def test_question_fix_missing_ids_not_increased_by_downsample(monkeypatch):
    marker = " (정답: 열 흡수)"
    sci = science_pool([("paraphrase", True, 4), ("wrong", False, 4)], n_q=1)
    for r in sci:
        r.update(B.record(r["id"], r, r["question"] + marker, r["model_answer"], r["student_answer"], r["label"],
                          r["kind"], r["reason"], r["feedback"], r["subject"]))
    monkeypatch.setattr(A, "QUESTION_FIXES", {sci[0]["id"]: ((marker, ""),), "SciEntsBank-NOT-THERE": ((marker, ""),)})
    _, _, full = run(coding_pool() + sci)
    _, _, small = run(coding_pool() + sci, max_science=2)
    assert small["question_fix_missing_ids"] == full["question_fix_missing_ids"] == ["SciEntsBank-NOT-THERE"]


# ---------- 수용 기준 12: 새 꼼수 행의 바탕은 코딩만 ----------

def test_run_base_subjects_limits_new_trick_bases_to_coding():
    sci = science_pool([("paraphrase", True, 40), ("wrong", False, 10)], n_q=10)
    train = coding_pool(n_q=8) + sci
    _, new, stats = run(train, per_kind=4, base_subjects=frozenset({"코딩"}))
    by_id = {r["id"]: r for r in train}
    assert new
    assert {by_id[r["source_id"]]["subject"] for r in new} == {"코딩"}
    assert {r["subject"] for r in new} == {"코딩"}
    assert stats["base_subjects"] == ["코딩"]


def test_pick_bases_subjects_filter():
    rows = coding_pool(n_q=3) + science_pool([("paraphrase", True, 10)], n_q=5)
    bases = A.pick_bases(rows, 4, 1, normalize_n2, subjects=frozenset({"코딩"}))
    assert len(bases) == 4 and {b["subject"] for b in bases} == {"코딩"}


def test_pick_bases_subjects_filter_raises_when_not_enough():
    rows = coding_pool(n_q=1, n_true=1) + science_pool([("paraphrase", True, 10)], n_q=5)
    with pytest.raises(ValueError):
        A.pick_bases(rows, 4, 1, normalize_n2, subjects=frozenset({"코딩"}))


# ---------- qid 보존 ----------

def test_run_keeps_qid_and_new_rows_inherit_base_qid():
    train = coding_pool()
    final, new, _ = run(train)
    by_id = {r["id"]: r for r in train}
    assert all("qid" in r for r in final)
    for r in new:
        assert r["qid"] == by_id[r["source_id"]]["qid"]


# ---------- 수용 기준 2: 구성 통계 ----------

def test_run_records_composition_stats():
    saf = coding_pool(n_q=4, source=SAF)
    train = coding_pool() + saf + science_pool(STRATA)
    final, _, stats = run(train, max_science=7, base_subjects=CODING)
    comp = stats["composition"]
    cheat = {"injection", "injection_with_answer", "tag_escape"}
    assert comp["total"] == len(final)
    assert comp["science"] == sum(r["subject"] == "과학" for r in final) == 7
    assert comp["label_true_share"] == pytest.approx(sum(r["label"] is True for r in final) / len(final), abs=1e-4)
    assert comp["mohler_non_trick"] == sum(r["source"] == MOH and r["kind"] not in cheat for r in final)
    assert comp["saf_non_trick"] == sum(r["source"] == SAF and r["kind"] not in cheat for r in final)


# ---------- CLI ----------

@pytest.fixture
def v2_aug_dir(tmp_path, monkeypatch):
    tables = make_tables()
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
    (d / "train_clean.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows),
                                         encoding="utf-8")
    (tmp_path / "datasets").mkdir()
    return {"dir": d, "datasets": tmp_path / "datasets", "rows": rows}


def test_augment_cli_v2_options(v2_aug_dir, monkeypatch):
    monkeypatch.setattr(A, "EXCLUDE_ROWS", frozenset())
    monkeypatch.setattr(A, "EXCLUDE_QUESTIONS_OF", frozenset())
    d = v2_aug_dir["dir"]
    A.main(["--dir", str(d), "--eval-set", str(DEV_FILE), "--datasets-dir", str(v2_aug_dir["datasets"]),
            "--max-science", "12", "--base-subjects", "코딩", "--per-kind", "3"])
    stats = json.loads((d / "aug_stats.json").read_text(encoding="utf-8"))
    final = [json.loads(line) for line in (d / "train_aug.jsonl").read_text(encoding="utf-8").splitlines()]
    assert stats["max_science"] == 12 and stats["base_subjects"] == ["코딩"]
    assert sum(r["subject"] == "과학" for r in final) <= 12
    new = [r for r in final if r["source"] == "augment"]
    by_id = {r["id"]: r for r in v2_aug_dir["rows"]}
    assert new and {by_id[r["source_id"]]["subject"] for r in new} == {"코딩"}


def test_augment_cli_v2_drops_english_pair_of_absent_anchor(v2_aug_dir, monkeypatch):
    rows = v2_aug_dir["rows"]
    moh = [r for r in rows if r["source"] == MOH and r["source_id"].startswith("M01.")]
    anchor, rest = moh[0], moh[1:]
    d = v2_aug_dir["dir"]
    kept_rows = [r for r in rows if r["id"] != anchor["id"]]
    (d / "train_clean.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in kept_rows),
                                         encoding="utf-8")
    monkeypatch.setattr(A, "EXCLUDE_ROWS", frozenset())
    monkeypatch.setattr(A, "EXCLUDE_QUESTIONS_OF", frozenset({anchor["id"]}))
    A.main(["--dir", str(d), "--eval-set", str(DEV_FILE), "--datasets-dir", str(v2_aug_dir["datasets"]),
            "--max-science", "12", "--base-subjects", "코딩", "--per-kind", "3"])
    final_ids = {json.loads(line)["source_id"] for line in (d / "train_aug.jsonl").read_text(encoding="utf-8").splitlines()}
    assert not {r["source_id"] for r in rest} & final_ids
