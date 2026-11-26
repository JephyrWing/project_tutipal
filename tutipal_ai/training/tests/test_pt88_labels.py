"""PT-88 라벨 규칙 테스트 (build_grading_train v2 원천 읽기, v1 결과는 그대로).

수용 기준 15(Mohler 10점 척도 문제 정규화), 16(경계 4.5 유지), 17(채점자 불일치 오답 제외),
18(무응답 → unknown), 19(독일어 SAF 답안 제외).
원천 parquet 은 가짜 표(FakeRows)로 바꾼다. 실제 데이터 테스트(15)는 TUTIPAL_DATASETS_DIR(없으면 홈의 기본 위치)이
없으면 건너뛴다.

load_sources_v2(datasets_dir, dropped=None): dropped 에 리스트를 주면 규칙으로 뺀 행을
{'source', 'source_id', 'error'} 로 덧붙인다(error: 'mohler_10scale_flip', 'disagree', 'german').
"""

import os
from pathlib import Path

import pytest
from _v2_data import MOH, SAF, FakeRows

from data_tools import build_grading_train as B
from data_tools import quality as Q

REAL_DATASETS = Path(os.environ.get("TUTIPAL_DATASETS_DIR") or Path.home() / "tutipal_datasets" / "datasets")


def mrow(rid, g1, g2, avg=None, ans="It stores the address of another variable in memory."):
    return {"id": rid, "question": f"Question for {rid.rsplit('.', 1)[0]}?",
            "instructor_answer": "The address of a location in memory.", "student_answer": ans,
            "score_avg": (g1 + g2) / 2 if avg is None else avg, "score_grader_1": g1, "score_grader_2": g2}


def srow(rid, ans, verdict="Correct"):
    return {"id": rid, "question": "What does the transport layer do with duplicate packets?",
            "reference_answer": "It detects and discards duplicate packets using sequence numbers.",
            "provided_answer": ans, "verification_feedback": verdict, "answer_feedback": "Synthetic feedback."}


def tables(mohler=(), saf=()):
    t = {rel: [] for rel in B.SOURCE_FILES.values()}
    t[B.SOURCE_FILES["mohler_open"]] = list(mohler)
    t[B.SOURCE_FILES["saf"]] = list(saf)
    return t


def load_v2(monkeypatch, t):
    monkeypatch.setattr(B, "rows", FakeRows(t))
    dropped = []
    items = B.load_sources_v2("unused", dropped=dropped)
    return {x["source_id"]: x for x in items}, {d["source_id"]: d for d in dropped}


# ---------- 수용 기준 15: 10점 척도 문제 ----------

def test_mohler_scale_questions_finds_questions_with_grader_above_5():
    rows = [mrow("E11.Q01.A00", 10, 8), mrow("E11.Q01.A01", 2, 3), mrow("E01.Q01.A00", 5, 5),
            mrow("E01.Q02.A00", 4.5, 5), mrow("E12.Q10.A06", 7.5, 0)]
    assert B.mohler_scale_questions(rows) == {"E11.Q01", "E12.Q10"}


def test_ten_scale_full_marks_stays_true(monkeypatch):
    items, _ = load_v2(monkeypatch, tables(mohler=[mrow("E11.Q01.A00", 10, 10, avg=5.0)]))
    assert items["E11.Q01.A00"]["label"] is True
    assert items["E11.Q01.A00"]["kind"] == "paraphrase"


def test_ten_scale_row_that_was_true_only_by_raw_scores_is_dropped(monkeypatch):
    # 정규화 전: 평균 4.5, 두 채점자 >= 4 → 정답. 정규화 후 5 / 3.5 → 한 채점자 < 4 → 버림.
    t = tables(mohler=[mrow("E11.Q01.A00", 10, 10, avg=5.0), mrow("E11.Q01.A01", 10, 7, avg=4.5)])
    items, dropped = load_v2(monkeypatch, t)
    assert "E11.Q01.A01" not in items
    assert dropped["E11.Q01.A01"] == {"source": MOH, "source_id": "E11.Q01.A01", "error": "mohler_10scale_flip"}


def test_five_scale_questions_are_not_normalized(monkeypatch):
    items, _ = load_v2(monkeypatch, tables(mohler=[mrow("E01.Q01.A00", 5, 4, avg=4.5)]))
    assert items["E01.Q01.A00"]["label"] is True


def test_v1_mohler_labels_unchanged_by_normalization(monkeypatch):
    monkeypatch.setattr(B, "rows", FakeRows(tables(mohler=[mrow("E11.Q01.A00", 10, 10, avg=5.0),
                                                           mrow("E11.Q01.A01", 10, 7, avg=4.5)])))
    v1 = {x["source_id"]: x for x in B.load_sources("unused")}
    assert v1["E11.Q01.A01"]["label"] is True


EXPECTED_SCALED = {f"E{e}.Q{q:02d}" for e in (11, 12) for q in range(1, 11)}


@pytest.mark.skipif(not REAL_DATASETS.is_dir(), reason="실제 원천 datasets 폴더 없음")
def test_real_mohler_ten_scale_questions_are_exactly_e11_e12():
    rows = (B.rows(REAL_DATASETS, B.SOURCE_FILES["mohler_open"])
            + B.rows(REAL_DATASETS, B.SOURCE_FILES["mohler_close"]))
    assert B.mohler_scale_questions(rows) == EXPECTED_SCALED
    assert len(EXPECTED_SCALED) == 20


