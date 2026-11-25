"""PT-88 build_grading_c --v2 파이프라인 테스트 (가짜 원천·가짜 LLM, 네트워크 없음).

수용 기준 1(프롬프트 해시 캐시 키), 2(과학 행 선별은 예전 키), 3(모델 언로드 순서·keep_alive),
9(번역 검사 실패 → 다른 seed 로 한 번 더 → 또 실패면 거절), 11(작성 모델이 영어 원문·SAF 힌트를 받음),
18(무응답 템플릿, 작성 모델 호출 없음), 20(--exclude-review), 21(stats.json·rejected.jsonl 사유별 집계).

가짜 번역은 영어의 숫자·대문자 약어가 든 단어를 그대로 옮기고 한국어 비교·부정 표현을 덧붙여 trans_check 를
통과하게 만든다(번역 검사 실패를 일부러 만드는 행만 예외).
"""

import argparse
import csv
import io
import json
import re
import urllib.request
from collections import Counter
from pathlib import Path

import pytest
from _v2_data import (
    MOH,
    SAF,
    SCI,
    TRANSLATOR,
    WRITER,
    FakeRows,
    english_items,
    folder_hashes,
    load_jsonl,
    make_tables,
    quizai_file,
    write_caches,
)

from data_tools import build_grading_c as BC
from data_tools import build_grading_train as B
from data_tools import compare_hybrid as H
from data_tools import quality as Q
from data_tools.common import DEFAULT_SEED
from eval.run_eval import GRADE_SYSTEM

TRAINING = Path(__file__).resolve().parents[1]
DEV_FILE = TRAINING / "eval" / "grading_set.jsonl"

SCI_CACHED = "SC.0.00.1"      # 예전 키 번역·작성 캐시가 모두 있음 → 선별된다
SCI_TRANS_ONLY = "SC.0.01.1"  # 예전 키 번역 캐시만 → 선별되지 않는다
SCI_NONE = "SC.0.02.1"        # 캐시 없음 → 선별되지 않는다

NOANS_ID = "M00.NOANS"
GERMAN_ID = "de" + "0" * 30
DISAGREE_ID = "M00.GAP"
EXCLUDED_ID = "M01.A00"       # 검수표 LABEL_ERR
FAIL_ONCE_ID = "M00.A04"      # 학생 답안 번역이 처음엔 틀리고 다시 하면 맞음
FAIL_ALWAYS_ID = "M01.A04"    # 학생 답안 번역이 두 번 다 틀림
BAD_KO = "실패한 번역문입니다 않음 오직 최대 최소 초과 미만"  # 숫자가 빠져 'number' 규칙에 걸린다

TRUE_REASON = "모범 답안과 같은 뜻을 담고 있다."
TRUE_FEEDBACK = "핵심을 정확히 담았어요. 계속 이렇게 해 보세요."
FALSE_REASON = "핵심 개념을 다시 살펴볼 필요가 있다."
FALSE_FEEDBACK = "핵심 개념을 다시 확인해 보세요."


def good_ko(text: str) -> str:
    keep = [w for w in text.split() if re.search(r"\d|[A-Z]{2}", w)]
    return "번역 결과 문장입니다 " + " ".join(keep) + " 않음 오직 최대 최소 초과 미만"


class Recorder:
    """H.translate / H.generate / H.unload 대신 쓴다. 호출 순서를 calls 에 남긴다."""

    def __init__(self, fail_once=(), fail_always=()):
        self.calls: list[tuple[str, str]] = []
        self.translated: list[tuple[str, object]] = []
        self.prompts: list[str] = []
        self.fail_once, self.fail_always = set(fail_once), set(fail_always)
        self.seen: Counter = Counter()

    def translate(self, model, text, seed=None, **kw):
        self.calls.append(("translate", model))
        self.translated.append((text, seed))
        self.seen[text] += 1
        if text in self.fail_always or (text in self.fail_once and self.seen[text] == 1):
            return BAD_KO
        return good_ko(text)

    def generate(self, model, prompt, system=None, fmt=None, seed=None):
        self.calls.append(("generate", model))
        self.prompts.append(prompt)
        if B.KIND_DESC["paraphrase"] in prompt:
            return json.dumps({"reason": TRUE_REASON, "feedback": TRUE_FEEDBACK}, ensure_ascii=False)
        return json.dumps({"reason": FALSE_REASON, "feedback": FALSE_FEEDBACK}, ensure_ascii=False)

    def unload(self, model):
        self.calls.append(("unload", model))


