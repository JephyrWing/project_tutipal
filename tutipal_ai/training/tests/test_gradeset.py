"""채점 평가 세트 형식 검사(eval.gradeset) 테스트. 데이터는 tmp_path에 만드는 합성 데이터다."""

import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

import pytest

from eval import gradeset, report

TRAINING = Path(__file__).resolve().parents[1]
DEV_FILE = TRAINING / "eval/grading_set.jsonl"

CORRECT_KINDS = ("exact", "paraphrase")
WRONG_KINDS = ("wrong", "partial", "unknown")
TRICKS = ("injection", "injection_with_answer", "tag_escape")
SECRET_MARKERS = ("SECRETQ", "SECRETM", "SECRETS", "IDXa", "QIDz")


def make_rows(n=60, n_correct=24, n_trick=12, n_code=24, n_q=12, subject="코딩"):
    """sealed 규칙을 지키는 행. 앞 n_correct행이 정답, 마지막 n_trick행이 꼼수, 앞 n_code행이 code 형식.
    qid는 i % n_q라 n_correct ≥ n_q, n - n_correct ≥ n_q이면 qid마다 정답·오답이 하나 이상 있다."""
    rows = []
    for i in range(n):
        q = i % n_q
        if i < n_correct:
            kind = CORRECT_KINDS[i % 2]
        elif i >= n - n_trick:
            kind = TRICKS[i % 3]
        else:
            kind = WRONG_KINDS[i % 3]
        rows.append({"id": f"IDXa{i:04d}", "qid": f"QIDz{q:02d}", "subject": subject,
                     "question": f"SECRETQ 문항 {q}", "model_answer": f"SECRETM 모범 답안 {q}",
                     "student_answer": f"SECRETS 학생 답안 {i}", "kind": kind,
                     "label": kind in CORRECT_KINDS, "answer_form": "code" if i < n_code else "text"})
    return rows


def as_lines(rows):
    return [(no, r) for no, r in enumerate(rows, 1)]


def fields(issues):
    return {i.field for i in issues}


def write(path: Path, rows) -> Path:
    path.write_text("".join((r if isinstance(r, str) else json.dumps(r, ensure_ascii=False)) + "\n" for r in rows),
                    encoding="utf-8")
    return path


# ---------- 공통 규칙 ----------

def test_profile_version_constant():
    assert gradeset.PROFILE_VERSION == "1"


def test_issue_has_line_field_reason():
    issue = gradeset.Issue(3, "label", "사유")
    assert (issue.line, issue.field, issue.reason) == (3, "label", "사유")


@pytest.mark.parametrize("profile", ["dev", "sealed"])
def test_valid_rows_have_no_issues(profile):
    assert gradeset.check_rows(as_lines(make_rows()), profile) == []


@pytest.mark.parametrize("field", ["id", "qid", "subject", "question", "model_answer", "student_answer", "kind",
                                   "label"])
def test_missing_required_field_is_reported(field):
    rows = make_rows()
    del rows[4][field]
    issues = gradeset.check_rows(as_lines(rows), "dev")
    assert any(i.line == 5 and i.field == field for i in issues), issues


@pytest.mark.parametrize("field, bad", [("id", 7), ("qid", None), ("subject", 1), ("question", ["x"]),
                                        ("model_answer", {}), ("student_answer", 3.0), ("kind", True),
                                        ("label", "true"), ("label", 1)])
def test_wrong_field_type_is_reported(field, bad):
    rows = make_rows()
    rows[2][field] = bad
    issues = gradeset.check_rows(as_lines(rows), "dev")
    assert any(i.line == 3 and i.field == field for i in issues), issues


@pytest.mark.parametrize("bad_id", ["has space", "한글id", "a/b", "", "x" * 129])
def test_id_must_match_leakcheck_pattern(bad_id):
    rows = make_rows()
    rows[0]["id"] = bad_id
    issues = gradeset.check_rows(as_lines(rows), "dev")
    assert any(i.line == 1 and i.field == "id" for i in issues), issues


def test_duplicate_id_is_reported():
    rows = make_rows()
    rows[7]["id"] = rows[3]["id"]
    issues = gradeset.check_rows(as_lines(rows), "dev")
    assert any(i.line == 8 and i.field == "id" for i in issues), issues


def test_unknown_kind_is_reported():
    rows = make_rows()
    rows[30]["kind"] = "bogus"
    assert "bogus" not in report.ANSWER_KINDS
    issues = gradeset.check_rows(as_lines(rows), "dev")
    assert any(i.line == 31 and i.field == "kind" for i in issues), issues


@pytest.mark.parametrize("kind, label", [("exact", False), ("paraphrase", False), ("wrong", True),
                                         ("injection_with_answer", True)])
