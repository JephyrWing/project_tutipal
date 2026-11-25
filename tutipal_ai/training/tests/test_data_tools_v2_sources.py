"""채점 학습 데이터 v2 (PT-88): 원천 읽기·qid·영어 단계 선별·핵심 누락 규칙 테스트.

원천 parquet 은 읽지 않는다. build_grading_train.rows 를 가짜 표(_v2_data.FakeRows)로 바꾼다.
LLM 은 부르지 않는다.
"""

import csv
import hashlib
from collections import Counter

import pytest
from _v2_data import MOH, SAF, SCI, FakeRows, make_tables, qid_of

from data_tools import build_grading_c as BC
from data_tools import build_grading_train as B
from data_tools.common import DEFAULT_SEED

# ---------- 수용 기준 16: v1 기본 인자 선별 결과 불변 ----------

# 변경 전 코드(B.load_sources + B.sample, seed=DEFAULT_SEED, n=40)를 make_tables(include_validation=False)에
# 돌려 얻은 결과. _v2_data.make_tables 를 바꾸면 이 기대값도 다시 만들어야 한다.
V1_GOLDEN = [
    (MOH, "M01.A08", True, "paraphrase"), (SCI, "SC.1.01.1", False, "wrong"),
    (SAF, "tr020000000000000000000000000000", True, "paraphrase"),
    (SAF, "tr000600000000000000000000000000", True, "paraphrase"),
    (MOH, "M02.A05", False, "partial"), (SCI, "SC.0.00.1", True, "paraphrase"),
    (MOH, "M02.A08", True, "paraphrase"),
    (SAF, "tr010500000000000000000000000000", False, "wrong"),
    (SAF, "tr030400000000000000000000000000", False, "partial"),
    (SAF, "tr030000000000000000000000000000", True, "paraphrase"),
    (MOH, "M05.A02", False, "wrong"), (MOH, "M03.A08", True, "paraphrase"),
    (MOH, "M05.A00", True, "paraphrase"),
    (SAF, "tr010700000000000000000000000000", False, "partial"),
    (MOH, "M00.A08", True, "paraphrase"),
    (SAF, "tr030300000000000000000000000000", True, "paraphrase"),
    (SCI, "SC.0.05.1", False, "wrong"), (MOH, "M04.A02", False, "wrong"),
    (SAF, "tr010400000000000000000000000000", False, "partial"),
    (MOH, "M01.A02", False, "wrong"), (MOH, "M03.A01", False, "partial"),
    (SCI, "SC.1.09.1", False, "wrong"), (MOH, "M00.A04", True, "paraphrase"),
    (MOH, "C00.A02", False, "wrong"), (SCI, "SC.2.08.1", True, "paraphrase"),
    (MOH, "M03.A05", False, "partial"), (MOH, "M05.A05", False, "partial"),
    (MOH, "M05.A08", True, "paraphrase"), (MOH, "M04.A08", True, "paraphrase"),
    (SCI, "SC.1.06.1", False, "partial"), (SCI, "SC.1.04.1", True, "paraphrase"),
    (SCI, "SC.1.07.1", False, "wrong"),
    (SAF, "tr020400000000000000000000000000", False, "partial"),
    (MOH, "M04.A05", False, "partial"), (SCI, "SC.1.00.1", True, "paraphrase"),
    (MOH, "M04.A07", False, "wrong"), (MOH, "M00.A02", False, "wrong"),
    (SCI, "SC.0.09.1", False, "wrong"),
]

V1_FILES = {"scientsbank", "mohler_open", "mohler_close", "saf"}


def test_v1_default_load_and_sample_unchanged(monkeypatch):
    fake = FakeRows(make_tables(include_validation=False))
    monkeypatch.setattr(B, "rows", fake)
    picked = B.sample(B.load_sources("unused-datasets-dir"), 40, DEFAULT_SEED)
    assert [(x["source"], x["source_id"], x["label"], x["kind"]) for x in picked] == V1_GOLDEN
    assert sorted(fake.reads) == sorted(B.SOURCE_FILES[k] for k in V1_FILES)


