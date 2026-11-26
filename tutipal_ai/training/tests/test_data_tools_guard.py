"""data_tools 출력 폴더 보호(git 작업 트리·UNC·빈 경로 거부)와 augment_tricks CLI 입력 검증 테스트.

데이터는 모두 합성이다. 저장소 안 경로는 존재하지 않는 하위 폴더만 쓰고, 만들어지지 않았는지 확인한다.
"""

import json
import os
import subprocess
import uuid
from pathlib import Path

import pytest

from data_tools import augment_tricks as A
from data_tools import build_grading_train as B
from data_tools import common

TRAINING = Path(__file__).resolve().parents[1]
DEV_FILE = TRAINING / "eval" / "grading_set.jsonl"
UNC = "\\" * 2


def _main_checkout() -> Path:
    """워크트리면 원래 체크아웃, 아니면 이 저장소 최상위(git --git-common-dir 의 부모)."""
    out = subprocess.run(["git", "-C", str(TRAINING), "rev-parse", "--path-format=absolute", "--git-common-dir"],
                         capture_output=True, text=True, check=True, timeout=60).stdout.strip()
    return Path(out).parent


def _row(rid, q, ans, label=True, ref="합성 모범 답안: 에너지는 형태가 바뀌어도 보존된다."):
    kind = "paraphrase" if label else "wrong"
    reason = "모범 답안과 같은 뜻이다." if label else "핵심 개념이 누락되었다."
    feedback = "맞았어요. 핵심을 담았어요." if label else "핵심 개념을 다시 확인해 보세요."
    return B.record(rid, {"source": "Synthetic", "source_id": rid}, q, ref, ans, label, kind, reason, feedback, "과학")


def _pool(n_q, n_true, n_false):
    rows = []
    for q in range(n_q):
        for j in range(n_true + n_false):
            rows.append(_row(f"SYN-{q:03d}-{j:02d}", f"합성 문항 {q}번: 원리를 설명하세요.",
                             f"학생 {q}-{j} 답안입니다 에너지가 바뀌어도 총량은 그대로 남습니다", j < n_true,
                             ref=f"합성 모범 답안 {q}번: 에너지는 보존된다."))
    return rows


def _jsonl(rows):
    return "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows)


@pytest.fixture
def no_review_lists(monkeypatch):
    monkeypatch.setattr(A, "EXCLUDE_ROWS", frozenset())
    monkeypatch.setattr(A, "EXCLUDE_QUESTIONS_OF", frozenset())


@pytest.fixture
def data_dir(tmp_path):
    d = tmp_path / "grading_c"
    d.mkdir()
    (d / "train_clean.jsonl").write_text(_jsonl(_pool(30, 2, 5)), encoding="utf-8")
    return d


def _run_main(argv):
    with pytest.raises(SystemExit) as exc:
        A.main(argv)
    return exc.value.code


OUTPUTS = ["train_aug.jsonl", "aug_bases.csv", "aug_stats.json", "aug_bases_new_for_review.csv"]


# ---------- git 작업 트리 ----------

def test_main_checkout_path_is_refused_from_worktree():
    target = _main_checkout() / "tutipal_ai" / "training" / "results" / f"guard_{uuid.uuid4().hex}"
    assert common.output_dir_problem(str(target)) is not None
    code = _run_main(["--dir", str(target), "--eval-set", str(DEV_FILE)])
    assert code == 2
    assert not target.exists()


def test_parent_git_repo_is_refused(tmp_path):
    """C:\\CODE-SPACE 처럼 상위 폴더가 저장소인 경우(아직 없는 깊은 하위 폴더도)."""
    (tmp_path / "outer" / ".git").mkdir(parents=True)
    target = tmp_path / "outer" / "a" / "b" / "c"
    assert "git" in common.output_dir_problem(str(target))
    assert _run_main(["--dir", str(target), "--eval-set", str(DEV_FILE)]) == 2
    assert not (tmp_path / "outer" / "a").exists()


def test_worktree_style_git_file_is_refused(tmp_path):
    (tmp_path / "wt").mkdir()
    (tmp_path / "wt" / ".git").write_text("gitdir: C:/elsewhere/.git/worktrees/wt\n", encoding="utf-8")
    assert common.output_dir_problem(str(tmp_path / "wt" / "out")) is not None


def test_plain_folder_outside_any_repo_is_allowed(tmp_path):
    assert common.output_dir_problem(str(tmp_path / "data")) is None


