"""run_eval의 답안 형식 복사, 과목 필터(--subjects), 리포트 재생성(--report-only) 테스트.

Ollama·Docker·네트워크 없이 돈다. OllamaClient는 가짜로 바꾸고 TRAINING_DIR은 tmp_path로 돌린다.
"""

import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from eval import run_eval
from eval.ollama_client import ChatResult, OllamaError


class FakeClient:
    def __init__(self, content='{"correct": true}', digests=None):
        self.content, self.calls = content, []
        self.digests = digests if digests is not None else {"m": "sha-m"}

    def chat(self, model, messages, **kw):
        self.calls.append((model, messages, kw))
        return ChatResult(self.content, 1.5, 30, 20.0, "stop")

    def model_digests(self):
        return dict(self.digests)


def _no_ollama(*args, **kwargs):
    raise AssertionError("OllamaClient를 만들면 안 된다")


ARGS = SimpleNamespace(num_predict=256, grade_num_predict=128, sandbox="local", test_timeout=10, ko_temperature=0.0)
GRADE_ITEM = {"id": "g-x", "qid": "q1", "subject": "코딩", "question": "Q?", "model_answer": "M",
              "student_answer": "S", "label": True, "kind": "exact"}


def _grade_row(i, subject="코딩", **kw):
    return {"id": f"g{i}", "qid": f"q{i}", "subject": subject, "question": "Q", "model_answer": "M",
            "student_answer": "S", "label": True, "kind": "exact"} | kw


def _write_jsonl(path: Path, rows: list[dict]) -> Path:
    path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    return path


def _args(tmp_path, **over):
    grade_set = tmp_path / "g.jsonl"
    if not grade_set.exists():
        grade_set.write_text('{"id": "1"}\n', encoding="utf-8")
    base = dict(tasks=["grade"], grade_set=grade_set, eval_set=tmp_path / "c.jsonl", ko_set=tmp_path / "k.jsonl",
                num_predict=2048, grade_num_predict=512, ko_temperature=0.0, test_timeout=20, sandbox="docker",
                limit=None, subjects=None)
    return SimpleNamespace(**(base | over))


# ---------- A4 answer_form 복사 ----------

@pytest.mark.parametrize("form", ["text", "code"])
def test_eval_grade_item_copies_answer_form(form):
    rec = run_eval.eval_grade_item(FakeClient(), {"name": "m"}, GRADE_ITEM | {"answer_form": form}, ARGS)
    assert rec["answer_form"] == form


def test_eval_grade_item_has_no_answer_form_key_when_item_lacks_it():
    rec = run_eval.eval_grade_item(FakeClient(), {"name": "m"}, GRADE_ITEM, ARGS)
    assert "answer_form" not in rec


def test_eval_grade_item_copies_answer_form_on_llm_error_too():
    class Broken(FakeClient):
        def chat(self, model, messages, **kw):
            raise OllamaError("HTTP 500")

    rec = run_eval.eval_grade_item(Broken(), {"name": "m"}, GRADE_ITEM | {"answer_form": "code"}, ARGS)
    assert rec["status"] == "llm_error" and rec["answer_form"] == "code"


# ---------- A5 과목 필터 ----------

def test_filter_subjects_none_returns_items_unchanged():
    items = [_grade_row(1), _grade_row(2, "수학")]
    assert run_eval.filter_subjects(items, None) == items


def test_filter_subjects_keeps_exact_matches_only():
    items = [_grade_row(1, "코딩"), _grade_row(2, "수학"), _grade_row(3, "코딩 "), _grade_row(4, "코딩심화"),
             _grade_row(5, "과학")]
    out = run_eval.filter_subjects(items, ["코딩", "과학"])
    assert [i["id"] for i in out] == ["g1", "g5"]


def test_filter_subjects_on_real_grading_set_gives_36_coding_items():
    items = run_eval.load_jsonl(run_eval.EVAL_DIR / "grading_set.jsonl")
    out = run_eval.filter_subjects(items, ["코딩"])
    assert len(out) == 36 and all(i["subject"] == "코딩" for i in out)