def test_v1_load_sources_does_not_read_saf_validation(monkeypatch):
    tables = make_tables(include_validation=False)
    fake = FakeRows(tables)
    monkeypatch.setattr(B, "rows", fake)
    B.load_sources("unused-datasets-dir")
    assert not any("validation" in rel for rel in fake.reads)


# ---------- qid ----------

def test_question_id_is_source_and_sha256_prefix():
    q = "What is a queue?"
    assert B.question_id("MohlerASAG", q) == "MohlerASAG|" + hashlib.sha256(q.encode("utf-8")).hexdigest()[:16]


def test_question_id_differs_by_source_and_text():
    assert B.question_id(MOH, "Q") != B.question_id(SAF, "Q")
    assert B.question_id(MOH, "Q1") != B.question_id(MOH, "Q2")


# ---------- 수용 기준 3·8: v2 원천 읽기 ----------

@pytest.fixture
def v2_tables():
    return make_tables()


@pytest.fixture
def v2_items(v2_tables, monkeypatch):
    fake = FakeRows(v2_tables)
    monkeypatch.setattr(B, "rows", fake)
    items = B.load_sources_v2("unused-datasets-dir")
    return {"items": items, "reads": fake.reads, "tables": v2_tables}


def test_saf_validation_path_is_validation_split_not_test():
    rel = B.SOURCE_FILES["saf_validation"]
    assert "validation" in rel and "test" not in rel
    assert rel.startswith("Short-Answer-Feedback__saf_communication_networks_english/")


def test_v2_reads_saf_train_and_validation_but_no_test_split(v2_items):
    reads = v2_items["reads"]
    assert B.SOURCE_FILES["saf"] in reads
    assert B.SOURCE_FILES["saf_validation"] in reads
    assert not any("test" in rel for rel in reads)


def test_v2_saf_ids_come_only_from_train_and_validation(v2_items):
    tables = v2_items["tables"]
    allowed = {r["id"] for r in tables[B.SOURCE_FILES["saf"]]} | {r["id"] for r in tables[B.SOURCE_FILES["saf_validation"]]}
    saf = [x for x in v2_items["items"] if x["source"] == SAF]
    assert {x["source_id"] for x in saf} == allowed
    val_ids = {r["id"] for r in tables[B.SOURCE_FILES["saf_validation"]]}
    assert val_ids & {x["source_id"] for x in saf} == val_ids  # validation 행이 실제로 들어온다


def test_v2_reads_mohler_open_and_close(v2_items):
    moh_ids = {x["source_id"] for x in v2_items["items"] if x["source"] == MOH}
    assert any(i.startswith("C00.") for i in moh_ids)
    assert any(i.startswith("M00.") for i in moh_ids)


def test_v2_items_have_qid_from_english_question(v2_items):
    for x in v2_items["items"]:
        assert x["qid"] == qid_of(x["source"], x["q"]), x["source_id"]


def test_v2_mohler_true_rows_need_avg_45_and_both_graders_at_least_4(v2_items, monkeypatch, v2_tables):
    rel = B.SOURCE_FILES["mohler_open"]
    tables = dict(v2_tables)
    extra = [
        {"id": "X.EDGE.OK", "question": "Edge question about zeta?", "instructor_answer": "Edge reference answer text.",
         "student_answer": "edge student answer that is long enough to count", "score_avg": 4.5,
         "score_grader_1": 5.0, "score_grader_2": 4.0},
        {"id": "X.EDGE.LOWGRADER", "question": "Edge question about zeta?",
         "instructor_answer": "Edge reference answer text.",
         "student_answer": "another edge student answer that is long enough", "score_avg": 4.6,
         "score_grader_1": 5.0, "score_grader_2": 3.7},
        {"id": "X.EDGE.AMBIG", "question": "Edge question about zeta?",
         "instructor_answer": "Edge reference answer text.",
         "student_answer": "an ambiguous edge student answer that is long", "score_avg": 4.0,
         "score_grader_1": 4.0, "score_grader_2": 4.0},
    ]
    tables[rel] = list(v2_tables[rel]) + extra
    monkeypatch.setattr(B, "rows", FakeRows(tables))
    items = {x["source_id"]: x for x in B.load_sources_v2("unused") if x["source"] == MOH}
    assert items["X.EDGE.OK"]["label"] is True and items["X.EDGE.OK"]["kind"] == "paraphrase"
    assert "X.EDGE.LOWGRADER" not in items or items["X.EDGE.LOWGRADER"]["label"] is False
    assert "X.EDGE.AMBIG" not in items
    by_id = {r["id"]: r for r in tables[rel] + tables[B.SOURCE_FILES["mohler_close"]]}
    for sid, x in items.items():
        r = by_id[sid]
        assert not 3.5 < r["score_avg"] < 4.5, sid
        if x["label"]:
            assert r["score_avg"] >= 4.5 and r["score_grader_1"] >= 4 and r["score_grader_2"] >= 4, sid


