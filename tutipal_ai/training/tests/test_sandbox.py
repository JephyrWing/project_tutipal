import pytest

from eval import sandbox

TEST = "from solution import add\n\ndef test_add():\n    assert add(1, 2) == 3\n"


def test_local_passes_correct_solution():
    assert sandbox.run_local("def add(a, b):\n    return a + b\n", TEST).status == "passed"


def test_local_fails_wrong_solution():
    assert sandbox.run_local("def add(a, b):\n    return a - b\n", TEST).status == "failed"


def test_local_reports_error_for_missing_function_or_syntax_error():
    assert sandbox.run_local("def plus(a, b):\n    return a + b\n", TEST).status == "error"
    assert sandbox.run_local("def add(a, b)\n    return a + b\n", TEST).status == "error"


def test_local_times_out_infinite_loop():
    result = sandbox.run_local("def add(a, b):\n    while True:\n        pass\n", TEST, timeout=3)
    assert result.status == "timeout"


def test_local_does_not_leak_secret_env(monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "sk-should-not-leak")
    code = "import os\n\ndef add(a, b):\n    return a + b + (100 if 'OPENAI_API_KEY' in os.environ else 0)\n"
    assert sandbox.run_local(code, TEST).status == "passed"


def test_docker_command_isolates_network_and_filesystem(tmp_path):
    cmd = sandbox.docker_command(tmp_path, "n", "img")
    joined = " ".join(cmd)
    assert "--network none" in joined
    assert "--read-only" in cmd
    assert f"{tmp_path}:/work:ro" in cmd
    assert "--cap-drop ALL" in joined


def test_docker_failure_needs_docker_message():
    assert sandbox._is_docker_failure(125, "docker: Error response from daemon: No such image")
    assert not sandbox._is_docker_failure(125, "")  # 모델 코드가 os._exit(125)
    assert not sandbox._is_docker_failure(1, "docker: x")


def _docker_ready() -> bool:
    try:
        sandbox.check_docker()
        return True
    except sandbox.SandboxUnavailable:
        return False


docker_only = pytest.mark.skipif(not _docker_ready(), reason="Docker 데몬 또는 채점 이미지 없음")


@docker_only
def test_docker_scores_pass_and_fail():
    assert sandbox.run_docker("def add(a, b):\n    return a + b\n", TEST).status == "passed"
    assert sandbox.run_docker("def add(a, b):\n    return 0\n", TEST).status == "failed"


@docker_only
def test_docker_blocks_network_and_writes():
    code = (
        "import socket\n\n"
        "def add(a, b):\n"
        "    try:\n"
        "        socket.create_connection(('1.1.1.1', 53), timeout=2)\n"
        "        return -1\n"
        "    except OSError:\n"
        "        pass\n"
        "    try:\n"
        "        open('/work/x.txt', 'w')\n"
        "        return -2\n"
        "    except OSError:\n"
        "        return a + b\n"
    )
    assert sandbox.run_docker(code, TEST).status == "passed"


@docker_only
def test_docker_times_out_and_cleans_up():
    import subprocess

    result = sandbox.run_docker("def add(a, b):\n    while True:\n        pass\n", TEST, timeout=3)
    assert result.status == "timeout"
    left = subprocess.run(["docker", "ps", "-q", "--filter", "name=tutipal-eval-"],
                          capture_output=True, text=True).stdout.strip()
    assert left == ""


def test_unknown_mode_rejected():
    with pytest.raises(ValueError):
        sandbox.run_tests("", "", mode="nope")
