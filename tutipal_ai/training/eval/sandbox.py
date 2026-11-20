"""모델이 만든 코드를 pytest로 채점한다.

- docker(기본): 네트워크 없음, 읽기 전용 파일, 메모리·CPU·프로세스 수 제한, nobody 사용자.
- local: 이 PC의 Python으로 바로 실행한다. 격리가 없으므로 Docker를 쓸 수 없을 때 참고용으로만 쓴다.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import uuid
from dataclasses import dataclass
from pathlib import Path

DEFAULT_IMAGE = "tutipal-eval-sandbox:1"
DOCKERFILE = Path(__file__).with_name("sandbox.Dockerfile")
_OUTPUT_TAIL = 2000
PYTEST_ARGS = ["-m", "pytest", "-q", "-x", "-p", "no:cacheprovider", "test_solution.py"]


@dataclass(frozen=True)
class TestResult:
    __test__ = False  # pytest가 테스트 클래스로 수집하지 않게

    status: str  # passed | failed | timeout | error
    output: str

    @property
    def passed(self) -> bool:
        return self.status == "passed"


class SandboxUnavailable(RuntimeError):
    pass


def _status_from_exit(code: int) -> str:
    # pytest 종료 코드: 0 통과, 1 실패, 그 밖(수집 오류·테스트 없음 등)은 오류
    if code == 0:
        return "passed"
    if code == 1:
        return "failed"
    return "error"


def _tail(text: str) -> str:
    return text[-_OUTPUT_TAIL:]


def _write_case(workdir: Path, solution_code: str, test_code: str) -> None:
    (workdir / "solution.py").write_text(solution_code, encoding="utf-8")
    (workdir / "test_solution.py").write_text(test_code, encoding="utf-8")


def _minimal_env() -> dict[str, str]:
    """API 키 같은 환경변수를 넘기지 않는다. Windows Python 실행에 필요한 값만 둔다."""
    env = {"PYTHONDONTWRITEBYTECODE": "1", "PYTHONIOENCODING": "utf-8"}
    for key in ("PATH", "SYSTEMROOT", "TEMP", "TMP"):
        if key in os.environ:
            env[key] = os.environ[key]
    return env


def run_local(solution_code: str, test_code: str, timeout: float = 20) -> TestResult:
    with tempfile.TemporaryDirectory(prefix="tutipal-eval-") as tmp:
        workdir = Path(tmp)
        _write_case(workdir, solution_code, test_code)
        try:
            proc = subprocess.run(
                [sys.executable, "-I", *PYTEST_ARGS],
                cwd=workdir, env=_minimal_env(), capture_output=True, text=True,
                encoding="utf-8", errors="replace", timeout=timeout,
            )
        except subprocess.TimeoutExpired:
            return TestResult("timeout", f"{timeout}초 초과")
        return TestResult(_status_from_exit(proc.returncode), _tail(proc.stdout + proc.stderr))


def docker_command(workdir: Path, name: str, image: str) -> list[str]:
    return [
        "docker", "run", "--rm", "--name", name,
        "--network", "none",
        "--memory", "512m", "--memory-swap", "512m",
        "--cpus", "1", "--pids-limit", "64",
        "--read-only", "--tmpfs", "/tmp:size=64m",
        "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
        "--user", "65534:65534",
        "-e", "PYTHONDONTWRITEBYTECODE=1",
        "-v", f"{workdir}:/work:ro", "-w", "/work",
        image, "python", *PYTEST_ARGS,
    ]


def _is_docker_failure(code: int, stderr: str) -> bool:
    """docker run 자체 실패(125: 데몬·이미지 문제). 모델 코드가 os._exit(125)로 흉내 내도 오류로만 남게
    docker의 오류 문구까지 확인한다."""
    return code == 125 and ("docker:" in stderr or "Error response from daemon" in stderr)


def run_docker(solution_code: str, test_code: str, timeout: float = 20,
               image: str = DEFAULT_IMAGE) -> TestResult:
    with tempfile.TemporaryDirectory(prefix="tutipal-eval-") as tmp:
        workdir = Path(tmp)
        _write_case(workdir, solution_code, test_code)
        name = f"tutipal-eval-{uuid.uuid4().hex[:12]}"
        try:
            # 컨테이너 시작 시간을 고려해 10초 여유
            proc = subprocess.run(
                docker_command(workdir, name, image), capture_output=True, text=True,
                encoding="utf-8", errors="replace", timeout=timeout + 10,
            )
        except subprocess.TimeoutExpired:
            subprocess.run(["docker", "rm", "-f", name], capture_output=True)
            return TestResult("timeout", f"{timeout}초 초과")
        if _is_docker_failure(proc.returncode, proc.stderr):
            raise SandboxUnavailable(_tail(proc.stderr))
        return TestResult(_status_from_exit(proc.returncode), _tail(proc.stdout + proc.stderr))


def check_docker(image: str = DEFAULT_IMAGE) -> None:
    """Docker 데몬과 채점 이미지가 준비됐는지 확인한다. 아니면 고치는 방법을 담아 예외를 낸다."""
    if shutil.which("docker") is None:
        raise SandboxUnavailable("docker 명령을 찾을 수 없습니다. Docker Desktop을 설치하거나 --sandbox local을 쓰세요.")
    info = subprocess.run(["docker", "info", "--format", "{{.ServerVersion}}"], capture_output=True, text=True)
    if info.returncode != 0:
        raise SandboxUnavailable("Docker 데몬이 꺼져 있습니다. Docker Desktop을 실행한 뒤 다시 시도하세요.")
    found = subprocess.run(["docker", "image", "inspect", image], capture_output=True, text=True)
    if found.returncode != 0:
        raise SandboxUnavailable(
            f"채점 이미지 {image}가 없습니다. 먼저 빌드하세요:\n"
            f"  docker build -t {image} -f {DOCKERFILE} {DOCKERFILE.parent}"
        )


def run_tests(solution_code: str, test_code: str, mode: str = "docker", timeout: float = 20,
              image: str = DEFAULT_IMAGE) -> TestResult:
    if mode == "docker":
        return run_docker(solution_code, test_code, timeout, image)
    if mode == "local":
        return run_local(solution_code, test_code, timeout)
    raise ValueError(f"알 수 없는 sandbox 모드: {mode}")
