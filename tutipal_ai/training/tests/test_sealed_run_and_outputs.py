"""봉인 모드(--sealed) 실행과 리포트 출력의 사전 검증·원자적 교체 테스트(Codex 최종 리뷰 1회차).

Ollama·네트워크 없이 돈다. OllamaClient는 가짜, TRAINING_DIR은 tmp_path.
"""

import csv
import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from eval import report, run_eval
from eval.ollama_client import ChatResult

RESPONSE = '{"reason": "RESPSECRET 이유", "correct": true, "feedback": "FEEDSECRET 잘했어요"}'
SECRETS = ("RESPSECRET", "FEEDSECRET", "SEALID", "SEALQID", "질문원문")


class FakeClient:
    def __init__(self, content=RESPONSE):
        self.content, self.calls = content, 0

    def chat(self, model, messages, **kw):
        self.calls += 1
        return ChatResult(self.content, 1.5, 30, 20.0, "stop")

    def model_digests(self):
        return {"m": "sha-m"}


def _no_ollama(*args, **kwargs):
    raise AssertionError("OllamaClient를 만들면 안 된다")


def _write_jsonl(path: Path, rows) -> Path:
    path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    return path


def sealed_item(i, label=True, form="code"):
    return {"id": f"SEALID{i:03d}", "qid": f"SEALQID{i % 2}", "subject": "코딩", "question": "질문원문",
            "model_answer": "모범", "student_answer": "학생", "label": label,
            "kind": "exact" if label else "wrong", "answer_form": form}


def dev_item(i, label=True):
    return {"id": f"g{i}", "qid": "q1", "subject": "코딩", "question": "Q", "model_answer": "M",
            "student_answer": "S", "label": label, "kind": "exact" if label else "wrong"}


@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setattr(run_eval, "TRAINING_DIR", tmp_path)
    client = FakeClient()
    monkeypatch.setattr(run_eval, "OllamaClient", lambda url: client)
    return tmp_path, client


def _sealed_set(tmp_path):
    # 정답 2개를 오답으로 판정하게 두면(가짜는 항상 true) 오답 문항이 틀린 채점이 된다
    return _write_jsonl(tmp_path / "sealed.jsonl", [sealed_item(1), sealed_item(2, False), sealed_item(3, False, "text")])


# ---------- P1 봉인 모드 ----------

def test_sealed_run_writes_no_raw_text_and_no_grade_errors(env, capsys):
    tmp_path, _ = env
    path = _sealed_set(tmp_path)
    assert run_eval.main(["--models", "m", "--grade-set", str(path), "--run-name", "s", "--sealed"]) == 0
    out = capsys.readouterr().out
    run_dir = tmp_path / "results" / "s"
    text = (run_dir / "results.jsonl").read_text(encoding="utf-8")
    for secret in SECRETS:
        assert secret not in text and secret not in out
    recs = run_eval.load_jsonl(run_dir / "results.jsonl")
    assert len(recs) == 3
    for r in recs:
        assert set(r) <= set(run_eval.SEALED_RECORD_KEYS)
        assert "response" not in r and "feedback" not in r and "qid" not in r
        assert r["item_id"].startswith("h:")
    assert {r["answer_form"] for r in recs} == {"code", "text"}
    assert not (run_dir / "grade_errors.csv").exists()
    summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
    assert summary["m"]["grade"]["accuracy"] == pytest.approx(1 / 3)  # 지표는 그대로 계산된다
    cfg = json.loads((run_dir / "run_config.json").read_text(encoding="utf-8"))
    assert cfg["settings"]["sealed"] is True


