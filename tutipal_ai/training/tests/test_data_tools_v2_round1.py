"""채점 학습 데이터 v2 (PT-88) Round 1 리뷰 반영 테스트.

out/cache-from 겹침 거부, 텍스트 단위 번역 메모, 잘린 캐시 마지막 줄 복구, v2 build 꼼수는 코딩만,
clean 의 v1 호환·출처 이름 검사, augment 의 미해결 검수 앵커·중복 검사 범위, 검수 시트 보호 장치.
데이터는 모두 합성이고 LLM·네트워크를 쓰지 않는다.
"""

import copy
import csv
import json
from pathlib import Path

import pytest
from _v2_data import (
    MOH,
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
    written,
)

# isort: split  (data_tools 는 실행 위치에 따라 first/third-party 로 다르게 판별돼 따로 묶는다)
from data_tools import augment_tricks as A
from data_tools import build_grading_c as BC
from data_tools import build_grading_train as B
from data_tools import clean_grading_c as C
from data_tools import compare_hybrid as H
from data_tools import review_sheet as RS
from eval.leakcheck import normalize_n2
from eval.run_eval import GRADE_SYSTEM

TRAINING = Path(__file__).resolve().parents[1]
DEV_FILE = TRAINING / "eval" / "grading_set.jsonl"


def _code(fn, argv):
    try:
        ret = fn(argv)
    except SystemExit as e:
        return 0 if e.code is None else e.code
    return 0 if ret is None else ret


# ---------- build: 공통 환경 ----------

@pytest.fixture
def benv(tmp_path, monkeypatch):
    tables = make_tables()
    items = english_items(tables)
    monkeypatch.setattr(B, "rows", FakeRows(tables))
    datasets = tmp_path / "datasets"
    datasets.mkdir()
    return {"tables": tables, "items": items, "tmp": tmp_path, "datasets": datasets,
            "quizai": quizai_file(tmp_path / "QuizAi.java", GRADE_SYSTEM), "mp": monkeypatch}


def _bargv(env, out, cache_from, *extra):
    return ["--v2", "--out", str(out), "--datasets-dir", str(env["datasets"]), "--quizai", str(env["quizai"]),
            "--eval-set", str(DEV_FILE), "--cache-from", str(cache_from), *extra]


def _llm(env, items=None):
    llm = FakeLLM(items or env["items"])
    env["mp"].setattr(H, "translate", llm.translate)
    env["mp"].setattr(H, "generate", llm.generate)
    return llm


# ---------- P1: --out 과 --cache-from 겹침 ----------

@pytest.mark.parametrize("layout", ["same", "out_inside_v1", "v1_inside_out"])
def test_v2_refuses_out_equal_to_cache_from(benv, layout):
    v1 = benv["tmp"] / "derived" / "grading_c"
    write_caches(v1, benv["items"])
    (v1 / "train.jsonl").write_text('{"id": "v1"}\n', encoding="utf-8")
    out = {"same": v1, "out_inside_v1": v1 / "v2", "v1_inside_out": v1.parent}[layout]
    before = folder_hashes(v1)
    llm = _llm(benv)
    assert _code(BC.main, _bargv(benv, out, v1)) == 2
    assert folder_hashes(v1) == before
    assert not (v1 / "v2").exists()
    assert llm.translated == [] and llm.generated == []


# ---------- P3: 같은 원문은 한 번만 번역 ----------

def test_v2_translates_same_question_text_once(benv):
    uncached = {x["source_id"] for x in benv["items"] if x["source"] == MOH and x["source_id"].startswith("M00.")}
    assert len(uncached) >= 3
    v1 = benv["tmp"] / "v1"
    write_caches(v1, [x for x in benv["items"] if x["source_id"] not in uncached])
    llm = _llm(benv)
    out = benv["tmp"] / "v2"
    BC.main(_bargv(benv, out, v1))
    m00 = [x for x in benv["items"] if x["source_id"] in uncached]
    q, ref = m00[0]["q"], m00[0]["ref"]
    assert llm.translated.count(q) == 1
    assert llm.translated.count(ref) == 1
    assert len(llm.translated) == 2 + len(m00)
    stats = json.loads((out / "stats.json").read_text(encoding="utf-8"))
    assert stats["llm_translate_calls"] == 2 + len(m00)
    assert stats["text_memo_hits"] == 2 * (len(m00) - 1)
    memo = load_jsonl(out / BC.TEXT_CACHE)
    assert len(memo) == len({r["key"] for r in memo}) == 2 + len(m00)
    train = {r["source_id"]: r for r in load_jsonl(out / "train.jsonl")}
    assert len({train[sid]["question"] for sid in uncached if sid in train}) == 1