def test_v2_mohler_non_true_kinds_follow_score_rule(v2_items):
    tables = v2_items["tables"]
    by_id = {r["id"]: r for r in tables[B.SOURCE_FILES["mohler_open"]] + tables[B.SOURCE_FILES["mohler_close"]]}
    for x in v2_items["items"]:
        if x["source"] != MOH or x["label"]:
            continue
        s = by_id[x["source_id"]]["score_avg"]
        assert x["kind"] == ("partial" if s >= 2.0 else "wrong"), x["source_id"]


def test_v2_subjects(v2_items):
    subj = {(x["source"], x["subject"]) for x in v2_items["items"]}
    assert subj == {(SCI, "과학"), (MOH, "코딩"), (SAF, "코딩")}


# ---------- 영어 단계 선별 (select_v2) ----------

def _item(source, sid, q, kind, label=None):
    label = (kind == "paraphrase") if label is None else label
    subject = "과학" if source == SCI else "코딩"
    return {"source": source, "source_id": sid, "subject": subject, "q": q, "ref": "reference answer text here",
            "ans": f"student answer number {sid} with enough words", "label": label, "kind": kind, "hint": "",
            "qid": qid_of(source, q)}


def _always(x):
    return True


def test_select_v2_default_caps():
    assert BC.V2_SELECT_CAPS == {MOH: 20, SAF: 16}


def test_select_v2_caps_mohler_question_at_20_round_robin_wrong_first():
    items = [_item(MOH, f"Q1.{k}.{i}", "Mohler question one?", k)
             for k in ("wrong", "partial", "paraphrase") for i in range(10)]
    picked = BC.select_v2(items, 7, _always)
    assert len(picked) == 20
    c = Counter(x["kind"] for x in picked)
    assert c["wrong"] == 7
    assert c["partial"] >= 6 and c["paraphrase"] >= 6


def test_select_v2_wrong_first_breaks_ties():
    items = [_item(MOH, f"Q1.{k}.{i}", "Mohler question one?", k) for k in ("paraphrase", "wrong") for i in range(5)]
    picked = BC.select_v2(items, 7, _always, caps={MOH: 3, SAF: 16})
    assert Counter(x["kind"] for x in picked) == {"wrong": 2, "paraphrase": 1}


def test_select_v2_caps_per_english_question_not_per_source():
    items = [_item(MOH, f"A.{i}", "Question A?", "wrong") for i in range(25)]
    items += [_item(MOH, f"B.{i}", "Question B?", "wrong") for i in range(5)]
    picked = BC.select_v2(items, 7, _always)
    c = Counter(x["q"] for x in picked)
    assert c == {"Question A?": 20, "Question B?": 5}


def test_select_v2_caps_saf_question_at_16():
    items = [_item(SAF, f"s{i:02d}", "SAF question?", ["paraphrase", "partial", "wrong"][i % 3]) for i in range(22)]
    picked = BC.select_v2(items, 7, _always)
    assert len(picked) == 16


