"""리뷰 1차 지적에 대한 회귀 테스트(인코딩, 줄 나눔, 비문자열 값, --run-name 경로, --report-only 입력 오류)."""

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest
from eval import gradeset, report, run_eval, trainmix

TRAINING = Path(__file__).resolve().parents[1]


def _no_ollama(*args, **kwargs):
    raise AssertionError("OllamaClient를 만들면 안 된다")


def grade_rec(model, item, label, predicted, kind="exact", **kw):
    return {"kind": "grade", "model": model, "item_id": item, "qid": "q1", "subject": "코딩",
            "answer_kind": kind, "label": label, "predicted": predicted,
            "status": "ok" if predicted is not None else "invalid_json",
            "response": "{}", "wall_seconds": 1.0, "tokens_per_second": 30.0} | kw


def _write_jsonl(path: Path, rows) -> Path:
    path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    return path


def sealed_rows(n=60, n_correct=24, n_trick=12, n_code=24, n_q=12):
    rows = []
    for i in range(n):
        if i < n_correct:
            kind = ("exact", "paraphrase")[i % 2]
        elif i >= n - n_trick:
            kind = ("injection", "injection_with_answer", "tag_escape")[i % 3]
        else:
            kind = ("wrong", "partial", "unknown")[i % 3]
        q = i % n_q
        rows.append({"id": f"r{i:03d}", "qid": f"q{q:02d}", "subject": "코딩", "question": f"문항 {q}",
                     "model_answer": f"답 {q}", "student_answer": f"학생 {i}", "kind": kind,
                     "label": kind in ("exact", "paraphrase"), "answer_form": "code" if i < n_code else "text"})
    return rows


# ---------- Q1 cp949 stdout ----------

def test_report_only_cli_survives_cp949_stdout(tmp_path):
    run_dir = tmp_path / "results" / "X"
    run_dir.mkdir(parents=True)
    # 꼼수 행이 있어야 CI 칸에 en dash(–)가 찍힌다
    _write_jsonl(run_dir / "results.jsonl", [grade_rec("a", "1", True, True),
                                             grade_rec("a", "2", False, False, "injection")])
    code = ("import sys; from pathlib import Path; from eval import run_eval; "
            f"run_eval.TRAINING_DIR = Path({str(tmp_path)!r}); "
            "sys.exit(run_eval.cli(['--run-name', 'X', '--report-only']))")
    env = os.environ | {"PYTHONIOENCODING": "cp949:strict"}
    proc = subprocess.run([sys.executable, "-c", code], cwd=TRAINING, capture_output=True, env=env, check=False)
    assert proc.returncode == 0, proc.stderr.decode("utf-8", "replace")
    out = proc.stdout.decode("utf-8")
    assert "–" in out and "저장:" in out


# ---------- Q2 gradeset: kind가 list/dict ----------

@pytest.mark.parametrize("profile", ["dev", "sealed"])
@pytest.mark.parametrize("bad", [["x"], {"a": 1}])
def test_gradeset_unhashable_kind_is_an_issue_not_a_crash(tmp_path, capsys, profile, bad):
    rows = sealed_rows()
    rows[0]["kind"] = bad
    path = _write_jsonl(tmp_path / "s.jsonl", rows)
    assert gradeset.main([str(path), "--profile", profile]) == 1
    assert "s.jsonl:1:kind" in capsys.readouterr().err


# ---------- Q3 줄 나눔이 run_eval.load_jsonl과 같다 ----------

@pytest.mark.parametrize("sep", [" ", "\x85", "\x0c"])
def test_gradeset_does_not_split_on_unicode_line_separators(tmp_path, capsys, sep):
    rows = [json.dumps(r, ensure_ascii=False) for r in sealed_rows()]
    joined = rows[:2] + [rows[2] + sep + rows[3]] + rows[4:]
    path = tmp_path / "s.jsonl"
    path.write_text("\n".join(joined) + "\n", encoding="utf-8")
    assert gradeset.main([str(path)]) == 1
    assert "s.jsonl:3:" in capsys.readouterr().err
    with pytest.raises(ValueError):
        run_eval.load_jsonl(path)  # 소비자도 이 줄을 못 읽는다


def test_gradeset_accepts_raw_line_separator_inside_string(tmp_path, capsys):
    rows = sealed_rows()
    rows[5]["student_answer"] = "첫 줄 둘째 줄\x85셋째"
    path = _write_jsonl(tmp_path / "s.jsonl", rows)  # ensure_ascii=False라 U+2028이 그대로 들어간다
    assert " " in path.read_text(encoding="utf-8")
    assert gradeset.main([str(path), "--profile", "sealed"]) == 0, capsys.readouterr().err
    assert len(run_eval.load_jsonl(path)) == 60


def test_gradeset_and_load_jsonl_agree_on_crlf_and_cr(tmp_path):
    rows = [json.dumps(r, ensure_ascii=False) for r in sealed_rows()]
    path = tmp_path / "s.jsonl"
    path.write_bytes(("\r\n".join(rows[:30]) + "\r" + "\r".join(rows[30:]) + "\r\n").encode("utf-8"))
    assert len(gradeset.read_lines(path)) == len(run_eval.load_jsonl(path)) == 60


# ---------- Q4 BOM ----------