def test_label_must_follow_kind(kind, label):
    rows = make_rows()
    rows[30]["kind"], rows[30]["label"] = kind, label
    issues = gradeset.check_rows(as_lines(rows), "dev")
    assert any(i.line == 31 and i.field == "label" for i in issues), issues


@pytest.mark.parametrize("field", ["question", "model_answer", "subject"])
def test_qid_must_map_to_one_question(field):
    rows = make_rows()
    rows[12][field] = rows[12][field] + " 다름"  # rows[0]과 같은 qid
    issues = gradeset.check_rows(as_lines(rows), "dev")
    assert fields(issues) & {"qid", "question", "model_answer", "subject"}, issues


def test_non_object_line_is_reported():
    lines = as_lines(make_rows())
    lines[5] = (6, None)
    issues = gradeset.check_rows(lines, "dev")
    assert any(i.line == 6 for i in issues), issues


def test_all_errors_are_collected():
    rows = make_rows()
    rows[1]["label"] = "true"
    rows[40]["kind"] = "bogus"
    issues = gradeset.check_rows(as_lines(rows), "dev")
    assert any(i.line == 2 and i.field == "label" for i in issues)
    assert any(i.line == 41 and i.field == "kind" for i in issues)


def test_dev_profile_allows_mixed_subjects_and_no_answer_form():
    rows = make_rows(n=10, n_correct=4, n_trick=1, n_code=0, n_q=2)
    for r in rows:
        del r["answer_form"]
    # qid 하나의 행을 모두 수학으로 바꿔야 qid 규칙을 지킨다
    for r in rows:
        if r["qid"] == rows[0]["qid"]:
            r["subject"] = "수학"
    assert gradeset.check_rows(as_lines(rows), "dev") == []


# ---------- sealed 규칙 ----------

def test_sealed_requires_coding_subject():
    rows = make_rows()
    for r in rows:
        if r["qid"] == "QIDz00":
            r["subject"] = "수학"
    assert gradeset.check_rows(as_lines(rows), "dev") == []
    issues = gradeset.check_rows(as_lines(rows), "sealed")
    assert "subject" in fields(issues)


@pytest.mark.parametrize("value", [None, "image", "CODE"])
def test_sealed_requires_answer_form_text_or_code(value):
    rows = make_rows()
    if value is None:
        del rows[10]["answer_form"]
    else:
        rows[10]["answer_form"] = value
    issues = gradeset.check_rows(as_lines(rows), "sealed")
    assert any(i.line == 11 and i.field == "answer_form" for i in issues), issues


def test_sealed_requires_at_least_60_rows():
    rows = make_rows(n=59, n_correct=15, n_trick=9, n_code=12)
    assert gradeset.check_rows(as_lines(make_rows(n=60, n_correct=15, n_trick=9, n_code=12)), "sealed") == []
    issues = gradeset.check_rows(as_lines(rows), "sealed")
    assert "n" in fields(issues), issues


def test_sealed_requires_correct_and_wrong_per_qid():
    rows = make_rows()
    for r in rows:  # QIDz05의 정답 행을 모두 오답으로
        if r["qid"] == "QIDz05" and r["label"]:
            r["kind"], r["label"] = "wrong", False
    rows[-1]["kind"], rows[-1]["label"] = "exact", True  # 전체 정답 비율은 유지(꼼수 하나를 정답으로)
    issues = gradeset.check_rows(as_lines(rows), "sealed")
    assert fields(issues) & {"qid", "label"}, issues


@pytest.mark.parametrize("n_correct, ok", [(15, True), (27, True), (14, False), (28, False)])
def test_sealed_correct_share_between_25_and_45_percent(n_correct, ok):
    rows = make_rows(n_correct=n_correct, n_trick=9, n_code=12)
    issues = gradeset.check_rows(as_lines(rows), "sealed")
    if ok:
        assert issues == []
    else:
        assert "label" in fields(issues), issues


@pytest.mark.parametrize("n_trick, ok", [(9, True), (8, False)])
def test_sealed_trick_share_at_least_15_percent(n_trick, ok):
    rows = make_rows(n_correct=20, n_trick=n_trick, n_code=12)
    issues = gradeset.check_rows(as_lines(rows), "sealed")
    if ok:
        assert issues == []
    else:
        assert "kind" in fields(issues), issues


@pytest.mark.parametrize("n_code, ok", [(12, True), (11, False)])
def test_sealed_code_share_at_least_20_percent(n_code, ok):
    rows = make_rows(n_correct=20, n_trick=9, n_code=n_code)
    issues = gradeset.check_rows(as_lines(rows), "sealed")
    if ok:
        assert issues == []
    else:
        assert "answer_form" in fields(issues), issues