def test_sealed_set_without_flag_exits_2_before_ollama(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(run_eval, "TRAINING_DIR", tmp_path)
    monkeypatch.setattr(run_eval, "OllamaClient", _no_ollama)
    path = _sealed_set(tmp_path)
    assert run_eval.main(["--models", "m", "--grade-set", str(path), "--run-name", "s"]) == 2
    assert "--sealed" in capsys.readouterr().err
    assert not (tmp_path / "results" / "s").exists()


def test_one_answer_form_row_is_enough_to_require_sealed(tmp_path, monkeypatch):
    monkeypatch.setattr(run_eval, "TRAINING_DIR", tmp_path)
    monkeypatch.setattr(run_eval, "OllamaClient", _no_ollama)
    path = _write_jsonl(tmp_path / "g.jsonl", [dev_item(1), dev_item(2) | {"answer_form": "text"}])
    assert run_eval.main(["--models", "m", "--grade-set", str(path), "--run-name", "s"]) == 2


def test_sealed_only_with_grade_task(tmp_path, monkeypatch):
    monkeypatch.setattr(run_eval, "TRAINING_DIR", tmp_path)
    monkeypatch.setattr(run_eval, "OllamaClient", _no_ollama)
    path = _sealed_set(tmp_path)
    assert run_eval.main(["--models", "m", "--grade-set", str(path), "--run-name", "s", "--sealed",
                          "--tasks", "grade", "ko"]) == 2


def test_sealed_run_resumes_by_hashed_id(env):
    tmp_path, client = env
    path = _sealed_set(tmp_path)
    argv = ["--models", "m", "--grade-set", str(path), "--run-name", "s", "--sealed"]
    assert run_eval.main(argv) == 0
    assert run_eval.main(argv) == 0
    assert client.calls == 3  # 두 번째 실행은 남은 문항이 없다
    assert len(run_eval.load_jsonl(tmp_path / "results" / "s" / "results.jsonl")) == 3


def test_switching_sealed_mode_in_same_run_conflicts(env, capsys):
    tmp_path, _ = env
    path = _write_jsonl(tmp_path / "g.jsonl", [dev_item(1)])
    argv = ["--models", "m", "--grade-set", str(path), "--run-name", "r"]
    assert run_eval.main(argv) == 0
    capsys.readouterr()
    assert run_eval.main(argv + ["--sealed"]) == 2
    assert "생성 설정" in capsys.readouterr().err


def test_old_run_config_without_sealed_key_resumes(tmp_path):
    args = {"tasks": ["grade"], "grade_set": tmp_path / "g.jsonl", "eval_set": tmp_path / "c",
            "ko_set": tmp_path / "k", "num_predict": 2048, "grade_num_predict": 512, "ko_temperature": 0.0,
            "test_timeout": 20, "sandbox": "docker", "limit": None, "subjects": None}
    (tmp_path / "g.jsonl").write_text('{"id": "1"}\n', encoding="utf-8")
    old = run_eval.run_config(SimpleNamespace(**args), [{"name": "a"}], {"a": "x"})
    del old["settings"]["sealed"]  # 봉인 모드 기능 전의 run_config
    del old["settings"]["subjects"]
    same = run_eval.run_config(SimpleNamespace(**args), [{"name": "a"}], {"a": "x"})
    assert same["settings"]["sealed"] is False
    assert run_eval.config_conflicts(old, same) == []
    sealed = run_eval.run_config(SimpleNamespace(**args, sealed=True), [{"name": "a"}], {"a": "x"})
    assert any("생성 설정" in p for p in run_eval.config_conflicts(old, sealed))


def test_dev_run_unchanged_keeps_response_ids_and_grade_errors(env):
    tmp_path, _ = env
    path = _write_jsonl(tmp_path / "g.jsonl", [dev_item(1), dev_item(2, False)])
    assert run_eval.main(["--models", "m", "--grade-set", str(path), "--run-name", "d"]) == 0
    run_dir = tmp_path / "results" / "d"
    recs = run_eval.load_jsonl(run_dir / "results.jsonl")
    assert sorted(r["item_id"] for r in recs) == ["g1", "g2"]
    assert all(r["response"] == RESPONSE and r["qid"] == "q1" for r in recs)
    assert (run_dir / "grade_errors.csv").exists()


def test_redact_sealed_keeps_only_allowed_fields():
    rec = {"kind": "grade", "model": "m", "item_id": "SEALID001", "qid": "SEALQID0", "subject": "코딩",
           "answer_kind": "exact", "answer_form": "code", "label": True, "predicted": True, "status": "ok",
           "response": "RESPSECRET", "feedback": "FEEDSECRET", "error": "x", "wall_seconds": 1.0,
           "eval_tokens": 3, "tokens_per_second": 2.0, "done_reason": "stop", "model_digest": "d"}
    out = run_eval.redact_sealed(rec)
    assert out["item_id"] == run_eval.sealed_item_id("SEALID001") != "SEALID001"
    assert not {"qid", "response", "feedback", "error"} & set(out)
    assert out["label"] is True and out["answer_form"] == "code"


@pytest.mark.parametrize("with_config", [True, False])
def test_report_only_on_sealed_run_makes_no_grade_errors(env, with_config):
    tmp_path, _ = env
    path = _sealed_set(tmp_path)
    assert run_eval.main(["--models", "m", "--grade-set", str(path), "--run-name", "s", "--sealed"]) == 0
    run_dir = tmp_path / "results" / "s"
    if not with_config:
        (run_dir / "run_config.json").unlink()
    (run_dir / "summary.md").unlink()
    assert run_eval.main(["--run-name", "s", "--report-only"]) == 0
    assert (run_dir / "summary.md").exists()
    assert not (run_dir / "grade_errors.csv").exists()


def test_report_only_rejects_sealed_flag(env):
    tmp_path, _ = env
    d = tmp_path / "results" / "s"
    d.mkdir(parents=True)
    assert run_eval.main(["--run-name", "s", "--report-only", "--sealed"]) == 2


# ---------- P2 사전 검증과 원자적 출력 ----------

def grade_rec(item="1", **kw):
    return {"kind": "grade", "model": "a", "item_id": item, "qid": "q", "subject": "코딩", "answer_kind": "exact",
            "label": True, "predicted": True, "status": "ok", "response": "{}", "wall_seconds": 1.0,
            "tokens_per_second": 30.0} | kw


def ko_rec(item="k1", **kw):
    return {"kind": "ko", "model": "a", "item_id": item, "category": "개념", "question": "질문", "answer": "답",
            "checklist": ["a"], "status": "answered", "hangul_ratio": 1.0, "wall_seconds": 4.0,
            "tokens_per_second": 10.0} | kw


def code_rec(item="c1", **kw):
    return {"kind": "code", "model": "a", "item_id": item, "difficulty": "easy", "status": "passed",
            "wall_seconds": 2.0, "tokens_per_second": 10.0} | kw


@pytest.fixture
def run_dir(tmp_path, monkeypatch):
    monkeypatch.setattr(run_eval, "TRAINING_DIR", tmp_path)
    monkeypatch.setattr(run_eval, "OllamaClient", _no_ollama)
    d = tmp_path / "results" / "X"
    d.mkdir(parents=True)
    return d


def _drop(rec, key):
    rec = dict(rec)
    del rec[key]
    return rec


@pytest.mark.parametrize("bad", [
    _drop(grade_rec(), "label"), grade_rec(label="true"), _drop(grade_rec(), "answer_kind"), grade_rec(predicted="yes"),
    _drop(grade_rec(), "status"), _drop(grade_rec(), "wall_seconds"), grade_rec(wall_seconds="1"),
    grade_rec(tokens_per_second="fast"), _drop(code_rec(), "difficulty"), _drop(ko_rec(), "hangul_ratio"),
    ko_rec(checklist="a"), ko_rec(checklist=[1]), grade_rec(kind="other"),
])
def test_report_only_prevalidates_fields_and_writes_nothing(run_dir, capsys, bad):
    _write_jsonl(run_dir / "results.jsonl", [grade_rec("0"), bad])
    assert run_eval.main(["--run-name", "X", "--report-only"]) == 2
    assert "results.jsonl:2:" in capsys.readouterr().err
    assert sorted(p.name for p in run_dir.iterdir()) == ["results.jsonl"]


def test_report_only_allows_llm_error_records_without_response_fields(run_dir):
    _write_jsonl(run_dir / "results.jsonl", [
        grade_rec("1"),
        {"kind": "grade", "model": "a", "item_id": "2", "qid": "q", "subject": "코딩", "answer_kind": "exact",
         "label": True, "status": "llm_error", "error": "HTTP 500"},
        code_rec(), ko_rec()])
    assert run_eval.main(["--run-name", "X", "--report-only"]) == 0


def _seed_outputs(run_dir):
    _write_jsonl(run_dir / "results.jsonl", [grade_rec("1"), ko_rec()])
    assert run_eval.main(["--run-name", "X", "--report-only"]) == 0
    return {name: (run_dir / name).read_bytes() for name in ("summary.md", "summary.json", "grade_errors.csv",
                                                             "ko_review.csv")}


def test_report_bug_propagates_and_keeps_old_outputs(run_dir, monkeypatch):
    before = _seed_outputs(run_dir)

    def broken(*args, **kwargs):
        raise RuntimeError("report bug")

    monkeypatch.setattr(report, "to_markdown", broken)
    with pytest.raises(RuntimeError, match="report bug"):
        run_eval.main(["--run-name", "X", "--report-only"])
    assert {n: (run_dir / n).read_bytes() for n in before} == before


@pytest.mark.parametrize("target", ["write_grade_errors", "write_ko_review"])
def test_failure_while_writing_files_leaves_old_outputs_whole(run_dir, monkeypatch, target):
    before = _seed_outputs(run_dir)
    _write_jsonl(run_dir / "results.jsonl", [grade_rec("1"), grade_rec("2", predicted=False), ko_rec()])

    def broken(records, path):
        path.write_text("반쯤 쓴 파일", encoding="utf-8")
        raise KeyError("boom")

    monkeypatch.setattr(report, target, broken)
    with pytest.raises(KeyError):
        run_eval.main(["--run-name", "X", "--report-only"])
    assert {n: (run_dir / n).read_bytes() for n in before} == before
    assert not [p for p in run_dir.iterdir() if p.name.startswith(".staging-")]


def test_write_outputs_keeps_human_ko_scores(run_dir):
    _write_jsonl(run_dir / "results.jsonl", [ko_rec("k1")])
    assert run_eval.main(["--run-name", "X", "--report-only"]) == 0
    path = run_dir / "ko_review.csv"
    rows = list(csv.reader(path.open(encoding="utf-8-sig")))
    rows[1][-4:] = ["5", "4", "3", "좋음"]
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        csv.writer(f).writerows(rows)
    _write_jsonl(run_dir / "results.jsonl", [ko_rec("k1"), ko_rec("k2")])
    assert run_eval.main(["--run-name", "X", "--report-only"]) == 0
    rows = {r[0]: r for r in csv.reader(path.open(encoding="utf-8-sig"))}
    assert rows["k1"][-4:] == ["5", "4", "3", "좋음"] and rows["k2"][-4:] == ["", "", "", ""]


# ---------- Codex 2회차: 봉인 모드의 옛 grade_errors.csv 제거, 묶음 교체 ----------

OUTPUTS = ("summary.md", "summary.json", "grade_errors.csv", "ko_review.csv")


def _snapshot(run_dir):
    return {p.name: p.read_bytes() for p in run_dir.iterdir() if p.is_file()}


def _no_staging(run_dir):
    return not [p for p in run_dir.iterdir() if p.name.startswith(".staging-")]


def _sealed_grade_rec(i, **kw):
    return grade_rec(run_eval.sealed_item_id(f"SEALID{i}"), answer_form="code", **kw)


def test_sealed_run_removes_stale_grade_errors(env):
    tmp_path, _ = env
    run_dir = tmp_path / "results" / "s"
    run_dir.mkdir(parents=True)
    (run_dir / "grade_errors.csv").write_text("예전 원문 응답", encoding="utf-8")  # run_config 없이 남은 옛 파일
    path = _sealed_set(tmp_path)
    assert run_eval.main(["--models", "m", "--grade-set", str(path), "--run-name", "s", "--sealed"]) == 0
    assert not (run_dir / "grade_errors.csv").exists()
    assert (run_dir / "summary.md").exists() and _no_staging(run_dir)


def test_sealed_report_only_removes_stale_grade_errors(run_dir):
    _seed_outputs(run_dir)  # 일반 기록으로 grade_errors.csv까지 만든 폴더
    assert (run_dir / "grade_errors.csv").exists()
    _write_jsonl(run_dir / "results.jsonl", [_sealed_grade_rec(1), _sealed_grade_rec(2, predicted=False)])
    assert run_eval.main(["--run-name", "X", "--report-only"]) == 0
    assert not (run_dir / "grade_errors.csv").exists()
    assert (run_dir / "summary.md").exists() and _no_staging(run_dir)


def test_non_sealed_run_without_grade_records_keeps_existing_grade_errors(run_dir):
    _seed_outputs(run_dir)
    before = (run_dir / "grade_errors.csv").read_bytes()
    _write_jsonl(run_dir / "results.jsonl", [ko_rec()])
    assert run_eval.main(["--run-name", "X", "--report-only"]) == 0
    assert (run_dir / "grade_errors.csv").read_bytes() == before  # 지우는 건 봉인 모드뿐


def _failing_replace(monkeypatch, fail_at, after_move):
    """fail_at번째 _replace 호출에서 실패. after_move면 파일을 옮긴 뒤에 실패(교체는 됐는데 예외)."""
    real = run_eval._replace
    calls = {"n": 0}

    def fake(src, dst):
        calls["n"] += 1
        if calls["n"] == fail_at:
            if after_move:
                real(src, dst)
            raise OSError("replace failed")
        real(src, dst)

    monkeypatch.setattr(run_eval, "_replace", fake)
    return calls


@pytest.mark.parametrize("after_move", [False, True])
@pytest.mark.parametrize("fail_at", [1, 2, 3, 4])
def test_replace_failure_rolls_back_whole_bundle(run_dir, monkeypatch, fail_at, after_move):
    _seed_outputs(run_dir)
    before = _snapshot(run_dir)
    assert set(OUTPUTS) <= set(before)
    _write_jsonl(run_dir / "results.jsonl", [grade_rec("1"), grade_rec("2", predicted=False), ko_rec("k1"),
                                             ko_rec("k2")])
    before["results.jsonl"] = (run_dir / "results.jsonl").read_bytes()
    calls = _failing_replace(monkeypatch, fail_at, after_move)
    with pytest.raises(OSError, match="replace failed"):
        run_eval.main(["--run-name", "X", "--report-only"])
    assert calls["n"] == fail_at
    assert _snapshot(run_dir) == before  # 4개 출력 바이트 그대로, 새 파일 없음
    assert _no_staging(run_dir)


@pytest.mark.parametrize("after_move", [False, True])
def test_replace_failure_removes_files_that_did_not_exist_before(run_dir, monkeypatch, after_move):
    _write_jsonl(run_dir / "results.jsonl", [grade_rec("1")])
    assert run_eval.main(["--run-name", "X", "--report-only"]) == 0
    before = _snapshot(run_dir)
    assert "ko_review.csv" not in before
    _write_jsonl(run_dir / "results.jsonl", [grade_rec("1"), ko_rec()])
    before["results.jsonl"] = (run_dir / "results.jsonl").read_bytes()
    # 교체 순서: summary.json, summary.md, grade_errors.csv, ko_review.csv(새 파일)
    _failing_replace(monkeypatch, 4, after_move)
    with pytest.raises(OSError):
        run_eval.main(["--run-name", "X", "--report-only"])
    assert _snapshot(run_dir) == before and _no_staging(run_dir)


@pytest.mark.parametrize("fail_at", [1, 2])
def test_sealed_replace_failure_keeps_original_state(run_dir, monkeypatch, fail_at):
    _seed_outputs(run_dir)
    _write_jsonl(run_dir / "results.jsonl", [_sealed_grade_rec(1)])
    before = _snapshot(run_dir)
    _failing_replace(monkeypatch, fail_at, after_move=True)
    with pytest.raises(OSError):
        run_eval.main(["--run-name", "X", "--report-only"])
    assert _snapshot(run_dir) == before and _no_staging(run_dir)


def test_sealed_failure_after_delete_restores_original_state(run_dir, monkeypatch):
    """삭제 단계에서 지운 뒤 실패해도 원래 상태(옛 grade_errors.csv 포함)로 돌아간다.
    봉인 결과가 커밋되지 않았으므로 되살아난 파일은 봉인 run 전부터 있던 일반 run의 산출물이다."""
    _seed_outputs(run_dir)
    _write_jsonl(run_dir / "results.jsonl", [_sealed_grade_rec(1)])
    before = _snapshot(run_dir)
    real_unlink = Path.unlink
    target = run_dir / "grade_errors.csv"

    def unlink(self, missing_ok=False):
        real_unlink(self, missing_ok=missing_ok)
        if self == target:
            raise OSError("unlink failed after delete")

    monkeypatch.setattr(Path, "unlink", unlink)
    with pytest.raises(OSError, match="after delete"):
        run_eval.main(["--run-name", "X", "--report-only"])
    monkeypatch.undo()
    assert _snapshot(run_dir) == before and _no_staging(run_dir)


def test_rollback_failure_keeps_backup_and_reraises_original(run_dir, monkeypatch, capsys):
    before = _seed_outputs(run_dir)
    _write_jsonl(run_dir / "results.jsonl", [grade_rec("1"), grade_rec("2", predicted=False), ko_rec("k2")])
    _failing_replace(monkeypatch, 2, after_move=True)  # summary.json·summary.md가 새 것으로 바뀐 뒤 실패
    real_copy2 = run_eval.shutil.copy2

    def copy2(src, dst, *args, **kwargs):
        if Path(dst).resolve().parent == run_dir.resolve():  # 백업 만들기(staging 안으로)는 되고, 되돌리기(run_dir로)는 실패
            raise OSError("restore failed")
        return real_copy2(src, dst, *args, **kwargs)

    monkeypatch.setattr(run_eval.shutil, "copy2", copy2)
    with pytest.raises(OSError, match="replace failed") as info:
        run_eval.main(["--run-name", "X", "--report-only"])
    monkeypatch.undo()
    assert any(".backup" in note for note in getattr(info.value, "__notes__", []))
    assert ".backup" in capsys.readouterr().err
    stagings = [p for p in run_dir.iterdir() if p.name.startswith(".staging-")]
    assert len(stagings) == 1
    backup = stagings[0] / ".backup"
    assert {n: (backup / n).read_bytes() for n in OUTPUTS} == before  # 원래 4개 파일 백업이 남아 있다


def test_successful_commit_leaves_no_staging_and_updates_all(run_dir):
    before = _seed_outputs(run_dir)
    _write_jsonl(run_dir / "results.jsonl", [grade_rec("1"), grade_rec("2", predicted=False), ko_rec("k2")])
    assert run_eval.main(["--run-name", "X", "--report-only"]) == 0
    after = {n: (run_dir / n).read_bytes() for n in OUTPUTS}
    assert all(after[n] != before[n] for n in OUTPUTS)
    assert _no_staging(run_dir)