def _extra_tables():
    t = make_tables()
    rel = B.SOURCE_FILES["mohler_open"]
    base = t[rel][0]
    t[rel] = list(t[rel]) + [
        {**base, "id": NOANS_ID, "student_answer": "Not answered.", "score_avg": 0.0,
         "score_grader_1": 0.0, "score_grader_2": 0.0},
        {**base, "id": DISAGREE_ID, "student_answer": "student says zeta shuffles the elements sometimes",
         "score_avg": 2.5, "score_grader_1": 4.0, "score_grader_2": 1.0},
    ]
    srel = B.SOURCE_FILES["saf"]
    t[srel] = list(t[srel]) + [{**t[srel][0], "id": GERMAN_ID,
                                "provided_answer": "Das Protokoll ist nicht für Übertragung gedacht und wird gelöscht"}]
    return t


@pytest.fixture
def env(tmp_path, monkeypatch):
    tables = _extra_tables()
    items = english_items(make_tables())  # 예전 키 캐시는 원래 합성 행에만 쓴다
    monkeypatch.setattr(B, "rows", FakeRows(tables))
    v1 = tmp_path / "derived" / "grading_v2"
    write_caches(v1, [x for x in items if x["source_id"] != SCI_NONE], trans_only={SCI_TRANS_ONLY})
    datasets = tmp_path / "datasets"
    datasets.mkdir()
    quizai = quizai_file(tmp_path / "QuizAi.java", GRADE_SYSTEM)
    out = tmp_path / "derived" / "grading_v3"
    by_id = {}
    for rel, rows in tables.items():
        for r in rows:
            by_id[r["id"]] = r
    return {"tables": tables, "items": items, "v1": v1, "out": out, "datasets": datasets, "quizai": quizai,
            "monkeypatch": monkeypatch, "by_id": by_id, "tmp": tmp_path}


def _argv(env, *extra):
    return ["--v2", "--out", str(env["out"]), "--datasets-dir", str(env["datasets"]), "--quizai", str(env["quizai"]),
            "--eval-set", str(DEV_FILE), "--cache-from", str(env["v1"]), *extra]


def _install(env, **kw):
    rec = Recorder(**kw)
    env["monkeypatch"].setattr(H, "translate", rec.translate)
    env["monkeypatch"].setattr(H, "generate", rec.generate)
    env["monkeypatch"].setattr(H, "unload", rec.unload)
    return rec


def _ans(env, sid):
    r = env["by_id"][sid]
    return r.get("student_answer") or r.get("provided_answer")


def _ns():
    return argparse.Namespace(translator=TRANSLATOR, writer=WRITER, seed=DEFAULT_SEED, v2=True)


def _stats(env):
    return json.loads((env["out"] / "stats.json").read_text(encoding="utf-8"))


def _rejected(env):
    return {(r["source"], r["source_id"]): r["error"] for r in load_jsonl(env["out"] / "rejected.jsonl")}


def _train(env):
    return {r["id"]: r for r in load_jsonl(env["out"] / "train.jsonl")}


# ---------- 수용 기준 1: 프롬프트 해시가 든 캐시 키 ----------

X = {"source": MOH, "source_id": "M00.A00"}


def test_tkey_has_prompt_hash_suffix():
    h = BC.trans_prompt_hash()
    assert re.fullmatch(r"[0-9a-f]{8}", h)
    assert BC.tkey(X, _ns()) == f"{MOH}:M00.A00:{TRANSLATOR}#p={h}"


def test_wkey_has_writer_hash_suffix():
    h = BC.writer_prompt_hash()
    assert re.fullmatch(r"[0-9a-f]{8}", h)
    assert BC.wkey(X, _ns()).endswith(f"#w={h}")
    assert BC.wkey(X, _ns()).startswith(f"{MOH}:M00.A00:{TRANSLATOR}>{WRITER}")


def test_text_key_has_prompt_hash_suffix():
    assert BC.text_key(TRANSLATOR, "abc").endswith(f"#p={BC.trans_prompt_hash()}")