def test_git_missing_still_refuses_marked_folder(tmp_path, monkeypatch):
    """git 이 없어도 .git 표식 검사로 막는다. 표식이 없는 바깥 폴더는 허용한다."""
    def no_git(*args, **kwargs):
        raise FileNotFoundError("git")
    monkeypatch.setattr(common.subprocess, "run", no_git)
    (tmp_path / "repo" / ".git").mkdir(parents=True)
    assert common.output_dir_problem(str(tmp_path / "repo" / "out")) is not None
    assert common.output_dir_problem(str(tmp_path / "plain")) is None


def test_git_error_other_than_missing_fails_closed(tmp_path, monkeypatch):
    def timeout(*args, **kwargs):
        raise subprocess.TimeoutExpired("git", 1)
    monkeypatch.setattr(common.subprocess, "run", timeout)
    assert common.output_dir_problem(str(tmp_path / "plain")) is not None


def test_git_toplevel_ignores_git_env_overrides(monkeypatch, tmp_path):
    """GIT_CEILING_DIRECTORIES·GIT_DIR 로 저장소 판정을 피할 수 없다."""
    monkeypatch.setenv("GIT_CEILING_DIRECTORIES", str(TRAINING.parent))
    monkeypatch.setenv("GIT_DIR", str(tmp_path / "nope"))
    assert common._git_toplevel(str(TRAINING)) is not None


def test_different_drive_is_outside_but_same_drive_compare_is_fail_closed(monkeypatch):
    root = common.repo_root()
    other = "Z:\\data" if str(root)[:1].upper() != "Z" else "Y:\\data"
    if os.name == "nt":
        assert common.is_inside(other, root) is False

    def boom(paths):
        raise ValueError("cannot compare")
    monkeypatch.setattr(common.os.path, "commonpath", boom)
    assert common.is_inside(root / "x", root) is True


# ---------- UNC·장치 경로, 빈 경로 ----------

def _unc_variants(path: Path) -> list[str]:
    p = str(path.resolve())
    drive, rest = p[0], p[2:]
    return [
        UNC + "?\\" + p,
        UNC + "localhost\\" + drive + "$" + rest,
        UNC + "127.0.0.1\\" + drive.lower() + "$" + rest,
        UNC + "?\\UNC\\localhost\\" + drive + "$" + rest,
        UNC + "?\\Volume{00000000-0000-0000-0000-000000000000}" + rest,
        UNC + ".\\" + p,
        "//localhost/" + drive + "$" + rest.replace("\\", "/"),
    ]


@pytest.mark.skipif(os.name != "nt", reason="Windows 경로 형식")
@pytest.mark.parametrize("i", range(7))
def test_unc_and_device_paths_are_refused(i, tmp_path):
    for base in (TRAINING / "results" / f"guard_{uuid.uuid4().hex}", tmp_path / "out"):
        value = _unc_variants(base)[i]
        assert common.output_dir_problem(value) is not None, value
    value = _unc_variants(TRAINING / "results" / f"guard_{uuid.uuid4().hex}")[i]
    assert _run_main(["--dir", value, "--eval-set", str(DEV_FILE)]) == 2


