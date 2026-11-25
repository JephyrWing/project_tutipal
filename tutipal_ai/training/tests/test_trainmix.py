"""학습 데이터 구성 검사(eval.trainmix) 테스트. 데이터는 tmp_path에 만드는 합성 데이터다."""

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

from eval import trainmix

TRAINING = Path(__file__).resolve().parents[1]
ENV_TRAIN = "TUTIPAL_GRADING_TRAIN"
SECRET_MARKERS = ("SECRETQ", "SECRETM", "SECRETS", "SECRETMSG", "TIDx")


def row(i, subject="코딩", kind="wrong", qid=None, messages=True, source="synthetic", **kw):
    r = {"id": f"TIDx{i:04d}", "question": f"SECRETQ 문항 {i}", "model_answer": f"SECRETM 답 {i}",
         "student_answer": f"SECRETS 학생 {i}", "kind": kind, "label": kind in ("exact", "paraphrase"),
         "subject": subject, "source": source} | kw
    if qid is not None:
        r["qid"] = qid
    if messages:
        r["messages"] = [{"role": "system", "content": "SECRETMSG system"},
                         {"role": "user", "content": f"SECRETMSG user {i}"},
                         {"role": "assistant", "content": '{"correct": false}'}]
    return r


def mix(n=20, coding=12, trick=3, missing_messages=0):
    """n행 중 앞 coding행이 코딩, 마지막 trick행이 꼼수, 앞 missing_messages행은 messages 없음."""
    rows = []
    for i in range(n):
        kind = "injection" if i >= n - trick else ("exact" if i % 2 else "wrong")
        rows.append(row(i, subject="코딩" if i < coding else "수학", kind=kind, messages=i >= missing_messages))
    return rows


def write(path: Path, rows) -> Path:
    path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    return path


# ---------- mix_stats ----------

def test_mix_stats_counts_and_shares():
    s = trainmix.mix_stats(mix(n=20, coding=12, trick=3, missing_messages=2))
    assert s["n"] == 20
    assert s["coding_share"] == 12 / 20
    assert s["trick_share"] == 3 / 20
    assert s["missing_messages"] == 2
    assert s["subject"] == {"코딩": 12, "수학": 8}
    assert s["kind"]["injection"] == 3
    assert sum(s["label"].values()) == 20
    assert s["source"] == {"synthetic": 20}
    assert s["questions"] == 20


def test_mix_stats_unknown_fields_count_as_unspecified():
    rows = [row(1), row(2)]
    del rows[1]["subject"]
    del rows[1]["source"]
    del rows[1]["label"]
    s = trainmix.mix_stats(rows)
    assert s["subject"] == {"코딩": 1, "미지정": 1}
    assert s["source"] == {"synthetic": 1, "미지정": 1}
    assert s["label"].get("미지정") == 1
    assert s["coding_share"] == 0.5


def test_mix_stats_questions_use_qid_when_present():
    rows = [row(1, qid="a"), row(2, qid="a"), row(3, qid="b")]
    assert trainmix.mix_stats(rows)["questions"] == 2


def test_mix_stats_questions_use_normalized_question_without_qid():
    rows = [row(1, question="Hello   World"), row(2, question="hello world"), row(3, question="다른 문항")]
    assert trainmix.mix_stats(rows)["questions"] == 2


@pytest.mark.parametrize("kind", ["injection", "injection_with_answer", "tag_escape"])
def test_trick_share_uses_leakcheck_trick_kinds(kind):
    rows = [row(1, kind=kind), row(2, kind="wrong")]
    assert trainmix.mix_stats(rows)["trick_share"] == 0.5


# ---------- mix_problems ----------

def test_mix_problems_none_at_exact_boundaries():
    s = trainmix.mix_stats(mix(n=20, coding=12, trick=3))  # 0.6, 0.15
    assert trainmix.mix_problems(s, 0.6, 0.15) == []


def test_mix_problems_low_coding_share():
    s = trainmix.mix_stats(mix(n=20, coding=11, trick=3))
    problems = trainmix.mix_problems(s, 0.6, 0.15)
    assert len(problems) == 1 and all(isinstance(p, str) for p in problems)