# ---------- 수용 기준 16: 경계 규칙 유지 ----------

@pytest.mark.parametrize("avg, g1, g2, expected", [
    (4.5, 5, 4, True),
    (4.25, 5, 3.5, None),
    (5.0, 5, 5, True),
    (4.0, 4, 4, None),
])
def test_mohler_label_v2_boundary_kept(avg, g1, g2, expected):
    assert B.mohler_label_v2(avg, g1, g2) is expected


def test_boundary_exactly_45_is_kept_in_loader(monkeypatch):
    items, _ = load_v2(monkeypatch, tables(mohler=[mrow("E10.Q04.A09", 5, 4)]))
    assert items["E10.Q04.A09"]["label"] is True


# ---------- 수용 기준 17: 채점자 불일치 오답 ----------

def test_false_candidate_with_grader_gap_3_is_dropped(monkeypatch):
    items, dropped = load_v2(monkeypatch, tables(mohler=[mrow("E08.Q03.A06", 4, 1), mrow("E08.Q03.A07", 3, 1)]))
    assert "E08.Q03.A06" not in items
    assert dropped["E08.Q03.A06"]["error"] == "disagree"
    assert items["E08.Q03.A07"]["label"] is False
    assert items["E08.Q03.A07"]["kind"] == "partial"


def test_grader_gap_uses_normalized_scores(monkeypatch):
    # 10점 척도 문제: (8, 2) → (4, 1) 차이 3 → 버림, (6, 2) → (3, 1) 차이 2 → 오답으로 남김
    t = tables(mohler=[mrow("E12.Q10.A00", 10, 10, avg=5.0), mrow("E12.Q10.A01", 8, 2, avg=2.5),
                       mrow("E12.Q10.A02", 6, 2, avg=2.0)])
    items, dropped = load_v2(monkeypatch, t)
    assert "E12.Q10.A01" not in items and dropped["E12.Q10.A01"]["error"] == "disagree"
    assert items["E12.Q10.A02"]["label"] is False


def test_v1_keeps_disagreeing_false_rows(monkeypatch):
    monkeypatch.setattr(B, "rows", FakeRows(tables(mohler=[mrow("E08.Q03.A06", 4, 1)])))
    assert any(x["source_id"] == "E08.Q03.A06" for x in B.load_sources("unused"))


# ---------- 수용 기준 18: 무응답 ----------

@pytest.mark.parametrize("text", ["Not answered.", "not answered", "  No answer ", "N/A", "n/a.", "na",
                                  "I don't know", "i dont know.", "IDK"])
def test_is_no_answer_true(text):
    assert Q.is_no_answer(text) is True


@pytest.mark.parametrize("text", ["I don't know why, but a pointer holds an address.",
                                  "Not answered by the textbook, a pointer holds an address.",
                                  "A function that calls itself."])
def test_is_no_answer_false(text):
    assert Q.is_no_answer(text) is False


def test_no_answer_templates_are_korean_and_fixed():
    assert B.hangul(Q.NO_ANSWER_REASON) > 0.5 and B.hangul(Q.NO_ANSWER_FEEDBACK) > 0.5
    assert B.validate({"question": "질문입니다", "model_answer": "모범 답안입니다", "student_answer": "답변 없음",
                       "reason": Q.NO_ANSWER_REASON, "feedback": Q.NO_ANSWER_FEEDBACK}, False) is None


def test_v2_no_answer_row_becomes_unknown(monkeypatch):
    items, _ = load_v2(monkeypatch, tables(mohler=[mrow("E11.Q05.A02", 0, 0, ans="not answered")]))
    assert items["E11.Q05.A02"]["label"] is False
    assert items["E11.Q05.A02"]["kind"] == "unknown"


def test_v1_no_answer_row_kind_unchanged(monkeypatch):
    monkeypatch.setattr(B, "rows", FakeRows(tables(mohler=[mrow("E11.Q05.A02", 0, 0, ans="not answered")])))
    v1 = {x["source_id"]: x for x in B.load_sources("unused")}
    assert v1["E11.Q05.A02"]["kind"] == "wrong"


# ---------- 수용 기준 19: 독일어 SAF 답안 ----------

GERMAN_880DA168 = ("1. Temporäre TSAPs\nEs werden für jede Verbindung neue TSAPs generiert. "
                   "Nachteil ist, dass dies nicht überall anwendbar ist")


@pytest.mark.parametrize("text", [GERMAN_880DA168, "Die Größe", "Das ist nicht ein Problem"])
def test_is_german_true(text):
    assert Q.is_german(text) is True


@pytest.mark.parametrize("text", ["the processes die and are not reused afterwards",
                                  "Sequence numbers detect duplicate packets.",
                                  "Under the order, the data is sent."])
def test_is_german_false(text):
    assert Q.is_german(text) is False


def test_v2_drops_german_saf_answer_keeps_english(monkeypatch):
    t = tables(saf=[srow("880da168" + "0" * 24, GERMAN_880DA168),
                    srow("e0" * 16, "the processes die and are not reused afterwards")])
    items, dropped = load_v2(monkeypatch, t)
    assert "880da168" + "0" * 24 not in items
    assert dropped["880da168" + "0" * 24] == {"source": SAF, "source_id": "880da168" + "0" * 24, "error": "german"}
    assert "e0" * 16 in items
