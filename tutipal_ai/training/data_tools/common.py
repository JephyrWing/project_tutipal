"""data_tools 공통 도우미: 저장소 밖 출력 확인, JSONL 읽기·쓰기, 콘솔 UTF-8."""

from __future__ import annotations

import argparse
import contextlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import NoReturn

TRAINING_DIR = Path(__file__).resolve().parents[1]
GIT_TIMEOUT_SECONDS = 30
# 생성기(build_grading_train·build_grading_c 등)의 기본 --seed. 기존 LLM 캐시는 이 seed 로 만들어졌다.
DEFAULT_SEED = 20261120


def seed_key_suffix(seed: int) -> str:
    """LLM 추론 seed 를 캐시 키 끝에 붙일 꼬리표.

    기본 seed 면 빈 문자열이라 seed 를 키에 넣기 전의 옛 키와 같다. 그래서 기본 seed 로 만든 기존 캐시
    파일을 고치지 않고 그대로 이어 쓰고, 다른 seed 로 돌리면 옛 추론 결과를 재사용하지 않는다.
    """
    return "" if seed == DEFAULT_SEED else f":seed={seed}"


def repo_root() -> Path:
    """이 파일이 들어 있는 git 체크아웃(워크트리 포함)의 최상위 폴더."""
    for p in TRAINING_DIR.parents:
        if (p / ".git").exists():
            return p
    return TRAINING_DIR.parents[1]  # tutipal_ai/training → 저장소 최상위


def is_inside(path: str | os.PathLike, root: Path) -> bool:
    """path 가 root 안(같은 폴더 포함)이면 True. `..`·대소문자(Windows)를 정규화해 비교한다.

    드라이브가 다르면 False. 그 밖에 비교할 수 없으면(경로 형식이 섞인 경우 등) 안전하게 True.
    """
    target = os.path.normcase(os.path.abspath(os.path.realpath(path)))
    base = os.path.normcase(os.path.abspath(os.path.realpath(root)))
    if os.path.splitdrive(target)[0] != os.path.splitdrive(base)[0]:
        return False
    try:
        return os.path.commonpath([target, base]) == base
    except ValueError:
        return True


def _ancestors(path: str) -> list[str]:
    """path 와 그 위 폴더들(path 자신부터 드라이브 루트까지)."""
    out, cur = [], path
    while True:
        out.append(cur)
        parent = os.path.dirname(cur)
        if parent == cur:
            return out
        cur = parent


def _has_git_marker(folder: str) -> bool:
    """folder/.git(폴더 또는 워크트리용 파일)이 있으면 True. 확인할 수 없는 오류도 True(안전하게)."""
    try:
        os.lstat(os.path.join(folder, ".git"))
    except (FileNotFoundError, NotADirectoryError):
        return False
    except OSError:
        return True
    return True


def _git_toplevel(folder: str) -> str | None:
    """folder 가 git 작업 트리 안이면 그 최상위 폴더. git 이 없거나 작업 트리가 아니면 None.

    GIT_DIR·GIT_CEILING_DIRECTORIES 같은 환경 변수가 판정을 바꾸지 않게 GIT_* 를 지우고,
    다른 사용자 소유 폴더(dubious ownership)에서도 판정하도록 safe.directory=* 를 준다.
    """
    env = {k: v for k, v in os.environ.items() if not k.upper().startswith("GIT_")}
    try:
        proc = subprocess.run(
            ["git", "-c", "safe.directory=*", "-C", folder, "rev-parse", "--show-toplevel"],
            capture_output=True, text=True, encoding="utf-8", errors="replace", env=env,
            timeout=GIT_TIMEOUT_SECONDS, check=False,
        )
    except FileNotFoundError:
        return None  # git 미설치: .git 표식 검사(_has_git_marker)만으로 판정한다
    except (OSError, subprocess.SubprocessError):
        return folder  # 실행은 됐는데 판정하지 못함 → 안쪽으로 본다(안전하게)
    if proc.returncode != 0:
        return None
    return proc.stdout.strip() or folder


UNC_PREFIX = "\\" * 2


def _is_unc(path: str) -> bool:
    return path.startswith((UNC_PREFIX, "//"))


