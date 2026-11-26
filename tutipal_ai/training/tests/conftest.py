"""공통 테스트 설정: 테스트 중 네트워크 접속을 막는다.

LLM(Ollama)·외부 서버에 실수로 접속하지 않도록 urllib.request.urlopen, socket.create_connection,
socket.socket.connect / connect_ex 를 모든 테스트에서 예외로 바꾼다. 네트워크가 필요한 기능은 가짜(fake)로 테스트한다.
"""

import socket
import urllib.request

import pytest

NETWORK_BLOCKED_MESSAGE = "network access is disabled in tests"


class NetworkBlockedError(RuntimeError):
    """테스트에서 네트워크 접속을 시도했다."""


def _blocked(*args, **kwargs):
    raise NetworkBlockedError(NETWORK_BLOCKED_MESSAGE)


@pytest.fixture(autouse=True)
def _no_network(monkeypatch):
    monkeypatch.setattr(urllib.request, "urlopen", _blocked)
    monkeypatch.setattr(socket, "create_connection", _blocked)
    monkeypatch.setattr(socket.socket, "connect", _blocked)
    monkeypatch.setattr(socket.socket, "connect_ex", _blocked)