@pytest.mark.parametrize("value", ["", " "])
def test_empty_dir_is_refused_and_writes_nothing(value, tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    (tmp_path / "train_clean.jsonl").write_text(_jsonl(_pool(30, 2, 5)), encoding="utf-8")
    assert _run_main(["--dir", value, "--eval-set", str(DEV_FILE)]) == 2
    assert not any((tmp_path / name).exists() for name in OUTPUTS)


# ---------- augment_tricks 입력 검증 ----------

def test_empty_eval_set_exits_2_and_writes_nothing(data_dir, no_review_lists, capsys):
    empty = data_dir.parent / "empty_eval.jsonl"
    empty.write_text("\n  \n", encoding="utf-8")
    assert _run_main(["--dir", str(data_dir), "--eval-set", str(empty), "--per-kind", "4"]) == 2
    assert "평가 세트" in capsys.readouterr().err
    assert not any((data_dir / name).exists() for name in OUTPUTS)


@pytest.mark.parametrize("content", ["", "\n\n   \n"])
def test_empty_train_exits_2_and_keeps_previous_output(content, data_dir, capsys):
    (data_dir / "train_clean.jsonl").write_text(content, encoding="utf-8")
    (data_dir / "train_aug.jsonl").write_text("previous\n", encoding="utf-8")
    assert _run_main(["--dir", str(data_dir), "--eval-set", str(DEV_FILE)]) == 2
    assert "행이 없음" in capsys.readouterr().err
    assert (data_dir / "train_aug.jsonl").read_text(encoding="utf-8") == "previous\n"
    assert not (data_dir / "aug_stats.json").exists()


@pytest.mark.parametrize("line", ["[1, 2]", "\"text\"", "3", "null"])
def test_non_object_line_exits_2_with_location(line, data_dir, capsys):
    rows = _pool(30, 2, 5)
    (data_dir / "train_clean.jsonl").write_text(_jsonl(rows[:1]) + line + "\n" + _jsonl(rows[1:]), encoding="utf-8")
    assert _run_main(["--dir", str(data_dir), "--eval-set", str(DEV_FILE)]) == 2
    err = capsys.readouterr().err
    assert "train_clean.jsonl:2" in err and "JSON 객체가 아님" in err
    assert "Traceback" not in err


def test_missing_field_message_names_field_and_line(data_dir, capsys):
    rows = _pool(30, 2, 5)
    del rows[2]["question"]
    (data_dir / "train_clean.jsonl").write_text(_jsonl(rows), encoding="utf-8")
    assert _run_main(["--dir", str(data_dir), "--eval-set", str(DEV_FILE)]) == 2
    assert "train_clean.jsonl:3: 필수 필드 없음: question" in capsys.readouterr().err


def test_bad_json_line_exits_2_with_location(data_dir, capsys):
    (data_dir / "train_clean.jsonl").write_text("{not json\n", encoding="utf-8")
    assert _run_main(["--dir", str(data_dir), "--eval-set", str(DEV_FILE)]) == 2
    assert "train_clean.jsonl:1: JSON 형식이 아님" in capsys.readouterr().err


@pytest.mark.parametrize("value", ["-1", "-48", "x"])
def test_per_kind_must_be_non_negative_int(value, data_dir):
    assert _run_main(["--dir", str(data_dir), "--eval-set", str(DEV_FILE), "--per-kind", value]) == 2
    assert not (data_dir / "train_aug.jsonl").exists()


def test_bom_is_accepted_for_train_and_eval(data_dir, tmp_path, no_review_lists):
    """eval.leakcheck 와 같게 UTF-8 BOM 을 허용한다."""
    train = (data_dir / "train_clean.jsonl").read_text(encoding="utf-8")
    (data_dir / "train_clean.jsonl").write_text(train, encoding="utf-8-sig")
    dev = tmp_path / "dev_bom.jsonl"
    dev.write_text(DEV_FILE.read_text(encoding="utf-8"), encoding="utf-8-sig")
    A.main(["--dir", str(data_dir), "--eval-set", str(dev), "--per-kind", "4", "--seed", "7"])
    assert json.loads((data_dir / "aug_stats.json").read_text(encoding="utf-8"))["new"] == 8


def test_reviewed_bases_without_base_id_column_exits_2(data_dir, tmp_path, no_review_lists, capsys):
    bad = tmp_path / "review.csv"
    bad.write_text("id,verdict\nSYN-1,OK\n", encoding="utf-8-sig")
    assert _run_main(["--dir", str(data_dir), "--eval-set", str(DEV_FILE), "--per-kind", "4",
                      "--reviewed-bases", str(bad)]) == 2
    assert "base_id" in capsys.readouterr().err
    assert not (data_dir / "train_aug.jsonl").exists()


def test_write_failure_keeps_previous_outputs_and_leaves_no_temp(data_dir, no_review_lists, monkeypatch, capsys):
    for name in ("train_aug.jsonl", "aug_bases.csv", "aug_stats.json"):
        (data_dir / name).write_text(f"previous {name}\n", encoding="utf-8")
    real_fdopen = os.fdopen
    calls = {"n": 0}

    def flaky_fdopen(*args, **kwargs):
        calls["n"] += 1
        if calls["n"] == 3:
            raise OSError(28, "No space left on device")
        return real_fdopen(*args, **kwargs)

    monkeypatch.setattr(common.os, "fdopen", flaky_fdopen)
    assert _run_main(["--dir", str(data_dir), "--eval-set", str(DEV_FILE), "--per-kind", "4"]) == 2
    for name in ("train_aug.jsonl", "aug_bases.csv", "aug_stats.json"):
        assert (data_dir / name).read_text(encoding="utf-8") == f"previous {name}\n"
    assert sorted(p.name for p in data_dir.iterdir()) == ["aug_bases.csv", "aug_stats.json", "train_aug.jsonl",
                                                          "train_clean.jsonl"]
    assert "No space left" in capsys.readouterr().err


# ---------- 네트워크 차단(conftest) ----------

def test_raw_socket_connect_is_blocked():
    import socket
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        with pytest.raises(RuntimeError, match="network access is disabled"):
            s.connect(("127.0.0.1", 11434))
        with pytest.raises(RuntimeError, match="network access is disabled"):
            s.connect_ex(("127.0.0.1", 11434))