def output_dir_problem(value: str) -> str | None:
    """출력 폴더로 쓰면 안 되는 이유(없으면 None). 판정하지 못하면 거부한다(fail closed).

    - 빈 값: Path("") 는 현재 폴더라 거부한다.
    - UNC·장치 경로(백슬래시 두 개로 시작: 네트워크 공유, 관리 공유, 확장 경로, 볼륨 GUID 경로):
      같은 폴더를 다른 이름으로 가리킬 수 있어 저장소 안인지 확실히 가릴 수 없으므로 모두 거부한다.
      로컬 드라이브 경로만 쓴다.
    - 어느 git 작업 트리 안이든 거부한다: 이 저장소, 워크트리의 원래 체크아웃, 상위 폴더 저장소 모두.
      대상과 그 위 폴더들에서 루트까지 `.git` 표식을 찾고(git 없이도 동작),
      git 이 있으면 가장 가까운 기존 상위 폴더에서 `git rev-parse --show-toplevel` 로 한 번 더 확인한다.
    - 이 저장소 최상위와 같은 폴더(os.path.samefile)인 상위 폴더가 있으면 거부한다(별칭 경로 대비).
    """
    if not value or not value.strip():
        return "빈 경로는 현재 폴더를 뜻하므로 쓸 수 없다"
    raw = os.fspath(value)
    if _is_unc(raw) or _is_unc(os.path.abspath(raw)):
        return f"UNC·장치 경로({UNC_PREFIX} 로 시작)는 쓸 수 없다. 로컬 드라이브 경로를 쓴다"
    target = os.path.abspath(raw)
    root = repo_root()
    if is_inside(target, root):
        return f"이 저장소({root}) 안이다"
    candidates = _ancestors(target)
    real = os.path.realpath(target)
    if os.path.normcase(real) != os.path.normcase(target):
        candidates += _ancestors(real)  # 정션·심볼릭 링크로 실제 위치가 다를 때
    existing = [p for p in candidates if os.path.isdir(p)]
    for p in candidates:
        if _has_git_marker(p):
            return f"git 작업 트리({p}) 안이다"
    for p in existing:
        try:
            if os.path.samefile(p, root):
                return f"이 저장소({root})와 같은 폴더다"
        except OSError:
            return f"{p} 를 확인할 수 없다"
    if existing:
        top = _git_toplevel(existing[0])
        if top is not None:
            return f"git 작업 트리({top}) 안이다"
    return None


def require_outside_repo(parser: argparse.ArgumentParser, args: argparse.Namespace, *names: str) -> None:
    """출력 폴더 인자가 git 작업 트리 안(또는 판정 불가)이면 아무것도 쓰기 전에 종료 코드 2로 끝낸다.

    값이 None(인자를 안 줌)인 경우만 건너뛴다. 빈 문자열은 거부한다.
    """
    for name in names:
        value = getattr(args, name.lstrip("-").replace("-", "_"))
        if value is None:
            continue
        why = output_dir_problem(value)
        if why:
            parser.error(f"{name} 는 git 저장소 밖 로컬 데이터 폴더여야 한다 (학습 데이터를 커밋하지 않도록): "
                         f"{value!r} — {why}")


def require_args(parser: argparse.ArgumentParser, args: argparse.Namespace, *names: str) -> None:
    """argparse required 대신 쓸 때(다른 모드가 있을 때) 빠진 인자를 이름과 함께 알린다."""
    missing = [n for n in names if not getattr(args, n.lstrip("-").replace("-", "_"))]
    if missing:
        parser.error("the following arguments are required: " + ", ".join(missing))


def load_jsonl(path: str | os.PathLike, required: tuple[str, ...] = ()) -> list[dict]:
    """JSONL 을 읽는다. 줄마다 JSON 객체여야 하고 required 필드가 있어야 한다(어기면 `파일:줄` 이 든 ValueError).

    UTF-8 BOM 은 eval.leakcheck 와 같게 허용한다(utf-8-sig). 빈 줄은 건너뛴다.
    """
    name = os.path.basename(os.fspath(path))
    rows = []
    with open(path, encoding="utf-8-sig") as f:
        for no, line in enumerate(f, 1):
            if not line.strip():
                continue
            try:
                obj = json.loads(line)
            except (ValueError, RecursionError):
                raise ValueError(f"{name}:{no}: JSON 형식이 아님") from None
            if not isinstance(obj, dict):
                # CLI 가 입력 오류(ValueError)를 종료 코드 2로 바꾸므로 TypeError 대신 ValueError 로 통일한다.
                raise ValueError(f"{name}:{no}: JSON 객체가 아님")  # noqa: TRY004
            for field in required:
                if field not in obj:
                    raise ValueError(f"{name}:{no}: 필수 필드 없음: {field}")
            rows.append(obj)
    return rows