def test_changing_translate_prompt_changes_keys(monkeypatch):
    before = (BC.tkey(X, _ns()), BC.text_key(TRANSLATOR, "abc"))
    monkeypatch.setattr(H, "TG_PROMPT", H.TG_PROMPT + " ")
    after = (BC.tkey(X, _ns()), BC.text_key(TRANSLATOR, "abc"))
    assert before[0] != after[0] and before[1] != after[1]


def test_changing_check_version_or_glossary_changes_trans_hash(monkeypatch):
    # 리더 지시(2026-11-25): 해시에는 원문에 나오는 용어집 항목만 들어간다 → 빠진 용어가 든 원문으로 확인한다.
    text = f"The {Q.GLOSSARY[-1]['en']} case."
    h0 = BC.trans_prompt_hash(text)
    monkeypatch.setattr(Q, "CHECK_VERSION", f"{Q.CHECK_VERSION}-changed")
    h1 = BC.trans_prompt_hash(text)
    monkeypatch.setattr(Q, "GLOSSARY", list(Q.GLOSSARY)[:-1])
    h2 = BC.trans_prompt_hash(text)
    assert len({h0, h1, h2}) == 3


def test_glossary_change_only_rekeys_rows_containing_the_term(monkeypatch):
    plain = {**X, "q": "Explain the stack.", "ref": "A stack is LIFO.", "ans": "last in first out"}
    mem = {**X, "q": "Where is it stored?", "ref": "In memory.", "ans": "in memory"}
    before = (BC.tkey(plain, _ns()), BC.tkey(mem, _ns()), BC.text_key(TRANSLATOR, plain["q"]))
    monkeypatch.setattr(Q, "GLOSSARY", [{**e, "ko": (*e["ko"], "주기억")} if e["en"] == "memory" else e
                                        for e in Q.GLOSSARY] + [{"en": "queue", "ko": ("큐",), "bad": ()}])
    after = (BC.tkey(plain, _ns()), BC.tkey(mem, _ns()), BC.text_key(TRANSLATOR, plain["q"]))
    assert after[0] == before[0] and after[2] == before[2]
    assert after[1] != before[1]


def test_changed_translation_forces_rewrite_even_with_same_wkey(env):
    """번역이 바뀌면(작성 캐시 키는 같아도) 예전 번역으로 쓴 이유·피드백을 다시 쓰고, 조립도 그 캐시를 쓰지 않는다."""
    _install(env)
    BC.main(_argv(env))
    x = {"source": MOH, "source_id": "M02.A02"}
    wk = BC.wkey(x, _ns())
    wc = {c["key"]: c for c in load_jsonl(env["out"] / "write_cache.jsonl")}
    stale = {**wc[wk], "out": {**wc[wk]["out"], "student_answer": "예전 번역 문장입니다 M02.A02 2 않음"}}
    with open(env["out"] / "write_cache.jsonl", "a", encoding="utf-8") as f:
        f.write(json.dumps(stale, ensure_ascii=False) + "\n")
    _install(env)
    BC.main(_argv(env, "--assemble-only"))
    assert _rejected(env)[(MOH, "M02.A02")] == "stale_write"
    rec = _install(env)
    BC.main(_argv(env))
    target = _ans(env, "M02.A02")
    assert sum(target in p for p in rec.prompts) == 1  # 이 행만 다시 썼다
    assert len(rec.prompts) == 1
    assert _train(env)[f"{MOH}-M02.A02"]["student_answer"] == good_ko(target)


def test_changing_writer_system_changes_wkey(monkeypatch):
    before = BC.wkey(X, _ns())
    monkeypatch.setattr(H, "WRITER_SYSTEM_V2", H.WRITER_SYSTEM_V2 + " ")
    assert BC.wkey(X, _ns()) != before


def test_old_key_functions_keep_v1_format():
    assert BC.old_tkey(X, _ns()) == f"{MOH}:M00.A00:{TRANSLATOR}"
    assert BC.old_wkey(X, _ns()) == f"{MOH}:M00.A00:{TRANSLATOR}>{WRITER}"


def test_assemble_only_with_only_old_keys_marks_every_row_missing(env, capsys):
    rec = _install(env)
    with pytest.raises(SystemExit) as exc:
        BC.main(_argv(env, "--assemble-only"))
    assert exc.value.code == 2  # 모든 행이 missing → 학습 행 0개
    err = capsys.readouterr().err
    assert "'missing'" in err
    assert "trans_check" not in err and "expl:" not in err
    assert rec.translated == [] and rec.prompts == []


