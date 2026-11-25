"""data_tools 패키지(채점 학습 데이터 파이프라인) 이동·경로·CLI 테스트.

실제 학습 데이터는 쓰지 않는다. 경로는 tmp_path(저장소 밖)와 저장소 안의 존재하지 않는 폴더만 쓴다.
"""

import os
import socket
import subprocess
import sys
import urllib.request
import uuid
from pathlib import Path

import pytest

from data_tools import (
    augment_tricks,
    build_grading_c,
    build_grading_train,
    clean_grading_c,
    compare_hybrid,
)
from eval import run_eval

TRAINING = Path(__file__).resolve().parents[1]
REPO = TRAINING.parents[1]
DATA_TOOLS = TRAINING / "data_tools"
DEV_FILE = TRAINING / "eval" / "grading_set.jsonl"

MODULE_NAMES = ["augment_tricks", "build_grading_train", "clean_grading_c", "build_grading_c", "compare_hybrid"]
MODULES = {
    "augment_tricks": augment_tricks,
    "build_grading_train": build_grading_train,
    "clean_grading_c": clean_grading_c,
    "build_grading_c": build_grading_c,
    "compare_hybrid": compare_hybrid,
}
FORBIDDEN_SOURCE_TEXT = ["junse", "tutipal_datasets", "sys.path.insert", "parent.parent"]


def _quizai_file(path: Path, system: str) -> Path:
    """QuizAi.java 와 같은 모양(텍스트 블록)으로 GRADE_SYSTEM 을 담은 가짜 파일."""
    body = "\n".join("            " + line for line in system.split("\n"))
    path.write_text(
        "class QuizAi {\n    static final String GRADE_SYSTEM = \"\"\"\n" + body + "\n            \"\"\";\n}\n",
        encoding="utf-8",
    )
    return path


@pytest.fixture
def quizai_ok(tmp_path):
    return _quizai_file(tmp_path / "QuizAi.java", run_eval.GRADE_SYSTEM)


@pytest.fixture
def quizai_mismatch(tmp_path):
    return _quizai_file(tmp_path / "QuizAiOther.java", "다른 시스템 프롬프트")


def _full_args(name: str, tmp_path: Path, quizai: Path) -> dict[str, str]:
    """모듈별로 필요한 인자 전체(값은 tmp_path 아래 경로)."""
    data_dir = tmp_path / "data"
    datasets = tmp_path / "datasets"
    data_dir.mkdir(exist_ok=True)
    datasets.mkdir(exist_ok=True)
    out = str(tmp_path / "out")
    return {
        "augment_tricks": {"--dir": str(data_dir), "--eval-set": str(DEV_FILE)},
        "clean_grading_c": {"--dir": str(data_dir), "--datasets-dir": str(datasets), "--eval-set": str(DEV_FILE)},
        "build_grading_c": {"--out": out, "--datasets-dir": str(datasets),
                            "--quizai": str(quizai), "--eval-set": str(DEV_FILE)},
        "build_grading_train": {"--out": out, "--datasets-dir": str(datasets),
                                "--quizai": str(quizai), "--eval-set": str(DEV_FILE)},
        "compare_hybrid": {"--out": out, "--datasets-dir": str(datasets),
                           "--translator": "fake-translator", "--writer": "fake-writer"},
    }[name]


def _argv(args: dict[str, str]) -> list[str]:
    out: list[str] = []
    for k, v in args.items():
        out += [k, v]
    return out


REQUIRED_PATH_ARGS = [
    ("augment_tricks", "--dir"),
    ("augment_tricks", "--eval-set"),
    ("clean_grading_c", "--dir"),
    ("clean_grading_c", "--datasets-dir"),
    ("clean_grading_c", "--eval-set"),
    ("build_grading_c", "--out"),
    ("build_grading_c", "--datasets-dir"),
    ("build_grading_train", "--out"),
    ("build_grading_train", "--datasets-dir"),
    ("compare_hybrid", "--out"),
    ("compare_hybrid", "--datasets-dir"),
]

# (모듈, 저장소 안을 가리키면 안 되는 출력 인자)
OUTPUT_DIR_ARGS = [
    ("augment_tricks", "--dir"),
    ("clean_grading_c", "--dir"),
    ("build_grading_c", "--out"),
    ("build_grading_train", "--out"),
    ("compare_hybrid", "--out"),
]