def test_bom_is_accepted_by_gradeset_and_run_eval(tmp_path):
    path = tmp_path / "s.jsonl"
    path.write_bytes(b"\xef\xbb\xbf" + "".join(json.dumps(r, ensure_ascii=False) + "\n"
                                               for r in sealed_rows()).encode("utf-8"))
    assert gradeset.check_rows(gradeset.read_lines(path), "sealed") == []
    assert len(run_eval.load_jsonl(path)) == 60


# ---------- Q5 filter_subjects ----------

def test_filter_subjects_skips_non_string_subjects():
    items = [{"id": "1", "subject": ["코딩"]}, {"id": "2", "subject": {"a": 1}}, {"id": "3", "subject": "코딩"},
             {"id": "4"}]
    assert [i["id"] for i in run_eval.filter_subjects(items, ["코딩"])] == ["3"]


# ---------- S6 --run-name 경로 ----------

@pytest.fixture
def sandbox_dir(tmp_path, monkeypatch):
    monkeypatch.setattr(run_eval, "TRAINING_DIR", tmp_path / "training")
    monkeypatch.setattr(run_eval, "OllamaClient", _no_ollama)
    (tmp_path / "training" / "results").mkdir(parents=True)
    return tmp_path


@pytest.mark.parametrize("report_only", [True, False])
@pytest.mark.parametrize("name", ["../escape", "..", ".", "a/../../escape"])
def test_run_name_outside_results_exits_2(sandbox_dir, name, report_only):
    outside = sandbox_dir / "escape"
    outside.mkdir()
    _write_jsonl(outside / "results.jsonl", [grade_rec("a", "1", True, True)])
    argv = ["--run-name", name] + (["--report-only"] if report_only else ["--models", "a"])
    assert run_eval.main(argv) == 2
    assert not (outside / "summary.md").exists() and not (outside / "run_config.json").exists()


def test_absolute_run_name_exits_2(sandbox_dir):
    target = sandbox_dir / "abs"
    target.mkdir()
    _write_jsonl(target / "results.jsonl", [grade_rec("a", "1", True, True)])
    assert run_eval.main(["--run-name", str(target), "--report-only"]) == 2
    assert not (target / "summary.md").exists()


def test_nested_run_name_inside_results_is_allowed(sandbox_dir):
    d = sandbox_dir / "training" / "results" / "team" / "r1"
    d.mkdir(parents=True)
    _write_jsonl(d / "results.jsonl", [grade_rec("a", "1", True, True)])
    assert run_eval.main(["--run-name", "team/r1", "--report-only"]) == 0
    assert (d / "summary.md").exists()


# ---------- Q7 --report-only 입력 오류 ----------

@pytest.mark.parametrize("content", ["", "\n\n", "{not json\n", '{"kind": "grade", "item_id": "1"}\n', "[1]\n",
                                     json.dumps(grade_rec("a", "1", True, True) | {"label": None}) + "\n"
                                     + json.dumps({"kind": "grade", "model": "a", "item_id": "2"}) + "\n"])
def test_report_only_bad_results_exit_2_without_traceback(sandbox_dir, capsys, content):
    d = sandbox_dir / "training" / "results" / "X"
    d.mkdir()
    (d / "results.jsonl").write_text(content, encoding="utf-8")
    assert run_eval.main(["--run-name", "X", "--report-only"]) == 2
    assert "오류" in capsys.readouterr().err


# ---------- Q8 문구 ----------

def test_subjects_with_limit_zero_mentions_limit(sandbox_dir, capsys):
    # answer_form이 있으면 봉인 세트로 보고 --sealed 없이는 먼저 2로 끝나므로 개발 세트 형태로 만든다
    rows = [{k: v for k, v in r.items() if k != "answer_form"} for r in sealed_rows()[:2]]
    grade_set = _write_jsonl(sandbox_dir / "g.jsonl", rows)
    assert run_eval.main(["--models", "m", "--grade-set", str(grade_set), "--run-name", "r",
                          "--subjects", "코딩", "--limit", "0"]) == 2
    assert "--limit" in capsys.readouterr().err


def test_gradeset_and_trainmix_say_folder_for_directory(tmp_path, capsys):
    assert gradeset.main([str(tmp_path)]) == 2
    assert "폴더" in capsys.readouterr().err
    assert trainmix.main(["--train", str(tmp_path)]) == 2
    assert "폴더" in capsys.readouterr().err


# ---------- S2 / F3 리포트 ----------

def test_by_form_groups_unknown_values():
    recs = [grade_rec("m", "1", True, True, answer_form="code"), grade_rec("m", "2", True, True, answer_form="IMAGE"),
            grade_rec("m", "3", True, True, answer_form=["code"])]
    by_form = report.summarize_grade(recs)["by_form"]
    assert set(by_form) == {"code", report.UNKNOWN}
    assert by_form[report.UNKNOWN]["n"] == 2


def test_subject_column_names_cannot_break_table():
    recs = [grade_rec("m", "1", True, True, subject="코|딩\n심화"), grade_rec("m", "2", True, True, subject=["x"])]
    md = report.to_markdown(report.summarize(recs))
    header = next(line for line in md.splitlines() if line.startswith("| 모델 |") and "심화" in line)
    assert "코\\|딩 심화" in header and report.UNKNOWN in header


def test_markdown_has_no_fixed_dev_set_counts():
    md = report.to_markdown(report.summarize([grade_rec("m", "1", True, True)]))
    assert "정답 40" not in md and "오답 80" not in md