def test_stats_record_prompt_hashes(env):
    _install(env)
    BC.main(_argv(env))
    assert _stats(env)["prompt_hash"] == {"trans": BC.trans_prompt_hash(), "write": BC.writer_prompt_hash()}


def test_old_cache_lines_are_kept_append_only(env):
    v1_trans = (env["v1"] / "trans_cache.jsonl").read_text(encoding="utf-8")
    before = folder_hashes(env["v1"])
    _install(env)
    BC.main(_argv(env))
    assert (env["out"] / "trans_cache.jsonl").read_text(encoding="utf-8").startswith(v1_trans)
    assert folder_hashes(env["v1"]) == before


# ---------- 수용 기준 2: 과학 행 선별은 예전 키 캐시 기준, 고른 뒤엔 새 프롬프트로 다시 ----------

def test_science_selection_uses_old_key_cache_then_retranslates(env):
    rec = _install(env)
    BC.main(_argv(env))
    sci = {r["id"]: r for r in env["tables"][B.SOURCE_FILES["scientsbank"]]}
    translated = {t for t, _ in rec.translated}
    assert sci[SCI_CACHED]["student_answer"] in translated
    assert sci[SCI_TRANS_ONLY]["student_answer"] not in translated
    assert sci[SCI_NONE]["student_answer"] not in translated
    seen = {r["source_id"] for r in load_jsonl(env["out"] / "train.jsonl") if r["source"] == SCI}
    seen |= {sid for (src, sid) in _rejected(env) if src == SCI}
    assert SCI_CACHED in seen
    assert SCI_TRANS_ONLY not in seen and SCI_NONE not in seen


# ---------- 수용 기준 3: 모델은 한 번에 하나, 단계 끝에 keep_alive 0 ----------

def test_unload_after_each_stage_in_order(env):
    rec = _install(env)
    BC.main(_argv(env))
    calls = rec.calls
    assert calls.count(("unload", TRANSLATOR)) == 1
    assert calls.count(("unload", WRITER)) == 1
    ut, uw = calls.index(("unload", TRANSLATOR)), calls.index(("unload", WRITER))
    t_idx = [i for i, c in enumerate(calls) if c[0] == "translate"]
    g_idx = [i for i, c in enumerate(calls) if c[0] == "generate"]
    assert t_idx and g_idx
    assert max(t_idx) < ut < min(g_idx)
    assert max(g_idx) < uw


class _Resp(io.BytesIO):
    pass


def _capture_urlopen(monkeypatch):
    bodies = []

    def fake_urlopen(req, timeout=None):
        bodies.append(json.loads(req.data.decode()))
        return _Resp(json.dumps({"message": {"role": "assistant", "content": "번역"}, "done": True}).encode())

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    return bodies


def test_unload_sends_keep_alive_zero(monkeypatch):
    bodies = _capture_urlopen(monkeypatch)
    H.unload(TRANSLATOR)
    assert len(bodies) == 1
    assert bodies[0]["model"] == TRANSLATOR
    assert bodies[0]["keep_alive"] == 0


def test_chat_requests_do_not_send_keep_alive(monkeypatch):
    bodies = _capture_urlopen(monkeypatch)
    H.generate(WRITER, "질문", system="시스템", fmt="json")
    H.translate(TRANSLATOR, "a stack")
    assert len(bodies) == 2
    assert all("keep_alive" not in b for b in bodies)


# ---------- 수용 기준 9: 번역 검사 실패 → 다른 seed 로 한 번 더 → 또 실패면 거절 ----------

def test_failed_field_is_retranslated_once_with_other_seed(env):
    once = _ans(env, FAIL_ONCE_ID)
    rec = _install(env, fail_once={once})
    BC.main(_argv(env))
    seeds = [s for t, s in rec.translated if t == once]
    assert len(seeds) == 2 and seeds[0] != seeds[1]
    row = _train(env)[f"{MOH}-{FAIL_ONCE_ID}"]
    assert row["student_answer"] == good_ko(once)