def _subprocess_env(**extra: str) -> dict[str, str]:
    env = dict(os.environ)
    env.pop("PYTHONPATH", None)
    env["PYTHONIOENCODING"] = "utf-8"
    env.update(extra)
    return env


# ---------- A. 이동·실행 ----------

@pytest.mark.parametrize("name", MODULE_NAMES)
def test_module_help_exits_zero(name):
    proc = subprocess.run([sys.executable, "-m", f"data_tools.{name}", "--help"], cwd=TRAINING, check=False,
                          capture_output=True, text=True, encoding="utf-8", env=_subprocess_env(), timeout=120)
    assert proc.returncode == 0, proc.stderr
    assert "usage" in proc.stdout.lower()


def test_package_has_init_and_all_modules():
    assert (DATA_TOOLS / "__init__.py").is_file()
    for name in MODULE_NAMES:
        assert (DATA_TOOLS / f"{name}.py").is_file(), name


@pytest.mark.parametrize("name,arg", REQUIRED_PATH_ARGS, ids=[f"{n}{a}" for n, a in REQUIRED_PATH_ARGS])
def test_main_without_required_path_arg_exits_2(name, arg, tmp_path, quizai_mismatch, capsys):
    args = _full_args(name, tmp_path, quizai_mismatch)
    del args[arg]
    with pytest.raises(SystemExit) as exc:
        MODULES[name].main(_argv(args))
    assert exc.value.code == 2
    assert arg in capsys.readouterr().err


def test_source_has_no_personal_paths_or_path_hacks():
    files = sorted(DATA_TOOLS.glob("*.py"))
    assert files, "data_tools/*.py 가 없다"
    hits = []
    for f in files:
        text = f.read_text(encoding="utf-8")
        for needle in FORBIDDEN_SOURCE_TEXT:
            if needle in text:
                hits.append(f"{f.name}: {needle}")
    assert hits == []


def _inside_repo_targets() -> list[str]:
    name = f"data_tools_guard_{uuid.uuid4().hex}"
    plain = TRAINING / "results" / name
    dotted = f"{REPO}{os.sep}tutipal_ai{os.sep}..{os.sep}tutipal_ai{os.sep}training{os.sep}results{os.sep}{name}x"
    return [str(plain), dotted]


@pytest.mark.parametrize("name,arg", OUTPUT_DIR_ARGS, ids=[f"{n}{a}" for n, a in OUTPUT_DIR_ARGS])
@pytest.mark.parametrize("variant", [0, 1], ids=["plain", "dotdot"])
def test_output_dir_inside_repo_exits_2_before_writing(name, arg, variant, tmp_path, quizai_ok):
    target = _inside_repo_targets()[variant]
    args = _full_args(name, tmp_path, quizai_ok)
    args[arg] = target
    with pytest.raises(SystemExit) as exc:
        MODULES[name].main(_argv(args))
    assert exc.value.code == 2
    assert not Path(target).exists(), "저장소 안에 폴더를 만들면 안 된다"


@pytest.mark.parametrize("name", MODULE_NAMES)
def test_import_has_no_side_effects(name):
    code = (
        "import sys\n"
        "enc0 = sys.stdout.encoding\n"
        f"import data_tools.{name}\n"
        "print('pyarrow' in sys.modules, sys.stdout.encoding == enc0)\n"
    )
    proc = subprocess.run([sys.executable, "-c", code], cwd=TRAINING, check=False, capture_output=True, text=True,
                          env=_subprocess_env(PYTHONIOENCODING="latin-1"), timeout=120)
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout.split() == ["False", "True"], "pyarrow 를 import 하거나 stdout 을 바꾸면 안 된다"


def test_grade_system_has_single_source():
    assert build_grading_train.GRADE_SYSTEM is run_eval.GRADE_SYSTEM


# ---------- D. 네트워크 차단 ----------

def test_network_is_blocked_for_urlopen():
    with pytest.raises(RuntimeError, match="network access is disabled"):
        urllib.request.urlopen("http://127.0.0.1:11434/api/tags", timeout=1)


def test_network_is_blocked_for_socket_connections():
    with pytest.raises(RuntimeError, match="network access is disabled"):
        socket.create_connection(("127.0.0.1", 11434), timeout=1)
