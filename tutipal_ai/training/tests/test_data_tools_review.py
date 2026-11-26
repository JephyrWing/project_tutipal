"""data_tools.augment_tricks 의 라벨 검수 반영(질문 누출 수정·제외 목록)과 보조 기능 테스트(개발자 추가분).

데이터는 모두 합성이다. 실제 학습 데이터는 읽지 않는다.
"""

import copy
import csv
import json
import math
from collections import Counter
from pathlib import Path

import pytest

from data_tools import augment_tricks as A
from data_tools import build_grading_train as B
from data_tools import clean_grading_c as C
from data_tools.common import is_inside, repo_root
from eval.leakcheck import ID_PATTERN, normalize_n2

TRAINING = Path(__file__).resolve().parents[1]
DEV_FILE = TRAINING / "eval" / "grading_set.jsonl"


def _row(rid, q, ans, label=True, ref="합성 모범 답안: 에너지는 형태가 바뀌어도 보존된다."):
    kind = "paraphrase" if label else "wrong"
    reason = "모범 답안과 같은 뜻이다." if label else "핵심 개념이 누락되었다."
    feedback = "맞았어요. 핵심을 담았어요." if label else "핵심 개념을 다시 확인해 보세요."
    return B.record(rid, {"source": "Synthetic", "source_id": rid}, q, ref, ans, label, kind, reason, feedback, "과학")


def _pool(n_q, n_true, n_false):
    rows = []
    for q in range(n_q):
        for j in range(n_true + n_false):
            label = j < n_true
            rows.append(_row(f"SYN-{q:03d}-{j:02d}", f"합성 문항 {q}번: 원리를 설명하세요.",
                             f"학생 {q}-{j} 답안입니다 에너지가 바뀌어도 총량은 그대로 남습니다", label,
                             ref=f"합성 모범 답안 {q}번: 에너지는 보존된다."))
    return rows


@pytest.fixture(scope="module")
def dev():
    return [json.loads(x) for x in DEV_FILE.read_text(encoding="utf-8").splitlines() if x.strip()]


def _no_leaks(train, dev, sealed=None):
    return {"failures": [], "review": [], "review_total": {}, "sealed": "not_checked"}


# ---------- 검수 상수 ----------

def test_review_constants_are_plain_ids():
    for ids in (A.EXCLUDE_ROWS, A.EXCLUDE_QUESTIONS_OF, set(A.QUESTION_FIXES), A.QUESTION_REVIEWED_NO_LEAK,
                A.BAD_BASES):
        assert all(ID_PATTERN.fullmatch(x) for x in ids)
    assert len(A.EXCLUDE_ROWS) == 31
    assert len(A.EXCLUDE_QUESTIONS_OF) == 32
    assert not set(A.QUESTION_FIXES) & A.QUESTION_REVIEWED_NO_LEAK
    assert not hasattr(A, "QUESTION_FIX_PENDING"), "보류 목록은 없어졌다(검토 결과 누출 아님으로 옮김)"


SECOND_REVIEW_BAD_BASES = {
    "SciEntsBank-ME.10.314.1", "SciEntsBank-EM.21a.529.1", "SciEntsBank-PS.12.249.1",
    "MohlerASAG-E11.Q06.A17", "SciEntsBank-MX.36b.612.1", "SciEntsBank-SE.3c.140.1",
}


def test_bad_bases_include_second_review():
    assert SECOND_REVIEW_BAD_BASES <= A.BAD_BASES
    assert len(A.BAD_BASES) == 25


def test_reviewed_no_leak_question_is_not_fixed_or_pending():
    assert A.QUESTION_REVIEWED_NO_LEAK == {"SciEntsBank-SE.10.327.1-injection_with_answer"}
    assert not A.QUESTION_REVIEWED_NO_LEAK & set(A.QUESTION_FIXES)