def test_v2_text_memo_survives_restart(benv):
    v1 = benv["tmp"] / "v1"
    write_caches(v1, [x for x in benv["items"] if x["source_id"] != "M00.A00"])
    out = benv["tmp"] / "v2"
    out.mkdir()
    x = next(i for i in benv["items"] if i["source_id"] == "M00.A00")
    memo = [{"key": BC.text_key("translategemma:4b", t), "out": f"메모된 번역 {n}번입니다"}
            for n, t in enumerate((x["q"], x["ref"]))]
    (out / BC.TEXT_CACHE).write_text("".join(json.dumps(m, ensure_ascii=False) + "\n" for m in memo),
                                     encoding="utf-8")
    llm = _llm(benv)
    BC.main(_bargv(benv, out, v1))
    assert llm.translated == [x["ans"]]


def test_v2_cached_rows_keep_row_cache_translation(benv):
    """행 캐시에 있는 행은 메모가 아니라 행 캐시 결과를 그대로 쓴다(선별·조립 불변)."""
    v1 = benv["tmp"] / "v1"
    write_caches(v1, benv["items"])
    out = benv["tmp"] / "v2"
    out.mkdir()
    x = benv["items"][0]
    (out / BC.TEXT_CACHE).write_text(json.dumps({"key": BC.text_key("translategemma:4b", x["q"]),
                                                 "out": "다른 번역"}, ensure_ascii=False) + "\n", encoding="utf-8")
    llm = _llm(benv)
    BC.main(_bargv(benv, out, v1))
    assert llm.translated == []
    row = next(r for r in load_jsonl(out / "train.jsonl") if r["source_id"] == x["source_id"])
    assert row["question"] == written(x)["question"]


# ---------- 잘린 캐시 마지막 줄 ----------

def test_load_cache_skips_truncated_last_line(tmp_path, capsys):
    p = tmp_path / "trans_cache.jsonl"
    p.write_text('{"key": "a", "out": 1, "error": null}\n{"key": "b", "out"', encoding="utf-8")
    assert set(BC.load_cache(p)) == {"a"}
    assert "마지막 줄" in capsys.readouterr().err


def test_load_cache_raises_on_broken_middle_line(tmp_path):
    p = tmp_path / "trans_cache.jsonl"
    p.write_text('{"key": "a"\n{"key": "b", "out": 1, "error": null}\n', encoding="utf-8")
    with pytest.raises(ValueError):
        BC.load_cache(p)


def test_repair_tail_cuts_broken_line_so_appends_stay_readable(tmp_path):
    p = tmp_path / "write_cache.jsonl"
    p.write_text('{"key": "a", "out": 1, "error": null}\n{"key": "b", "ou', encoding="utf-8")
    assert BC.repair_tail(p) is True
    with open(p, "a", encoding="utf-8") as f:
        f.write('{"key": "c", "out": 2, "error": null}\n')
    assert set(BC.load_cache(p)) == {"a", "c"}


def test_repair_tail_adds_missing_newline(tmp_path):
    p = tmp_path / "write_cache.jsonl"
    p.write_text('{"key": "a", "out": 1, "error": null}', encoding="utf-8")
    assert BC.repair_tail(p) is True
    assert p.read_text(encoding="utf-8").endswith("\n")
    assert BC.repair_tail(p) is False


def test_v2_build_resumes_after_truncated_cache(benv):
    v1 = benv["tmp"] / "v1"
    write_caches(v1, [x for x in benv["items"] if x["source_id"] != "M00.A00"])
    out = benv["tmp"] / "v2"
    _llm(benv)
    BC.main(_bargv(benv, out, v1))
    tpath = out / "trans_cache.jsonl"
    tpath.write_text(tpath.read_text(encoding="utf-8") + '{"key": "half', encoding="utf-8")
    llm = _llm(benv)
    BC.main(_bargv(benv, out, v1))
    assert llm.translated == [] and llm.generated == []
    BC.load_cache(tpath)  # 복구된 파일은 다시 읽힌다