# ---------- stats / read_lines ----------

def test_stats_counts_numbers():
    rows = make_rows()
    s = gradeset.stats(rows)
    assert s["n"] == 60
    assert s["qids"] == 12
    assert s["kind"]["exact"] == 12 and s["kind"]["paraphrase"] == 12
    assert sorted(s["label"].values()) == [24, 36]
    assert s["answer_form"] == {"code": 24, "text": 36}


def test_read_lines_marks_non_objects_as_none(tmp_path):
    path = write(tmp_path / "s.jsonl", [make_rows()[0], "not json", "[1, 2]"])
    lines = gradeset.read_lines(path)
    assert [no for no, _ in lines] == [1, 2, 3]
    assert isinstance(lines[0][1], dict) and lines[1][1] is None and lines[2][1] is None


# ---------- CLI ----------

def run_main(capsys, *argv):
    code = gradeset.main([str(a) for a in argv])
    out, err = capsys.readouterr()
    return code, out, err


def test_main_passes_valid_sealed_set_and_prints_numbers_only(tmp_path, capsys):
    path = write(tmp_path / "sealed.jsonl", make_rows())
    code, out, err = run_main(capsys, path, "--profile", "sealed")
    assert code == 0, err
    assert hashlib.sha256(path.read_bytes()).hexdigest() in out
    assert "profile_version" in out and gradeset.PROFILE_VERSION in out
    assert "60" in out
    for marker in SECRET_MARKERS:
        assert marker not in out and marker not in err


def test_main_default_profile_is_dev(tmp_path, capsys):
    rows = make_rows()
    rows[0]["subject"] = "수학"
    for r in rows:
        if r["qid"] == rows[0]["qid"]:
            r["subject"] = "수학"
    path = write(tmp_path / "dev.jsonl", rows)
    assert run_main(capsys, path)[0] == 0
    assert run_main(capsys, path, "--profile", "sealed")[0] == 1


def test_main_reports_every_error_with_file_line_field(tmp_path, capsys):
    rows = make_rows()
    rows[1]["label"] = "true"
    rows[3]["kind"] = "bogus"
    path = write(tmp_path / "bad.jsonl", rows)
    code, out, err = run_main(capsys, path)
    assert code == 1
    assert re.search(r"bad\.jsonl:2:label — \S", err), err
    assert re.search(r"bad\.jsonl:4:kind — \S", err), err
    for marker in SECRET_MARKERS:
        assert marker not in out and marker not in err


def test_main_set_level_errors_have_file_prefix_and_field(tmp_path, capsys):
    path = write(tmp_path / "small.jsonl", make_rows(n=59, n_correct=15, n_trick=9, n_code=12))
    code, out, err = run_main(capsys, path, "--profile", "sealed")
    assert code == 1
    assert re.search(r"small\.jsonl:[^:\n]*:n — \S", err), err


def test_main_sealed_does_not_print_subject_names(tmp_path, capsys):
    rows = make_rows()
    for r in rows:
        if r["qid"] == "QIDz00":
            r["subject"] = "비밀과목"
    path = write(tmp_path / "s.jsonl", rows)
    code, out, err = run_main(capsys, path, "--profile", "sealed")
    assert code == 1
    assert "비밀과목" not in out and "비밀과목" not in err


@pytest.mark.parametrize("bad_line", ["not json", "[1, 2]", '"text"'])
def test_main_non_object_line_exits_1(tmp_path, capsys, bad_line):
    rows = make_rows()
    path = write(tmp_path / "bad.jsonl", [*rows[:2], bad_line, *rows[2:]])
    code, out, err = run_main(capsys, path)
    assert code == 1
    assert "bad.jsonl:3:" in err


def test_main_missing_file_exits_2(tmp_path, capsys):
    assert run_main(capsys, tmp_path / "none.jsonl")[0] == 2


def test_main_non_utf8_file_exits_2(tmp_path, capsys):
    path = tmp_path / "latin.jsonl"
    path.write_bytes(b'{"id": "\xff\xfe"}\n')
    assert run_main(capsys, path)[0] == 2


def test_real_grading_set_passes_dev_and_fails_sealed(capsys):
    assert run_main(capsys, DEV_FILE)[0] == 0
    assert run_main(capsys, DEV_FILE, "--profile", "sealed")[0] == 1


def test_runs_as_module(tmp_path):
    path = write(tmp_path / "sealed.jsonl", make_rows())
    env = os.environ | {"PYTHONIOENCODING": "utf-8"}
    proc = subprocess.run([sys.executable, "-m", "eval.gradeset", str(path), "--profile", "sealed"],
                          cwd=TRAINING, capture_output=True, env=env)
    assert proc.returncode == 0, proc.stderr.decode("utf-8", "replace")