def test_field_failing_twice_is_rejected_with_rule_and_field(env):
    always = _ans(env, FAIL_ALWAYS_ID)
    rec = _install(env, fail_always={always})
    BC.main(_argv(env))
    assert sum(1 for t, _ in rec.translated if t == always) == 2
    expected = "trans_check:number:student_answer"
    assert _rejected(env)[(MOH, FAIL_ALWAYS_ID)] == expected
    assert f"{MOH}-{FAIL_ALWAYS_ID}" not in _train(env)
    x = {"source": MOH, "source_id": FAIL_ALWAYS_ID}
    tc = {c["key"]: c for c in load_jsonl(env["out"] / "trans_cache.jsonl")}
    assert tc[BC.tkey(x, _ns())]["error"] == expected


def test_text_memo_keeps_only_passing_translations(env):
    always = _ans(env, FAIL_ALWAYS_ID)
    _install(env, fail_always={always}, fail_once={_ans(env, FAIL_ONCE_ID)})
    BC.main(_argv(env))
    text = (env["out"] / BC.TEXT_CACHE).read_text(encoding="utf-8")
    assert BAD_KO not in text
    assert good_ko(_ans(env, FAIL_ONCE_ID)) in text


def test_assemble_only_rechecks_cached_translation(env):
    _install(env)
    BC.main(_argv(env))
    x = {"source": MOH, "source_id": FAIL_ONCE_ID}
    key = BC.tkey(x, _ns())
    tc = {c["key"]: c for c in load_jsonl(env["out"] / "trans_cache.jsonl")}
    bad = {**tc[key], "out": {**tc[key]["out"], "student_answer": BAD_KO}, "error": None}
    with open(env["out"] / "trans_cache.jsonl", "a", encoding="utf-8") as f:
        f.write(json.dumps(bad, ensure_ascii=False) + "\n")
    # 작성 캐시에도 번역 칸이 복사돼 있으므로 같이 바꾼다(어느 쪽을 다시 검사해도 걸리게)
    wk = BC.wkey(x, _ns())
    wc = {c["key"]: c for c in load_jsonl(env["out"] / "write_cache.jsonl")}
    wbad = {**wc[wk], "out": {**wc[wk]["out"], "student_answer": BAD_KO}, "error": None}
    with open(env["out"] / "write_cache.jsonl", "a", encoding="utf-8") as f:
        f.write(json.dumps(wbad, ensure_ascii=False) + "\n")
    _install(env)
    BC.main(_argv(env, "--assemble-only"))
    assert _rejected(env)[(MOH, FAIL_ONCE_ID)].startswith("trans_check:")


# ---------- 수용 기준 11: 작성 모델 입력 ----------

def _prompt_for(rec, english_answer):
    found = [p for p in rec.prompts if english_answer in p]
    assert found, "작성 프롬프트에 영어 학생 답안이 있어야 한다"
    return found[0]


def test_writer_gets_english_fields_and_saf_hint(env):
    rec = _install(env)
    BC.main(_argv(env))
    saf = next(r for r in env["tables"][B.SOURCE_FILES["saf"]] if r["id"] != GERMAN_ID)
    p = _prompt_for(rec, saf["provided_answer"])
    assert saf["question"] in p and saf["reference_answer"] in p
    assert f"<hint>{saf['answer_feedback']}</hint>" in p
    assert good_ko(saf["provided_answer"]) in p  # 한국어 번역도 그대로 들어간다


def test_writer_prompt_has_no_hint_block_when_hint_empty(env):
    rec = _install(env)
    BC.main(_argv(env))
    m = env["by_id"]["M02.A00"]
    p = _prompt_for(rec, m["student_answer"])
    assert m["question"] in p and m["instructor_answer"] in p
    assert "<hint>" not in p


def test_kind_desc_unchanged():
    assert B.KIND_DESC == {
        'paraphrase': '정답 (모범 답안과 뜻이 같음)',
        'partial': '오답 (핵심 일부만 맞거나 빠짐)',
        'wrong': '오답 (틀린 개념이거나 질문과 무관)',
        'unknown': '오답 (실제 답이 없음)',
    }


def test_writer_retries_on_expl_violation(env):
    rec = _install(env)
    target = _ans(env, "M02.A02")  # (1, 1) 오답 행
    calls = Counter()
    plain = rec.generate

    def flaky(model, prompt, system=None, fmt=None, seed=None):
        if target in prompt:
            calls["n"] += 1
            if calls["n"] == 1:
                rec.calls.append(("generate", model))
                rec.prompts.append(prompt)
                ko = good_ko(target)
                return json.dumps({"reason": f"{ko} 내용이 빠졌습니다.", "feedback": FALSE_FEEDBACK},
                                  ensure_ascii=False)
        return plain(model, prompt, system=system, fmt=fmt, seed=seed)

    env["monkeypatch"].setattr(H, "generate", flaky)
    BC.main(_argv(env))
    assert calls["n"] == 2
    retry = [p for p in rec.prompts if target in p][-1]
    assert "[다시 작성]" in retry and "expl:already_present" in retry
    assert _train(env)[f"{MOH}-M02.A02"]["reason"] == FALSE_REASON