# ---------- v2 build 꼼수는 코딩 행만 ----------

def test_v2_build_tricks_only_on_coding_rows(benv):
    v1 = benv["tmp"] / "v1"
    write_caches(v1, benv["items"])
    out = benv["tmp"] / "v2"
    _llm(benv)
    BC.main(_bargv(benv, out, v1, "--inject-ratio", "1.0", "--assemble-only"))
    train = load_jsonl(out / "train.jsonl")
    tricks = [r for r in train if r["kind"] in C.CHEAT]
    assert tricks and {r["subject"] for r in tricks} == {"코딩"}
    assert any(r["source"] == SCI and r["label"] for r in train)


def test_v2_review_true_missing_file_exits_2(benv):
    v1 = benv["tmp"] / "v1"
    write_caches(v1, benv["items"])
    out = benv["tmp"] / "v2"
    _llm(benv)
    assert _code(BC.main, _bargv(benv, out, v1, "--review-true", str(benv["tmp"] / "nope.csv"))) == 2
    bad = benv["tmp"] / "bad.csv"
    bad.write_text("a,b\n1,2\n", encoding="utf-8")
    assert _code(BC.main, _bargv(benv, out, v1, "--review-true", str(bad))) == 2
    assert not out.exists()


# ---------- clean: v1 호환 ----------

def _crow(sid, *, label=True, kind=None, reason=None, qid=True, q="합성 문항: 규칙을 설명하세요."):
    kind = kind or ("paraphrase" if label else "wrong")
    r = B.record(f"{MOH}-{sid}", {"source": MOH, "source_id": sid}, q, "합성 모범 답안: 순서를 지킨다.",
                 f"학생 답안 {sid}번입니다 순서를 지킵니다", label, kind,
                 reason or ("모범 답안과 같은 뜻을 담고 있다." if label else "핵심 개념이 누락되어 있다."),
                 "핵심을 정확히 담았어요." if label else "핵심 개념을 다시 확인해 보세요.", "코딩")
    if qid:
        r["qid"] = qid_of(MOH, "Q?")
    return r


def test_clean_v1_rows_keep_terms_and_count_cheat_in_cap():
    rows = [_crow("a", label=False, reason="핵심 개념인 핑거링 동작이 누락되어 있다.", qid=False)]
    rows += [_crow(f"b{i}", qid=False) for i in range(4)]
    rows += [_crow(f"c{i}", label=False, kind="injection", qid=False, reason=B.INJ_REASON) for i in range(3)]
    origin = {(MOH, r["source_id"]): "Q?" for r in rows}
    final, dropped, _, _ = C.clean(rows, origin, cap=5, seed=1)
    assert not any(why.startswith("term:") for _, why in dropped)
    assert len(final) == 5, "v1(qid 없음)은 꼼수 행도 상한에 센다(HEAD 와 같음)"


def test_clean_v2_flag_forces_rules_on_rows_without_qid():
    rows = [_crow("a", label=False, reason="핵심 개념인 핑거링 동작이 누락되어 있다.", qid=False), _crow("b", qid=False)]
    origin = {(MOH, r["source_id"]): "Q?" for r in rows}
    final, dropped, _, _ = C.clean(rows, origin, cap=5, seed=1, v2=True)
    assert [r["source_id"] for r in final] == ["b"]
    assert [why for _, why in dropped] == ["term:핑거링"]