def test_review_drop_is_per_question_and_model_answer_pair(monkeypatch):
    """대표 행과 질문은 같지만 모범 답안이 다른 행은 남는다(실제 예: MohlerASAG-E12.Q06.A00 과 E09.Q01.A26)."""
    q = "합성 문항: 스택이란?"
    anchor = _row("SYN-A", q, "학생 답안 하나입니다", ref="깨진 모범 답안")
    same_pair = _row("SYN-B", q, "학생 답안 둘입니다", False, ref="깨진 모범 답안")
    other_ref = _row("SYN-C", q, "학생 답안 셋입니다", ref="다른 모범 답안: 후입선출 자료 구조")
    monkeypatch.setattr(A, "EXCLUDE_ROWS", frozenset())
    monkeypatch.setattr(A, "EXCLUDE_QUESTIONS_OF", frozenset({"SYN-A"}))
    kept, info = A.review_drop([anchor, same_pair, other_ref])
    assert [r["id"] for r in kept] == ["SYN-C"]
    assert info["questions"] == ["SYN-A", "SYN-B"]


def test_run_stats_report_reviewed_no_leak_not_pending(dev, monkeypatch):
    monkeypatch.setattr(A, "EXCLUDE_ROWS", frozenset())
    monkeypatch.setattr(A, "EXCLUDE_QUESTIONS_OF", frozenset())
    _, _, stats = A.run(_pool(30, 2, 5), dev, seed=3, per_kind=4, check_leaks=_no_leaks, normalize_n2=normalize_n2)
    assert "question_fix_pending_ids" not in stats
    assert stats["question_reviewed_no_leak_ids"] == ["SciEntsBank-SE.10.327.1-injection_with_answer"]


def test_run_rejects_empty_train_or_dev(dev):
    with pytest.raises(ValueError, match="행이 없음"):
        A.run([], dev, seed=1, per_kind=1, check_leaks=_no_leaks, normalize_n2=normalize_n2)
    with pytest.raises(ValueError, match="평가 세트"):
        A.run(_pool(3, 2, 2), [], seed=1, per_kind=1, check_leaks=_no_leaks, normalize_n2=normalize_n2)


def test_base_reject_review_excluded_row():
    rid = min(A.EXCLUDE_ROWS - A.BAD_BASES)
    row = _row(rid, "합성 문항", "학생 답안입니다 에너지가 바뀌어도 총량은 그대로 남습니다")
    assert A.base_reject(row, normalize_n2) == "review_excluded"


# ---------- 질문 누출 수정 ----------

def test_fix_questions_rewrites_question_and_messages(monkeypatch):
    monkeypatch.setattr(A, "QUESTION_FIXES", {"SYN-1": (("(C. 40그램)", "C. 40그램."), (" (동일)", ""))})
    q = "공의 무게는? A. 20그램. B. 30그램. (C. 40그램) 같나요? (동일) 이유를 쓰세요."
    rows = [_row("SYN-1", q, "학생 답안 하나입니다"), _row("SYN-2", q, "학생 답안 둘입니다", False),
            _row("SYN-3", "다른 문항 (동일)", "학생 답안 셋입니다")]
    snapshot = copy.deepcopy(rows)
    out, fixed, missing = A.fix_questions(rows)
    assert rows == snapshot, "입력을 바꾸면 안 된다"
    assert fixed == ["SYN-1", "SYN-2"] and missing == []
    assert out[0]["question"] == "공의 무게는? A. 20그램. B. 30그램. C. 40그램. 같나요? 이유를 쓰세요."
    assert out[1]["question"] == out[0]["question"]
    assert out[2] is rows[2]
    assert C.check_input(out) == []
    assert "(C. 40그램)" not in out[0]["messages"][1]["content"]


def test_fix_questions_skips_absent_and_rejects_partial(monkeypatch):
    monkeypatch.setattr(A, "QUESTION_FIXES", {"SYN-1": (("(Q)", ""),), "SYN-9": (("(Q)", ""),)})
    _, fixed, missing = A.fix_questions([_row("SYN-1", "누출 없는 문항", "학생 답안입니다")])
    assert fixed == [] and missing == ["SYN-1", "SYN-9"]
    monkeypatch.setattr(A, "QUESTION_FIXES", {"SYN-1": (("(Q)", ""), ("(R)", ""))})
    with pytest.raises(ValueError):
        A.fix_questions([_row("SYN-1", "가장 가파른 곳은? (Q) 이유는?", "학생 답안입니다")])


def test_fix_questions_rejects_row_with_inconsistent_messages(monkeypatch):
    monkeypatch.setattr(A, "QUESTION_FIXES", {"SYN-1": (("(Q)", ""),)})
    r = _row("SYN-1", "가장 가파른 곳은? (Q) 이유는?", "학생 답안입니다")
    r["messages"][1]["content"] = "<question>다른 문제</question>"
    with pytest.raises(ValueError):
        A.fix_questions([r])