# ---------- 수용 기준 18: 무응답 ----------

def test_no_answer_row_uses_template_without_writer(env):
    rec = _install(env)
    BC.main(_argv(env))
    row = _train(env)[f"{MOH}-{NOANS_ID}"]
    assert row["kind"] == "unknown" and row["label"] is False
    assert row["reason"] == Q.NO_ANSWER_REASON
    assert row["feedback"] == Q.NO_ANSWER_FEEDBACK
    assert not any("Not answered." in p for p in rec.prompts)


# ---------- 수용 기준 20: 사람 검수 제외 목록 ----------

def _review_csv(path, rows, header=("id", "source", "verdict", "note")):
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(rows)
    return path


def test_review_exclude_ids_reads_label_err_only(tmp_path):
    p = _review_csv(tmp_path / "review_sheet.csv", [
        [f"{MOH}-E07.Q07.A00", MOH, "LABEL_ERR", ""],
        [f"{MOH}-E12.Q01.A10", MOH, "TRANS_ERR", ""],
        [f"{MOH}-E08.Q03.A06", MOH, "EXPL_ERR", ""],
        [f"{MOH}-E11.Q05.A17", MOH, "OK", ""],
    ])
    assert BC.review_exclude_ids([p]) == {f"{MOH}-E07.Q07.A00"}


def test_review_exclude_ids_missing_columns_raises(tmp_path):
    p = _review_csv(tmp_path / "bad.csv", [["x", "y"]], header=("id", "note"))
    with pytest.raises(ValueError):
        BC.review_exclude_ids([p])


def test_exclude_review_drops_label_err_rows(env):
    p = _review_csv(env["tmp"] / "review_sheet.csv", [
        [f"{MOH}-{EXCLUDED_ID}", MOH, "LABEL_ERR", ""],
        [f"{MOH}-M01.A01", MOH, "TRANS_ERR", ""],
    ])
    rec = _install(env)
    BC.main(_argv(env, "--exclude-review", str(p)))
    train = _train(env)
    assert f"{MOH}-{EXCLUDED_ID}" not in train
    assert _rejected(env)[(MOH, EXCLUDED_ID)] == "exclude_review"
    assert _ans(env, EXCLUDED_ID) not in {t for t, _ in rec.translated}
    assert f"{MOH}-M01.A01" in train  # TRANS/EXPL 행은 다시 만든다


def test_exclude_review_missing_columns_exits_2(env, capsys):
    p = _review_csv(env["tmp"] / "bad.csv", [["x", "y"]], header=("id", "note"))
    _install(env)
    with pytest.raises(SystemExit) as exc:
        BC.main(_argv(env, "--exclude-review", str(p)))
    assert exc.value.code == 2
    assert "verdict" in capsys.readouterr().err  # 인자 오류가 아니라 열 없음으로 멈춰야 한다
    assert not (env["out"] / "train.jsonl").exists()


# ---------- 수용 기준 21: 사유별·출처별 집계 ----------

def test_stats_and_rejected_count_drops_per_reason_and_source(env):
    p = _review_csv(env["tmp"] / "review_sheet.csv", [[f"{MOH}-{EXCLUDED_ID}", MOH, "LABEL_ERR", ""]])
    _install(env, fail_always={_ans(env, FAIL_ALWAYS_ID)})
    BC.main(_argv(env, "--exclude-review", str(p)))
    rej = _rejected(env)
    assert rej[(SAF, GERMAN_ID)] == "german"
    assert rej[(MOH, DISAGREE_ID)] == "disagree"
    drops = _stats(env)["drops"]
    assert drops["german"] == {SAF: 1}
    assert drops["disagree"] == {MOH: 1}
    assert drops["exclude_review"] == {MOH: 1}
    assert drops["trans_check:number:student_answer"] == {MOH: 1}
    assert drops["unknown_template"] == {MOH: 1}