def test_mix_problems_high_trick_share():
    s = trainmix.mix_stats(mix(n=20, coding=12, trick=4))
    assert len(trainmix.mix_problems(s, 0.6, 0.15)) == 1


def test_mix_problems_missing_messages():
    s = trainmix.mix_stats(mix(n=20, coding=12, trick=3, missing_messages=1))
    assert len(trainmix.mix_problems(s, 0.6, 0.15)) == 1


def test_mix_problems_reports_each_problem():
    s = trainmix.mix_stats(mix(n=20, coding=5, trick=10, missing_messages=1))
    assert len(trainmix.mix_problems(s, 0.6, 0.15)) == 3


# ---------- CLI ----------

def run_main(capsys, *argv):
    code = trainmix.main([str(a) for a in argv])
    out, err = capsys.readouterr()
    return code, out, err


def test_main_passes_at_default_thresholds(tmp_path, capsys, monkeypatch):
    monkeypatch.delenv(ENV_TRAIN, raising=False)
    path = write(tmp_path / "train.jsonl", mix(n=20, coding=12, trick=3))
    code, out, err = run_main(capsys, "--train", path)
    assert code == 0, err
    for marker in SECRET_MARKERS:
        assert marker not in out and marker not in err


@pytest.mark.parametrize("coding, trick, missing", [(11, 3, 0), (12, 4, 0), (12, 3, 1)])
def test_main_fails_on_bad_mix(tmp_path, capsys, coding, trick, missing):
    path = write(tmp_path / "train.jsonl", mix(n=20, coding=coding, trick=trick, missing_messages=missing))
    code, out, err = run_main(capsys, "--train", path)
    assert code == 1
    for marker in SECRET_MARKERS:
        assert marker not in out and marker not in err


def test_main_thresholds_can_be_changed(tmp_path, capsys):
    path = write(tmp_path / "train.jsonl", mix(n=20, coding=11, trick=4))
    assert run_main(capsys, "--train", path)[0] == 1
    assert run_main(capsys, "--train", path, "--min-coding-share", "0.5", "--max-trick-share", "0.2")[0] == 0


def test_main_reads_train_from_env_at_parse_time(tmp_path, capsys, monkeypatch):
    path = write(tmp_path / "train.jsonl", mix(n=20, coding=12, trick=3))
    monkeypatch.setenv(ENV_TRAIN, str(path))  # 모듈 import 뒤에 설정
    assert run_main(capsys)[0] == 0


def test_main_without_train_exits_2(capsys, monkeypatch):
    monkeypatch.delenv(ENV_TRAIN, raising=False)
    assert run_main(capsys)[0] == 2


@pytest.mark.parametrize("flag, value", [("--min-coding-share", "1.5"), ("--min-coding-share", "-0.1"),
                                         ("--max-trick-share", "1.01"), ("--max-trick-share", "-1")])
def test_main_rejects_share_out_of_range(tmp_path, capsys, flag, value):
    path = write(tmp_path / "train.jsonl", mix(n=20, coding=12, trick=3))
    assert run_main(capsys, "--train", path, flag, value)[0] == 2


def test_main_input_error_exits_2(tmp_path, capsys):
    assert run_main(capsys, "--train", tmp_path / "none.jsonl")[0] == 2
    bad = write(tmp_path / "bad.jsonl", [{"id": "x", "question": "q"}])  # 필수 필드 없음
    assert run_main(capsys, "--train", bad)[0] == 2


def test_runs_as_module(tmp_path):
    path = write(tmp_path / "train.jsonl", mix(n=20, coding=12, trick=3))
    env = {k: v for k, v in os.environ.items() if k != ENV_TRAIN} | {"PYTHONIOENCODING": "utf-8"}
    proc = subprocess.run([sys.executable, "-m", "eval.trainmix", "--train", str(path)],
                          cwd=TRAINING, capture_output=True, env=env)
    assert proc.returncode == 0, proc.stderr.decode("utf-8", "replace")