def test_run_applies_question_fix_before_exclusions(dev, monkeypatch):
    monkeypatch.setattr(A, "EXCLUDE_ROWS", frozenset())
    monkeypatch.setattr(A, "EXCLUDE_QUESTIONS_OF", frozenset())
    train = _pool(30, 2, 5)
    q0 = train[0]["question"]
    leaked = q0 + " (6도)"
    train = [B.record(r["id"], r, leaked, r["model_answer"], r["student_answer"], r["label"], r["kind"], r["reason"],
                      r["feedback"], r["subject"]) if r["question"] == q0 else r for r in train]
    monkeypatch.setattr(A, "QUESTION_FIXES", {train[0]["id"]: ((" (6도)", ""),)})
    final, new, stats = A.run(train, dev, seed=3, per_kind=4, check_leaks=_no_leaks, normalize_n2=normalize_n2)
    assert leaked not in {r["question"] for r in final + new}
    assert stats["question_fixed_rows"] == 7
    assert C.check_input(final) == []


# ---------- 템플릿 균형(정확한 횟수) ----------

def test_each_template_used_per_kind_over_len_times(dev):
    per_kind = math.lcm(*(len(t) for t in A.TEMPLATES.values()))
    train = _pool(per_kind * len(A.TEMPLATES), 2, 5)
    by_id = {r["id"]: r for r in train}
    _, new, _ = A.run(train, dev, seed=11, per_kind=per_kind, check_leaks=_no_leaks, normalize_n2=normalize_n2)
    for kind, templates in A.TEMPLATES.items():
        used = Counter(A.template_of(r, by_id[r["source_id"]]) for r in new if r["kind"] == kind)
        assert used == {i: per_kind // len(templates) for i in range(len(templates))}, kind


# ---------- 새 바탕 검수 목록 ----------

def test_cli_writes_new_bases_for_review(tmp_path, monkeypatch):
    monkeypatch.setattr(A, "EXCLUDE_ROWS", frozenset())
    monkeypatch.setattr(A, "EXCLUDE_QUESTIONS_OF", frozenset())
    d = tmp_path / "grading_c"
    d.mkdir()
    train = _pool(30, 2, 5)
    (d / "train_clean.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in train),
                                         encoding="utf-8")
    A.main(["--dir", str(d), "--eval-set", str(DEV_FILE), "--per-kind", "4", "--seed", "7"])
    with open(d / "aug_bases.csv", encoding="utf-8-sig", newline="") as f:
        first = list(csv.DictReader(f))
    reviewed = tmp_path / "review.csv"
    with open(reviewed, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["new_id", "base_id", "verdict", "reason"])
        for row in first[:5]:
            w.writerow([row["new_id"], row["base_id"], "OK", ""])
    A.main(["--dir", str(d), "--eval-set", str(DEV_FILE), "--per-kind", "4", "--seed", "7",
            "--reviewed-bases", str(reviewed)])
    with open(d / "aug_bases_new_for_review.csv", encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    assert list(rows[0]) == ["new_id", "base_id", "question", "model_answer", "base_answer"]
    assert sorted(r["base_id"] for r in rows) == sorted(r["base_id"] for r in first[5:])
    assert json.loads((d / "aug_stats.json").read_text(encoding="utf-8"))["new_bases_for_review"] == len(first) - 5


def test_cli_maps_insufficient_candidates_to_exit_2(tmp_path, monkeypatch, capsys):
    d = tmp_path / "grading_c"
    d.mkdir()
    train = _pool(3, 1, 30)
    (d / "train_clean.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in train),
                                         encoding="utf-8")
    with pytest.raises(SystemExit) as exc:
        A.main(["--dir", str(d), "--eval-set", str(DEV_FILE), "--per-kind", "5"])
    assert exc.value.code == 2
    assert not (d / "train_aug.jsonl").exists()


# ---------- 저장소 안 판정 ----------

def test_is_inside_repo(tmp_path):
    root = repo_root()
    assert is_inside(root / "tutipal_ai" / "x" / ".." / "training", root)
    assert not is_inside(tmp_path, root)
    assert (root / "tutipal_ai" / "training").is_dir()