def test_load_items_filters_grade_before_limit_and_leaves_other_tasks(tmp_path):
    grade_set = _write_jsonl(tmp_path / "g.jsonl", [_grade_row(1, "수학"), _grade_row(2, "코딩"),
                                                     _grade_row(3, "수학"), _grade_row(4, "코딩")])
    ko_set = _write_jsonl(tmp_path / "k.jsonl", [{"id": "k1", "category": "개념", "question": "?", "checklist": ["a"]},
                                                 {"id": "k2", "category": "개념", "question": "?", "checklist": ["a"]}])
    args = _args(tmp_path, tasks=["grade", "ko"], grade_set=grade_set, ko_set=ko_set, subjects=["코딩"], limit=1)
    items = run_eval.load_items(args)
    assert [i["id"] for i in items["grade"]] == ["g2"]  # 필터 후 앞에서 1개
    assert [i["id"] for i in items["ko"]] == ["k1"]     # ko는 필터 없이 limit만


def test_load_items_on_real_grading_set_with_coding_filter(tmp_path):
    args = _args(tmp_path, grade_set=run_eval.EVAL_DIR / "grading_set.jsonl", subjects=["코딩"])
    assert len(run_eval.load_items(args)["grade"]) == 36


def test_run_config_records_sorted_unique_subjects(tmp_path):
    cfg = run_eval.run_config(_args(tmp_path, subjects=["코딩", "수학", "코딩"]), [{"name": "a"}], {"a": "x"})
    assert cfg["settings"]["subjects"] == ["수학", "코딩"]
    cfg = run_eval.run_config(_args(tmp_path), [{"name": "a"}], {"a": "x"})
    assert "subjects" in cfg["settings"] and cfg["settings"]["subjects"] is None


def test_subject_order_does_not_change_conditions(tmp_path):
    old = run_eval.run_config(_args(tmp_path, subjects=["수학", "코딩"]), [{"name": "a"}], {"a": "x"})
    new = run_eval.run_config(_args(tmp_path, subjects=["코딩", "수학"]), [{"name": "a"}], {"a": "x"})
    assert run_eval.config_conflicts(old, new) == []


def test_changed_subjects_conflict(tmp_path):
    old = run_eval.run_config(_args(tmp_path, subjects=["코딩"]), [{"name": "a"}], {"a": "x"})
    new = run_eval.run_config(_args(tmp_path, subjects=["수학"]), [{"name": "a"}], {"a": "x"})
    assert any("생성 설정" in p for p in run_eval.config_conflicts(old, new))


def test_normalize_settings_fills_missing_subjects():
    assert run_eval._normalize_settings({"limit": 5}) == {"limit": 5, "subjects": None}
    assert run_eval._normalize_settings({"limit": 5, "subjects": ["코딩"]}) == {"limit": 5, "subjects": ["코딩"]}
    assert run_eval._normalize_settings(None) == {"subjects": None}


def test_old_run_config_without_subjects_resumes_without_filter_only(tmp_path):
    old = run_eval.run_config(_args(tmp_path), [{"name": "a"}], {"a": "x"})
    del old["settings"]["subjects"]  # 이 기능 전에 만든 run_config
    same = run_eval.run_config(_args(tmp_path), [{"name": "a"}], {"a": "x"})
    assert run_eval.config_conflicts(old, same) == []
    filtered = run_eval.run_config(_args(tmp_path, subjects=["코딩"]), [{"name": "a"}], {"a": "x"})
    assert any("생성 설정" in p for p in run_eval.config_conflicts(old, filtered))