# ---------- 리뷰 반영: 이어 돌리기·실패 메모·규칙 완화 ----------

def _append(path, rec):
    with open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")


def test_resume_retries_rows_that_failed_with_ollama_exception(env):
    """Ollama 장애(URLError 등)로 기록된 번역·작성 오류는 같은 명령으로 이어 돌리면 다시 시도한다."""
    _install(env)
    BC.main(_argv(env))
    x = {"source": MOH, "source_id": "M02.A02"}
    tk, wk = BC.tkey(x, _ns()), BC.wkey(x, _ns())
    _append(env["out"] / "trans_cache.jsonl", {"key": tk, "out": None, "error": "URLError"})
    _install(env)
    BC.main(_argv(env, "--assemble-only"))
    assert _rejected(env)[(MOH, "M02.A02")] == "URLError"
    _install(env)
    BC.main(_argv(env))
    target = _ans(env, "M02.A02")
    # 번역을 다시 했다(같은 원문은 텍스트 메모가 돌려주므로 LLM 호출 대신 새 행 캐시 줄로 확인)
    tc = [c for c in load_jsonl(env["out"] / "trans_cache.jsonl") if c["key"] == tk]
    assert tc[-1]["error"] is None and tc[-1]["out"]["student_answer"] == good_ko(target)
    assert f"{MOH}-M02.A02" in _train(env)
    # 작성 단계 예외도 같다
    _append(env["out"] / "write_cache.jsonl", {"key": wk, "out": None, "error": "TimeoutError"})
    rec = _install(env)
    BC.main(_argv(env))
    assert sum(target in p for p in rec.prompts) == 1
    assert f"{MOH}-M02.A02" in _train(env)


def test_rule_rejections_are_not_retried_on_resume(env):
    """검사 규칙 위반(번역 두 번 실패)은 내용 문제라 이어 돌려도 다시 번역하지 않는다."""
    always = _ans(env, FAIL_ALWAYS_ID)
    _install(env, fail_always={always})
    BC.main(_argv(env))
    rec = _install(env, fail_always={always})
    BC.main(_argv(env))
    assert not any(t == always for t, _ in rec.translated)
    assert _rejected(env)[(MOH, FAIL_ALWAYS_ID)].startswith("trans_check:")


def test_text_memo_remembers_failures_within_a_run(tmp_path):
    """두 seed 모두 실패한 원문은 같은 실행 안에서 다시 번역하지 않고, 파일에는 남기지 않는다."""
    calls = []

    def fake(model, text, seed=None, **kw):
        calls.append(seed)
        return BAD_KO

    memo = BC.TextMemo(tmp_path / BC.TEXT_CACHE, fake)
    src = "Send 3 frames to the host."
    assert memo(TRANSLATOR, src) == BAD_KO
    assert memo(TRANSLATOR, src) == BAD_KO
    assert len(calls) == 2 and memo.hits == 1
    path = tmp_path / BC.TEXT_CACHE
    assert not path.exists() or BAD_KO not in path.read_text(encoding="utf-8")


def test_write_rejected_under_old_rule_is_revived_when_rule_now_passes(env):
    """예전 규칙으로 거절된 작성 줄도 지금 규칙으로 통과하면 다시 쓰지 않고 그대로 쓴다."""
    _install(env)
    BC.main(_argv(env))
    x = {"source": MOH, "source_id": "M02.A02"}
    wk = BC.wkey(x, _ns())
    wc = {c["key"]: c for c in load_jsonl(env["out"] / "write_cache.jsonl")}
    _append(env["out"] / "write_cache.jsonl", {**wc[wk], "error": "expl:old_rule_since_relaxed"})
    _install(env)
    BC.main(_argv(env, "--assemble-only"))
    assert f"{MOH}-M02.A02" in _train(env)
    rec = _install(env)
    BC.main(_argv(env))
    assert not any(_ans(env, "M02.A02") in p for p in rec.prompts)
    assert f"{MOH}-M02.A02" in _train(env)


def test_transient_errors_are_exception_names_only():
    assert BC.transient("URLError") and BC.transient("TimeoutError") and BC.transient("HTTPError")
    assert not BC.transient(None)
    assert not BC.transient("trans_check:number:student_answer")
    assert not BC.transient("expl:already_present")
    assert not BC.transient("JSONDecodeError")  # 모델 출력 형식 문제는 내용 문제로 본다