@pytest.fixture
def clean_dir(tmp_path, monkeypatch):
    tables = make_tables()
    monkeypatch.setattr(B, "rows", FakeRows(tables))
    items = [x for x in english_items(tables) if x["source"] == MOH][:10]
    rows = []
    for x in items:
        o = written(x)
        r = B.record(f"{x['source']}-{x['source_id']}", x, o["question"], o["model_answer"], o["student_answer"],
                     x["label"], x["kind"], o["reason"], o["feedback"], x["subject"])
        r["qid"] = x["qid"]
        rows.append(r)
    d = tmp_path / "v2"
    d.mkdir()
    (d / "train.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    (tmp_path / "datasets").mkdir()
    return ["--dir", str(d), "--datasets-dir", str(tmp_path / "datasets"), "--eval-set", str(DEV_FILE),
            "--sources", "v2"]


def test_clean_cli_rejects_unknown_cap_source(clean_dir):
    assert _code(C.main, clean_dir + ["--cap-by-source", "MohlerASGA=12"]) == 2


def test_clean_cli_accepts_known_source_absent_from_data(clean_dir):
    assert _code(C.main, clean_dir + ["--cap-by-source", "MohlerASAG=12", "SciEntsBank=10"]) == 0


# ---------- augment ----------

def _arow(sid, q_en="Q?"):
    r = B.record(f"{MOH}-{sid}", {"source": MOH, "source_id": sid}, f"합성 문항 {sid}", "합성 모범 답안",
                 f"학생 답안 {sid}번입니다 새 항목을 끝에 붙이고 순서를 지킵니다", True, "paraphrase",
                 "모범 답안과 같은 뜻이다.", "맞았어요.", "코딩")
    r["qid"] = qid_of(MOH, q_en)
    return r


def test_run_stops_on_unresolved_review_anchor_with_origin(monkeypatch):
    monkeypatch.setattr(A, "EXCLUDE_ROWS", frozenset())
    monkeypatch.setattr(A, "EXCLUDE_QUESTIONS_OF", frozenset({f"{MOH}-NOT.IN.ORIGIN"}))
    rows = [_arow(f"X.{i}") for i in range(10)]
    origin = {(MOH, r["source_id"]): ("Q?", "R.") for r in rows}
    dev = [{"id": "g-001", "question": "dev", "model_answer": "dev", "student_answer": "dev", "kind": "wrong"}]

    def no_leaks(train, dev, sealed=None):
        return {"failures": [], "review": [], "review_total": {}, "sealed": "not_checked"}

    with pytest.raises(ValueError):
        A.run(rows, dev, seed=1, per_kind=1, check_leaks=no_leaks, normalize_n2=normalize_n2, origin=origin)
    # origin 없이(v1)는 기존처럼 통계에만 남는다
    A.run(copy.deepcopy(rows), dev, seed=1, per_kind=1, check_leaks=no_leaks, normalize_n2=normalize_n2)


def test_validate_allows_duplicates_among_kept_rows_but_not_with_new():
    """중복 검사는 새 행이 낀 경우만: 기존 행끼리의 같은 (문제, 학생 답안)은 clean 의 몫이라 허용한다."""
    base = _arow("B.1")
    twin = {**copy.deepcopy(base), "id": "SAF-communication-networks-B.1", "source": "SAF-communication-networks"}
    new = A.build([base], seed=1)
    A.validate(new, [base, twin], [{"id": "g", "question": "d", "model_answer": "d", "student_answer": "d",
                                     "kind": "wrong"}], lambda *a, **k: {"failures": [], "review_total": {}})
    dup = {**copy.deepcopy(base), "id": "X-dup", "student_answer": new[0]["student_answer"]}
    with pytest.raises(ValueError):
        A.validate(new, [base, dup], [], lambda *a, **k: {"failures": [], "review_total": {}})


# ---------- 검수 시트 ----------

@pytest.fixture
def sheet_dir(tmp_path, monkeypatch):
    tables = make_tables()
    monkeypatch.setattr(B, "rows", FakeRows(tables))
    rows = []
    for x in english_items(tables):
        o = written(x)
        r = B.record(f"{x['source']}-{x['source_id']}", x, o["question"], o["model_answer"], o["student_answer"],
                     x["label"], x["kind"], o["reason"], o["feedback"], x["subject"])
        r["qid"] = x["qid"]
        rows.append(r)
    d = tmp_path / "v2"
    d.mkdir()
    (d / "train_aug.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows),
                                       encoding="utf-8")
    (tmp_path / "datasets").mkdir()
    return {"dir": d, "make": ["make", "--dir", str(d), "--datasets-dir", str(tmp_path / "datasets")],
            "sheet": d / "review_sheet.csv", "rows": rows}


def _fill(sheet, verdict="OK"):
    with open(sheet, encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        fields, rows = reader.fieldnames, list(reader)
    for r in rows:
        r["verdict"] = verdict
    with open(sheet, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)


def test_make_refuses_to_overwrite_filled_sheet_without_force(sheet_dir):
    assert _code(RS.main, sheet_dir["make"]) == 0
    _fill(sheet_dir["sheet"])
    filled = sheet_dir["sheet"].read_bytes()
    assert _code(RS.main, sheet_dir["make"]) == 2
    assert sheet_dir["sheet"].read_bytes() == filled
    assert _code(RS.main, sheet_dir["make"] + ["--force"]) == 0
    backups = list(sheet_dir["dir"].glob("review_sheet.*.bak.csv"))
    assert len(backups) == 1 and backups[0].read_bytes() == filled


def test_check_passes_on_sheet_from_current_train(sheet_dir):
    _code(RS.main, sheet_dir["make"])
    _fill(sheet_dir["sheet"])
    assert _code(RS.main, ["check", "--sheet", str(sheet_dir["sheet"])]) == 0


def test_check_rejects_sheet_when_train_aug_changed(sheet_dir):
    _code(RS.main, sheet_dir["make"])
    _fill(sheet_dir["sheet"])
    p = sheet_dir["dir"] / "train_aug.jsonl"
    p.write_text(p.read_text(encoding="utf-8") + "\n", encoding="utf-8")
    assert _code(RS.main, ["check", "--sheet", str(sheet_dir["sheet"])]) == 2


def test_check_rejects_sheet_with_ids_not_from_meta(sheet_dir):
    _code(RS.main, sheet_dir["make"])
    _fill(sheet_dir["sheet"])
    text = sheet_dir["sheet"].read_text(encoding="utf-8-sig")
    first_id = text.splitlines()[1].split(",")[0]
    sheet_dir["sheet"].write_text(text.replace(first_id, "MohlerASAG-FAKE", 1), encoding="utf-8-sig")
    assert _code(RS.main, ["check", "--sheet", str(sheet_dir["sheet"])]) == 2


def test_check_cp949_sheet_exits_2_with_hint(tmp_path, capsys):
    sheet = tmp_path / "filled.csv"
    header = ",".join(RS.SHEET_COLUMNS)
    sheet.write_bytes((header + "\n" + "a,b,q,r,a,문항,모범,답,True,paraphrase,이유,피드백,OK,\n").encode("cp949"))
    assert _code(RS.main, ["check", "--sheet", str(sheet)]) == 2
    assert "UTF-8" in capsys.readouterr().err


def test_make_escapes_formula_cells_and_check_ignores_quote(sheet_dir):
    rows = sheet_dir["rows"]
    for i, r in enumerate(rows):
        if r["subject"] == "코딩":
            r.update(B.record(r["id"], r, r["question"], r["model_answer"], f"=HYPERLINK({i})", r["label"], r["kind"],
                              r["reason"], r["feedback"], r["subject"]))
    (sheet_dir["dir"] / "train_aug.jsonl").write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    assert _code(RS.main, sheet_dir["make"]) == 0
    with open(sheet_dir["sheet"], encoding="utf-8-sig", newline="") as f:
        cells = [c for row in csv.reader(f) for c in row]
    assert any(c.startswith("'=HYPERLINK(") for c in cells)
    assert not any(c.startswith("=") for c in cells)
    assert RS.escape_cell("-1") == "'-1" and RS.unescape_cell("'-1") == "-1"
    assert RS.unescape_cell("'abc") == "'abc"


@pytest.mark.parametrize("extra", [["--n", "0"], ["--n", "-1"], ["--min-saf", "-1"], ["--n", "5", "--min-saf", "6"]])
def test_make_rejects_bad_n_and_min_saf(sheet_dir, extra):
    assert _code(RS.main, sheet_dir["make"] + extra) == 2
    assert not sheet_dir["sheet"].exists()


def test_make_without_train_aug_exits_2(tmp_path):
    d = tmp_path / "empty"
    d.mkdir()
    assert _code(RS.main, ["make", "--dir", str(d), "--datasets-dir", str(tmp_path)]) == 2


# ---------- Codex 1회차: 잘린 UTF-8 꼬리, 표본 크기 ----------

def test_cache_with_utf8_cut_mid_character_loads_and_repairs(tmp_path, capsys):
    p = tmp_path / "trans_cache.jsonl"
    good = json.dumps({"key": "a", "out": {"question": "한국어 번역"}, "error": None}, ensure_ascii=False) + "\n"
    raw = json.dumps({"key": "b", "out": {"question": "잘린 한국어 기록"}, "error": None}, ensure_ascii=False).encode()
    cut = raw.index("한".encode()) + 1  # 3바이트 문자의 첫 바이트 뒤에서 자른다
    p.write_bytes(good.encode() + raw[:cut])
    with pytest.raises(UnicodeDecodeError):
        p.read_text(encoding="utf-8")
    assert set(BC.load_cache(p)) == {"a"}
    assert "마지막 줄" in capsys.readouterr().err
    assert BC.repair_tail(p) is True
    with open(p, "a", encoding="utf-8") as f:
        f.write(json.dumps({"key": "c", "out": "다음 기록", "error": None}, ensure_ascii=False) + "\n")
    lines = p.read_text(encoding="utf-8").splitlines()
    assert [json.loads(line)["key"] for line in lines] == ["a", "c"]


def test_cache_with_broken_utf8_in_middle_line_raises(tmp_path):
    p = tmp_path / "trans_cache.jsonl"
    p.write_bytes(b'{"key": "a", "out": "\xed\x95"}\n{"key": "b", "out": 1, "error": null}\n')
    with pytest.raises(ValueError):
        BC.load_cache(p)


def test_v2_build_resumes_after_utf8_cut_cache(benv):
    v1 = benv["tmp"] / "v1"
    write_caches(v1, [x for x in benv["items"] if x["source_id"] != "M00.A00"])
    out = benv["tmp"] / "v2"
    _llm(benv)
    BC.main(_bargv(benv, out, v1))
    wpath = out / "write_cache.jsonl"
    wpath.write_bytes(wpath.read_bytes() + '{"key": "half", "out": "한국'.encode()[:-1])
    llm = _llm(benv)
    BC.main(_bargv(benv, out, v1))
    assert llm.translated == [] and llm.generated == []
    assert all(json.loads(line) for line in wpath.read_text(encoding="utf-8").splitlines())


def test_make_refuses_when_candidates_short(sheet_dir, capsys):
    assert _code(RS.main, sheet_dir["make"] + ["--n", "500"]) == 2
    assert "가능한 수" in capsys.readouterr().err
    assert not sheet_dir["sheet"].exists()
    saf = "SAF-communication-networks"
    rows = [r for r in sheet_dir["rows"] if r["source"] != saf][:60]
    rows += [r for r in sheet_dir["rows"] if r["source"] == saf][:5]
    (sheet_dir["dir"] / "train_aug.jsonl").write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    assert _code(RS.main, sheet_dir["make"]) == 2
    assert "SAF" in capsys.readouterr().err
    assert not sheet_dir["sheet"].exists()


def _rewrite_sheet(sheet, keep):
    with open(sheet, encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        fields, rows = reader.fieldnames, list(reader)
    rows = [r for r in rows if keep(r)]
    with open(sheet, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)
    return rows


def test_check_rejects_small_sheet_against_meta(sheet_dir):
    _code(RS.main, sheet_dir["make"])
    _fill(sheet_dir["sheet"])
    meta_p = RS.meta_path(sheet_dir["sheet"])
    kept = _rewrite_sheet(sheet_dir["sheet"], lambda r: r["source"] != "SAF-communication-networks")
    meta = json.loads(meta_p.read_text(encoding="utf-8"))
    meta["ids"] = [r["id"] for r in kept]  # id 대조를 맞춰 줘도 크기·SAF 검사에서 걸린다
    meta_p.write_text(json.dumps(meta), encoding="utf-8")
    assert _code(RS.main, ["check", "--sheet", str(sheet_dir["sheet"])]) == 2


def test_check_uses_default_30_8_when_meta_lacks_n(sheet_dir):
    _code(RS.main, sheet_dir["make"])
    _fill(sheet_dir["sheet"])
    meta_p = RS.meta_path(sheet_dir["sheet"])
    kept = _rewrite_sheet(sheet_dir["sheet"], lambda r: True)[:20]
    _rewrite_sheet(sheet_dir["sheet"], lambda r: r["id"] in {k["id"] for k in kept})
    meta = json.loads(meta_p.read_text(encoding="utf-8"))
    meta.pop("n")
    meta.pop("min_saf")
    meta["ids"] = [r["id"] for r in kept]
    meta_p.write_text(json.dumps(meta), encoding="utf-8")
    assert _code(RS.main, ["check", "--sheet", str(sheet_dir["sheet"])]) == 2


def test_sample_shortfall():
    assert RS.sample_shortfall(30, 8, 30, 8) is None
    assert RS.sample_shortfall(29, 8, 30, 8)
    assert RS.sample_shortfall(30, 7, 30, 8)


# ---------- Codex 2회차: 백업은 기존 파일을 덮지 않음, 사람이 채운 입력을 출력으로 덮지 않음 ----------

def test_force_twice_in_same_second_keeps_both_backups(sheet_dir, monkeypatch):
    monkeypatch.setattr(RS.time, "strftime", lambda fmt, *a: "20261125-120000")
    assert _code(RS.main, sheet_dir["make"]) == 0
    _fill(sheet_dir["sheet"], "LABEL_ERR")
    human = sheet_dir["sheet"].read_bytes()
    assert _code(RS.main, sheet_dir["make"] + ["--force"]) == 0
    blank = sheet_dir["sheet"].read_bytes()
    assert _code(RS.main, sheet_dir["make"] + ["--force"]) == 0
    backups = sorted(sheet_dir["dir"].glob("review_sheet.*.bak.csv"))
    assert [b.name for b in backups] == ["review_sheet.20261125-120000-1.bak.csv",
                                         "review_sheet.20261125-120000.bak.csv"]
    by_name = {b.name: b.read_bytes() for b in backups}
    assert by_name["review_sheet.20261125-120000.bak.csv"] == human
    assert by_name["review_sheet.20261125-120000-1.bak.csv"] == blank


def test_backup_sheet_never_overwrites_existing(tmp_path):
    sheet = tmp_path / "review_sheet.csv"
    sheet.write_text("새 내용", encoding="utf-8")
    taken = tmp_path / "review_sheet.T.bak.csv"
    taken.write_text("사람 판정", encoding="utf-8")
    b = RS.backup_sheet(sheet, "T")
    assert b.name == "review_sheet.T-1.bak.csv"
    assert taken.read_text(encoding="utf-8") == "사람 판정"


def test_build_refuses_review_true_that_is_an_output(benv):
    v1 = benv["tmp"] / "v1"
    write_caches(v1, benv["items"])
    out = benv["tmp"] / "v2"
    out.mkdir()
    review = out / "stats.json"
    review.write_text("id,scope,verdict,reason\n", encoding="utf-8")
    _llm(benv)
    assert _code(BC.main, _bargv(benv, out, v1, "--review-true", str(review))) == 2
    assert review.read_text(encoding="utf-8") == "id,scope,verdict,reason\n"


def test_clean_refuses_overrides_that_is_an_output(clean_dir):
    d = Path(clean_dir[1])
    overrides = d / "translation_review.csv"
    overrides.write_text("{}", encoding="utf-8")
    assert _code(C.main, clean_dir + ["--overrides", str(overrides)]) == 2
    assert overrides.read_text(encoding="utf-8") == "{}"
    assert not (d / "train_clean.jsonl").exists()


def test_augment_refuses_reviewed_bases_that_is_an_output(tmp_path):
    d = tmp_path / "v2"
    d.mkdir()
    reviewed = d / "aug_bases_new_for_review.csv"
    reviewed.write_text("base_id,verdict\nX,OK\n", encoding="utf-8")
    argv = ["--dir", str(d), "--eval-set", str(DEV_FILE), "--reviewed-bases", str(reviewed)]
    assert _code(A.main, argv) == 2
    assert reviewed.read_text(encoding="utf-8") == "base_id,verdict\nX,OK\n"