def jsonl_text(rows: list[dict]) -> str:
    return "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows)


def write_files_atomic(files: dict[Path, tuple[str, str]]) -> None:
    """{경로: (내용, 인코딩)} 을 모두 임시 파일에 다 쓴 뒤에야 한꺼번에 바꿔 넣는다.

    - 임시 파일 쓰기 도중 실패하면(디스크 부족 등) 기존 파일은 그대로 남고 임시 파일은 지운다.
    - 바꿔 넣기 전에 기존 파일을 같은 폴더의 백업(.bak)으로 복사해 둔다. 복사라서 바꿔 넣는 동안에도
      대상 파일이 사라지는 순간이 없다.
    - 바꿔 넣기(os.replace) 도중 실패하면 이미 바뀐 파일을 백업으로 되돌리고(새로 생긴 파일은 지움)
      예외를 다시 던진다. 일부만 새 세대인 상태로 남지 않는다.
    - 성공하면 백업을 지운다. 되돌리기마저 실패한 백업은 데이터 보존을 위해 지우지 않는다.
    """
    temps: list[tuple[str, Path]] = []
    backups: dict[Path, str] = {}
    keep: set[str] = set()
    try:
        for path, (text, encoding) in files.items():
            fd, tmp = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
            temps.append((tmp, path))
            try:
                f = os.fdopen(fd, "w", encoding=encoding, newline="")
            except BaseException:
                os.close(fd)
                raise
            with f:
                f.write(text)
        for _, path in temps:
            if path.exists():
                fd, bak = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".bak", dir=path.parent)
                os.close(fd)
                backups[path] = bak
                shutil.copy2(path, bak)
        replaced: list[Path] = []
        try:
            for tmp, path in temps:
                os.replace(tmp, path)
                replaced.append(path)
        except BaseException:
            for path in replaced:
                try:
                    if path in backups:
                        os.replace(backups[path], path)
                    else:
                        os.remove(path)  # 이번에 새로 생긴 파일
                except OSError:
                    if path in backups:
                        keep.add(backups[path])  # 되돌리지 못한 원본은 백업으로라도 남긴다
            raise
    finally:
        for tmp, _ in temps:
            with contextlib.suppress(FileNotFoundError):
                os.remove(tmp)
        for bak in backups.values():
            if bak not in keep:
                with contextlib.suppress(FileNotFoundError):
                    os.remove(bak)


def _norm(path: str | os.PathLike) -> str:
    return os.path.normcase(os.path.abspath(os.path.realpath(path)))


def refuse_input_as_output(parser: argparse.ArgumentParser, inputs: dict[str, list],
                           outputs: list[Path]) -> None:
    """사람이 채운 입력 파일(검수표·override 등)이 이번 실행의 출력 파일과 같은 경로면 종료 코드 2.

    출력은 임시 파일 + 바꿔 넣기로 쓰므로, 같은 경로를 입력으로 주면 사람이 채운 내용이 생성물로 덮인다.
    inputs: {옵션 이름: [경로, ...]} (None 은 건너뜀).
    """
    targets = {_norm(p): Path(p).name for p in outputs}
    for name, paths in inputs.items():
        for p in paths or []:
            if p is not None and _norm(p) in targets:
                parser.error(f"{name} {p!r} 는 이 명령이 덮어쓰는 출력 파일({targets[_norm(p)]})이다. "
                             "다른 이름으로 저장한 뒤 지정한다")


def exit_error(message: str) -> NoReturn:
    """입력·데이터 문제를 stderr 에 알리고 종료 코드 2로 끝낸다(argparse 인자 오류와 같은 코드)."""
    print(f"오류: {message}", file=sys.stderr)
    raise SystemExit(2)


def write_jsonl(path: str | os.PathLike, rows: list[dict]) -> None:
    write_files_atomic({Path(path): (jsonl_text(rows), "utf-8")})


def utf8_stdout() -> None:
    """Windows 콘솔에서 한국어 출력이 깨지지 않게 한다. main() 에서만 부른다(import 부작용 금지)."""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8", errors="backslashreplace")