def test_select_v2_keeps_only_cached_science_rows():
    sci = [_item(SCI, f"SC.{i}", f"Science question {i % 2}?", "paraphrase") for i in range(6)]
    moh = [_item(MOH, f"M.{i}", "Mohler question?", "wrong") for i in range(3)]
    cached_ids = {"SC.1", "SC.4"}
    seen = []

    def cached(x):
        seen.append(x["source"])
        return x["source_id"] in cached_ids

    picked = BC.select_v2(sci + moh, 7, cached)
    assert {x["source_id"] for x in picked if x["source"] == SCI} == cached_ids
    assert {x["source_id"] for x in picked if x["source"] == MOH} == {"M.0", "M.1", "M.2"}


def test_select_v2_is_deterministic_and_input_order_independent():
    items = [_item(MOH, f"Q{q}.{k}.{i}", f"Mohler question {q}?", k)
             for q in range(3) for k in ("wrong", "partial", "paraphrase", "unknown") for i in range(8)]
    a = BC.select_v2(items, 11, _always)
    b = BC.select_v2(list(reversed(items)), 11, _always)
    c = BC.select_v2(items, 11, _always)
    ids = [(x["source"], x["source_id"]) for x in a]
    assert set(ids) == {(x["source"], x["source_id"]) for x in b}
    assert ids == [(x["source"], x["source_id"]) for x in c]
    assert len(ids) == 60


def test_select_v2_does_not_mutate_input():
    items = [_item(MOH, f"Q.{i}", "Mohler question?", "wrong") for i in range(25)]
    before = [dict(x) for x in items]
    BC.select_v2(items, 1, _always)
    assert items == before


# ---------- 수용 기준 9: 정답 행 핵심 누락 ----------

def _true(sid, ans_words, ref_words, label=True):
    x = _item(MOH, sid, "Mohler question?", "paraphrase" if label else "wrong", label)
    x["ans"] = " ".join(f"a{i}" for i in range(ans_words))
    x["ref"] = " ".join(f"r{i}" for i in range(ref_words))
    return x


@pytest.mark.parametrize("ans_words,ref_words,expected", [
    pytest.param(4, 8, True, id="under_5_words"),
    pytest.param(5, 20, False, id="exactly_25_percent_kept"),
    pytest.param(5, 24, True, id="under_25_percent"),
    pytest.param(6, 24, False, id="at_25_percent"),
    pytest.param(12, 10, False, id="long_answer"),
])
def test_missing_core_rule(ans_words, ref_words, expected):
    assert BC.missing_core(_true("t", ans_words, ref_words)) is expected


def test_missing_core_ignores_non_true_rows():
    assert BC.missing_core(_true("f", 1, 30, label=False)) is False


def test_drop_missing_core_keeps_reviewed_ids_and_reports_dropped():
    items = [_true("short1", 3, 10), _true("short2", 3, 10), _true("ok", 8, 10), _true("wrong", 1, 10, label=False)]
    kept, dropped = BC.drop_missing_core(items, keep_ids={f"{MOH}-short2"})
    assert {x["source_id"] for x in kept} == {"short2", "ok", "wrong"}
    assert dropped == [f"{MOH}-short1"]


def test_review_keep_ids_reads_ok_and_weak_keep(tmp_path):
    p1 = tmp_path / "review_true_mohler.csv"
    p2 = tmp_path / "review_true_saf.csv"
    for p, rows in ((p1, [("MohlerASAG-A", "row", "OK"), ("MohlerASAG-B", "row", "BAD"),
                          ("MohlerASAG-C", "row", "WEAK_KEEP")]),
                    (p2, [("SAF-communication-networks-D", "row", "WEAK_CORE"),
                          ("SAF-communication-networks-E", "row", "OK")])):
        with open(p, "w", encoding="utf-8-sig", newline="") as f:
            w = csv.writer(f)
            w.writerow(["id", "scope", "verdict", "reason"])
            for rid, scope, verdict in rows:
                w.writerow([rid, scope, verdict, "합성 사유"])
    assert BC.review_keep_ids([p1, p2]) == {"MohlerASAG-A", "MohlerASAG-C", "SAF-communication-networks-E"}


def test_review_keep_ids_empty_list_is_empty():
    assert BC.review_keep_ids([]) == set()