def test_main_runs_only_selected_subjects(tmp_path, monkeypatch):
    monkeypatch.setattr(run_eval, "TRAINING_DIR", tmp_path)
    grade_set = _write_jsonl(tmp_path / "g.jsonl", [_grade_row(1, "코딩"), _grade_row(2, "수학"), _grade_row(3, "코딩")])
    monkeypatch.setattr(run_eval, "OllamaClient", lambda url: FakeClient())
    assert run_eval.main(["--models", "m", "--grade-set", str(grade_set), "--run-name", "r",
                          "--subjects", "코딩"]) == 0
    recs = run_eval.load_jsonl(tmp_path / "results" / "r" / "results.jsonl")
    assert sorted(r["item_id"] for r in recs) == ["g1", "g3"]
    cfg = json.loads((tmp_path / "results" / "r" / "run_config.json").read_text(encoding="utf-8"))
    assert cfg["settings"]["subjects"] == ["코딩"]


def test_main_exits_2_before_ollama_when_filter_matches_nothing(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(run_eval, "TRAINING_DIR", tmp_path)
    grade_set = _write_jsonl(tmp_path / "g.jsonl", [_grade_row(1, "코딩")])
    monkeypatch.setattr(run_eval, "OllamaClient", _no_ollama)
    assert run_eval.main(["--models", "m", "--grade-set", str(grade_set), "--run-name", "r",
                          "--subjects", "없는과목"]) == 2
    assert not (tmp_path / "results" / "r" / "results.jsonl").exists()


def test_main_exits_2_before_ollama_when_subjects_without_grade_task(tmp_path, monkeypatch):
    monkeypatch.setattr(run_eval, "TRAINING_DIR", tmp_path)
    ko_set = _write_jsonl(tmp_path / "k.jsonl", [{"id": "k1", "category": "개념", "question": "?", "checklist": ["a"]}])
    monkeypatch.setattr(run_eval, "OllamaClient", _no_ollama)
    assert run_eval.main(["--models", "m", "--tasks", "ko", "--ko-set", str(ko_set), "--run-name", "r",
                          "--subjects", "코딩"]) == 2


def test_main_old_run_config_without_subjects(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(run_eval, "TRAINING_DIR", tmp_path)
    grade_set = _write_jsonl(tmp_path / "g.jsonl", [_grade_row(1, "코딩"), _grade_row(2, "수학")])
    monkeypatch.setattr(run_eval, "OllamaClient", lambda url: FakeClient())
    argv = ["--models", "m", "--grade-set", str(grade_set), "--run-name", "r"]
    assert run_eval.main(argv) == 0
    cfg_path = tmp_path / "results" / "r" / "run_config.json"
    cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
    cfg["settings"].pop("subjects", None)  # 옛 형식으로 되돌린다
    cfg_path.write_text(json.dumps(cfg, ensure_ascii=False), encoding="utf-8")

    assert run_eval.main(argv) == 0  # 필터 없이 재개: 충돌 없음
    capsys.readouterr()
    assert run_eval.main(argv + ["--subjects", "코딩"]) == 2
    assert "생성 설정" in capsys.readouterr().err


# ---------- A6 --report-only ----------

def grade_rec(model, item, label, predicted, kind="exact"):
    return {"kind": "grade", "model": model, "item_id": item, "qid": "q1", "subject": "코딩",
            "answer_kind": kind, "label": label, "predicted": predicted,
            "status": "ok" if predicted is not None else "invalid_json",
            "response": "{}", "wall_seconds": 1.0, "tokens_per_second": 30.0}


def ko_rec(model, item):
    return {"kind": "ko", "model": model, "item_id": item, "category": "개념", "question": "질문",
            "answer": "답", "checklist": ["a"], "status": "answered", "hangul_ratio": 1.0,
            "wall_seconds": 4.0, "tokens_per_second": 10.0}


@pytest.fixture
def run_dir(tmp_path, monkeypatch):
    monkeypatch.setattr(run_eval, "TRAINING_DIR", tmp_path)
    monkeypatch.setattr(run_eval, "OllamaClient", _no_ollama)
    d = tmp_path / "results" / "X"
    d.mkdir(parents=True)
    # 결과 첫 등장 순서: b, a, c
    _write_jsonl(d / "results.jsonl", [grade_rec("b", "1", True, True), grade_rec("a", "1", True, False),
                                       grade_rec("c", "1", False, False, "wrong"), grade_rec("a", "2", True, True)])
    return d


@pytest.fixture
def candidates(tmp_path):
    path = tmp_path / "cands.json"
    path.write_text(json.dumps({"models": [{"name": "a", "label": "Alpha", "think": False}]}), encoding="utf-8")
    return path


def _report_only(*extra):
    return run_eval.main(["--run-name", "X", "--report-only", *extra])


def test_report_only_regenerates_outputs_without_ollama(run_dir, candidates):
    cfg_text = json.dumps({"models": {"a": {"digest": "x", "think": False},
                                      "b": {"digest": "y", "think": False}}}, ensure_ascii=False, indent=2)
    (run_dir / "run_config.json").write_text(cfg_text, encoding="utf-8")
    assert _report_only("--candidates", str(candidates)) == 0
    summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
    assert list(summary) == ["a", "b", "c"]  # run_config 순서, 그 뒤 results에만 있는 모델
    assert summary["a"]["grade"]["accuracy"] == 0.5
    md = (run_dir / "summary.md").read_text(encoding="utf-8")
    assert "| Alpha |" in md and "| b |" in md and "| c |" in md
    assert (run_dir / "grade_errors.csv").exists()
    assert (run_dir / "run_config.json").read_text(encoding="utf-8") == cfg_text  # 건드리지 않음


def test_report_only_without_run_config_uses_first_appearance_and_creates_no_config(run_dir, candidates):
    assert _report_only("--candidates", str(candidates)) == 0
    summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
    assert list(summary) == ["b", "a", "c"]
    assert not (run_dir / "run_config.json").exists()


def test_report_only_with_broken_run_config_uses_first_appearance(run_dir, candidates):
    (run_dir / "run_config.json").write_text("{not json", encoding="utf-8")
    assert _report_only("--candidates", str(candidates)) == 0
    summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
    assert list(summary) == ["b", "a", "c"]
    assert (run_dir / "run_config.json").read_text(encoding="utf-8") == "{not json"


def test_report_only_labels_fall_back_to_model_name(run_dir, tmp_path):
    empty = tmp_path / "empty.json"
    empty.write_text(json.dumps({"models": []}), encoding="utf-8")
    assert _report_only("--candidates", str(empty)) == 0
    md = (run_dir / "summary.md").read_text(encoding="utf-8")
    assert "| a |" in md and "Alpha" not in md


def test_report_only_skips_grade_errors_without_grade_records(tmp_path, monkeypatch, candidates):
    monkeypatch.setattr(run_eval, "TRAINING_DIR", tmp_path)
    monkeypatch.setattr(run_eval, "OllamaClient", _no_ollama)
    d = tmp_path / "results" / "X"
    d.mkdir(parents=True)
    _write_jsonl(d / "results.jsonl", [ko_rec("a", "k1")])
    assert _report_only("--candidates", str(candidates)) == 0
    assert (d / "summary.md").exists() and (d / "summary.json").exists()
    assert not (d / "grade_errors.csv").exists()


def test_report_only_without_results_exits_2(tmp_path, monkeypatch, candidates):
    monkeypatch.setattr(run_eval, "TRAINING_DIR", tmp_path)
    monkeypatch.setattr(run_eval, "OllamaClient", _no_ollama)
    assert _report_only("--candidates", str(candidates)) == 2
    assert not (tmp_path / "results" / "X" / "run_config.json").exists()


@pytest.mark.parametrize("extra", [["--models", "a"], ["--subjects", "코딩"], ["--tasks", "grade"],
                                   ["--limit", "1"]])
def test_report_only_rejects_run_options(run_dir, candidates, extra):
    assert _report_only("--candidates", str(candidates), *extra) == 2
    assert not (run_dir / "summary.json").exists()
